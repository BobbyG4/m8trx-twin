package com.m8trx.twin.layer2

import com.m8trx.twin.domain.CustomerExited
import com.m8trx.twin.domain.CustomerReachedZone
import com.m8trx.twin.domain.EasGateCrossed
import com.m8trx.twin.domain.ItemConcealed
import com.m8trx.twin.domain.SaleCompleted
import com.m8trx.twin.domain.SaleLine
import com.m8trx.twin.layer1.BrowseEpisode
import com.m8trx.twin.layer1.FixtureSet
import com.m8trx.twin.layer1.ImpressionOracle
import com.m8trx.twin.layer1.Pt
import com.m8trx.twin.layer1.RoleZone
import com.m8trx.twin.layer1.ZoneAffinityModel
import com.m8trx.twin.layer1.ZoneRole
import com.m8trx.twin.runtime.GeneratorContext
import java.time.Duration
import kotlin.math.max
import kotlin.random.Random as KRandom

/**
 * Layer 2 — single-actor arcs. A journey owns one shopper from entry to exit: which zones they visit, which
 * fixtures they dwell at, whether they buy.
 *
 * **Dwell is emitted at Xovis fidelity, not as waypoints.** Every fixture visit is a [BrowseEpisode] at
 * 5 Hz. This is load-bearing: below ~1 Hz core's dwell and look clocks reset on every sample and no
 * impression can ever form, however long the shopper stands there — validated live against the twin edge
 * 2026-07-28. A journey that emitted one pin per zone would look completely correct and produce nothing.
 *
 * Zone selection uses [ZoneAffinityModel], re-keyed off `(space_type, zone_type)` + structural code so it
 * resolves in all 10 stores rather than only Denver.
 */
class Journeys(
    private val fixtures: FixtureSet,
    private val zones: List<RoleZone>,
    private val catalog: StoreCatalog,
    private val model: com.m8trx.twin.layer3.OperatingModel,
) {
    private val browse = BrowseEpisode(fixtures, emitHz = 5.0)

    /**
     * EAS gates for this store, read from its own layout. Empty for a store whose layout declares none —
     * and then [walkOutThroughGate] emits nothing rather than inventing a gate.
     */
    var gates: List<EasTagging.Gate> = emptyList()
        private set

    fun withGates(g: List<EasTagging.Gate>): Journeys = apply { gates = g }

    /**
     * Fixtures the impression pipeline can see, grouped by the AREA ZONE they physically sit in.
     *
     * **Keyed off `in_area_zone`, NOT `department`** — that was the bug. Department-keying reached only the
     * fixtures inside a sport-universe band, and **19 of Denver's 115 fixtures carry no department**: the 6
     * GPS cases (`Z-06`), 3 accessories bays (`Z-07`), gait treadmill (`Z-09`), footwear bench (`Z-08`),
     * 4 checkout counters + impulse rack (`Z-02`), and **all three circles** — `PI-01`, `PI-03`, `RR-02` —
     * which sit in the entrance zone `Z-01`. The full day of 2026-07-28 covered 97 distinct fixtures and
     * those 18 never participated, despite `PI-01` being individually proven the same day (first circle
     * impression ever). Read as a heatmap that looks like the platform dropping them.
     *
     * `in_area_zone` is verified present, non-null and orphan-free on every fixture in all 10 stores, and it
     * agrees with the band department wherever one exists — so this subsumes the old department map rather
     * than sitting beside it, and resolves in every store, not only Denver. Same discipline as the S15
     * zone-affinity re-key: key off structure that actually exists everywhere.
     */
    private val fixturesByZone: Map<String, List<String>> = fixtures.impressionVisible
        .filter { it.inAreaZone != null }
        .groupBy({ it.inAreaZone!! }, { it.code })

    private val anyVisibleFixture: List<String> = fixtures.impressionVisible.map { it.code }

    fun run(journeyId: String, ctx: GeneratorContext, customerId: String, rng: KRandom) {
        when (journeyId) {
            "ShopAndBuy" -> shopAndBuy(ctx, customerId, rng, tryOn = false)
            "TryOnAndPartialBuy" -> shopAndBuy(ctx, customerId, rng, tryOn = true)
            "Shoplift" -> shoplift(ctx, customerId, rng)
            else -> browseAndLeave(ctx, customerId, rng)
        }
    }

    // ── the (1 - conversion) bulk: walk, dwell, leave without buying ───────────

    private fun browseAndLeave(ctx: GeneratorContext, customerId: String, rng: KRandom) {
        val visits = plannedZoneVisits(rng)
        var cursorMs = ctx.clock.now().toEpochMilli()
        for (z in visits) {
            cursorMs = dwellAtZone(ctx, customerId, z, cursorMs, rng)
        }
        finish(ctx, customerId, cursorMs)
    }

    // ── the buyers ────────────────────────────────────────────────────────────

    private fun shopAndBuy(ctx: GeneratorContext, customerId: String, rng: KRandom, tryOn: Boolean) {
        val lines = catalog.sampleBasket(rng, model)
        var cursorMs = ctx.clock.now().toEpochMilli()

        // Browse the fixtures that actually hold what they buy — this is what ties dwell to the basket.
        for (line in lines.distinctBy { it.fixture }) {
            val code = line.fixture.takeIf { fixtures.impressionVisible.any { f -> f.code == it } }
                ?: anyVisibleFixture.random(rng)
            cursorMs = dwellAtFixture(ctx, customerId, code, fixtureDwellMs(rng), cursorMs)
        }

        if (tryOn) {
            // Try-on zones are their own space; the walk there is a gap, so clocks reset — correct.
            val role = if (rng.nextDouble() < 0.5) ZoneRole.FOOTWEAR_BENCH else ZoneRole.FITTING_ROOM
            zones.firstOrNull { it.role == role }?.let {
                ctx.bus.publish(CustomerReachedZone(ctx.clock.now(), customerId, it.zoneCode))
            }
            cursorMs += dwellMsFor(ZoneRole.FOOTWEAR_BENCH, rng)
        }

        zones.firstOrNull { it.role == ZoneRole.CHECKOUT }?.let {
            ctx.bus.publish(CustomerReachedZone(ctx.clock.now(), customerId, it.zoneCode))
        }
        cursorMs += dwellMsFor(ZoneRole.CHECKOUT, rng)

        ctx.bus.publish(SaleCompleted(ctx.clock.now(), customerId, lines))
        finish(ctx, customerId, cursorMs)
    }

    // ── loss prevention ───────────────────────────────────────────────────────

    /**
     * **Shoplift** — the LP arc, and the point is that the alarm is *earned*.
     *
     * Browse a fixture that actually holds EAS-tagged stock (a real [BrowseEpisode], so a real impression
     * forms) → conceal a tagged unit from that same fixture → **skip checkout entirely** → walk out through
     * the gate, emitting an `objLocation` path that genuinely crosses the gate line.
     *
     * ## Why the crossing is emitted as samples rather than asserted
     *
     * The gate crossing is **derived from the track**, not declared alongside it. [EasGateCrossed] is
     * published only after the walk-out samples have been emitted, and it carries the EPCs the shopper is
     * actually holding. An alarm emitter subscribes to that. Publishing an alarm directly from here would
     * fill the LP tile with events that have no shopper behind them — a populated-looking surface that means
     * nothing, which is the failure this project spent 2026-07-31 removing everywhere else.
     *
     * ## What is real and what is twin's
     *
     * Real: the gate (`CS-01`, present in all 10 stores with actual SRF geometry), the fixture, the dwell,
     * the SKU, the EPC. **Twin's:** which units carry a tag — see [EasTagging], and it is declared there
     * because tag state is the vendor's domain and the platform never sees it.
     *
     * No [SaleCompleted] is published, so this shopper correctly does not appear in conversion or revenue —
     * the §1 reconciliation identity stays honest and shrink shows up as the gap it really is.
     */
    private fun shoplift(ctx: GeneratorContext, customerId: String, rng: KRandom) {
        var cursorMs = ctx.clock.now().toEpochMilli()

        // Browse a couple of ordinary zones first — a thief who beelines to the target is not a shopper,
        // and the dwell before the act is what makes the track readable afterwards.
        plannedZoneVisits(rng).take(2).forEach { cursorMs = dwellAtZone(ctx, customerId, it, cursorMs, rng) }

        // The target: a tagged SKU, browsed at the fixture that actually holds it.
        val target = catalog.taggedSkus().takeIf { it.isNotEmpty() }?.random(rng)
        if (target != null) {
            val code = target.fixture.takeIf { c -> fixtures.impressionVisible.any { it.code == c } }
                ?: anyVisibleFixture.random(rng)
            cursorMs = dwellAtFixture(ctx, customerId, code, fixtureDwellMs(rng), cursorMs)
            ctx.bus.publish(ItemConcealed(ctx.clock.now(), customerId, target.itemCd, target.ean, target.priceUsd, code))
        }

        // NO checkout. That absence is the whole arc — it is why the gate crossing alarms.
        cursorMs = walkOutThroughGate(ctx, customerId, cursorMs, carried = listOfNotNull(target?.ean), paid = false, rng = rng)
        finish(ctx, customerId, cursorMs)
    }

    /**
     * Emit a walk that **crosses the EAS gate line outbound**, then publish the crossing derived from it.
     *
     * The path steps from inside the store (`y > gate.y`) to outside (`y < gate.y`) at the same 5 Hz the rest
     * of the journey uses, so the crossing is visible in the position stream a subscriber sees — not merely
     * in an event twin asserted. `viewDir` points the way they are walking.
     *
     * Returns the advanced cursor. If the store's layout declares no `eas_gate` slice the walk is skipped and
     * no crossing is published — **absence stays absence**, rather than being papered over with a synthetic
     * gate that would make every store look instrumented.
     */
    private fun walkOutThroughGate(
        ctx: GeneratorContext,
        customerId: String,
        cursorMs: Long,
        carried: List<String>,
        paid: Boolean,
        rng: KRandom,
    ): Long {
        val gate = gates.firstOrNull() ?: return cursorMs
        val stepMs = (1000.0 / EMIT_HZ).toLong()
        val exit = gate.crossingPoint(0.3 + rng.nextDouble() * 0.4) // through the middle of the doorway, not the jamb
        val approach = APPROACH_MM

        val samples = (0..GATE_WALK_STEPS).map { i ->
            val frac = i.toDouble() / GATE_WALK_STEPS
            // inside (+approach) → outside (-approach); the sign change IS the crossing.
            val y = exit.y + approach - frac * (approach * 2)
            ImpressionOracle.Sample(
                tsMs = cursorMs + i * stepMs,
                p = Pt(exit.x + (rng.nextDouble() - 0.5) * 200, y),
                viewDir = Pt(0.0, -1.0), // facing the door
                hasTag = false,
            )
        }
        ctx.sink.emitSamples(customerId, samples)

        val crossed = samples.first().p.y > gate.y && samples.last().p.y < gate.y
        check(crossed) { "gate walk did not cross ${gate.code} at y=${gate.y} — the crossing must be real, not asserted" }
        ctx.bus.publish(EasGateCrossed(ctx.clock.now(), customerId, gate.code, gate.zoneCode, outbound = true, carriedEpcs = carried, paid = paid))
        return cursorMs + (GATE_WALK_STEPS + 1) * stepMs
    }

    // ── mechanics ─────────────────────────────────────────────────────────────

    /** Pick the zones this session visits, sampling each role independently against its affinity. */
    private fun plannedZoneVisits(rng: KRandom): List<RoleZone> {
        val conversion = model.conversion.overallRate.value
        val target = max(1, model.dwell.zonesVisitedPerSession.value)
        val candidates = zones.filter { it.role.shopperReachable && it.role != ZoneRole.FIXTURE }
            .filter { rng.nextDouble() < ZoneAffinityModel.probability(it.role, conversion) }
        return if (candidates.isEmpty()) zones.filter { it.role == ZoneRole.DEPARTMENT_BAND }.take(1) else candidates.shuffled(rng).take(target)
    }

    /**
     * Dwell in a zone by browsing SEVERAL fixtures inside it, splitting the zone's dwell budget between
     * them — not by standing at one rack for the whole zone median.
     *
     * §8's `by_zone_min` (6 min for a department band) is **ZONE** dwell: the time a shopper spends in that
     * band, across the several fixtures they look at while there. Charging the full zone median to a single
     * fixture multiplies the day by the fixtures-per-visit factor — it was why a generated day came to 4.3M
     * samples and a single session ran 31 minutes. Per-rack dwell of 30–90s matches both the literature and
     * observed behaviour.
     */
    private fun dwellAtZone(ctx: GeneratorContext, customerId: String, z: RoleZone, cursorMs: Long, rng: KRandom): Long {
        ctx.bus.publish(CustomerReachedZone(ctx.clock.now(), customerId, z.zoneCode))
        val zoneBudget = dwellMsFor(z.role, rng)

        // Fixtures are reached by the zone they SIT IN, so non-department zones (entrance promo islands,
        // GPS cases, accessories wall, benches, impulse rack) are browsable too — see [fixturesByZone].
        val pool = fixturesByZone[z.zoneCode] ?: return cursorMs + zoneBudget
        if (pool.isEmpty()) return cursorMs + zoneBudget

        // Being in a zone is not the same as stopping at a rack in it. The entrance is crossed by every
        // session but only catches a minority at the promo island; a department band is why you came.
        // Without this gate, zone-keyed pools would put all 790 shoppers at the three entrance circles.
        if (rng.nextDouble() >= ZoneAffinityModel.fixtureBrowseRate(z.role)) return cursorMs + zoneBudget

        var cursor = cursorMs
        var remaining = zoneBudget
        val visits = 1 + rng.nextInt(MAX_FIXTURES_PER_ZONE)
        repeat(visits) {
            if (remaining < MIN_DWELL_MS) return@repeat
            val d = minOf(fixtureDwellMs(rng), remaining)
            if (d < MIN_DWELL_MS) return@repeat
            cursor = dwellAtFixture(ctx, customerId, pool.random(rng), d, cursor)
            remaining -= (d + WALK_GAP_MS)
        }
        return cursor
    }

    /**
     * Time at ONE fixture: 30–90s, floored at [MIN_DWELL_MS] so a visit always clears
     * `millisTillImpression` (5000ms) and can actually produce an impression.
     */
    private fun fixtureDwellMs(rng: KRandom): Long =
        (FIXTURE_DWELL_MIN_MS + rng.nextDouble() * (FIXTURE_DWELL_MAX_MS - FIXTURE_DWELL_MIN_MS)).toLong()
            .coerceAtLeast(MIN_DWELL_MS)

    private fun dwellAtFixture(ctx: GeneratorContext, customerId: String, fixtureCode: String, dwellMs: Long, cursorMs: Long): Long {
        val samples = browse.browse(fixtureCode, dwellMs, cursorMs, java.util.Random(cursorMs xor fixtureCode.hashCode().toLong()).asKotlinRandom())
        ctx.sink.emitSamples(customerId, samples)
        // +1 walk gap to the next fixture; exceeding the 1000ms allowance is correct — a new fixture
        // is a new dwell episode, and core resets both clocks on the gap.
        return cursorMs + dwellMs + WALK_GAP_MS
    }

    private fun dwellMsFor(role: ZoneRole, rng: KRandom): Long {
        val median = ZoneAffinityModel.dwellMedianMin(role) ?: 1.5
        // Log-normal-ish spread around the median without pulling in a stats lib: 0.55x .. 1.8x.
        val factor = 0.55 + rng.nextDouble() * 1.25
        return (median * 60_000 * factor).toLong().coerceAtLeast(MIN_DWELL_MS)
    }

    private fun finish(ctx: GeneratorContext, customerId: String, cursorMs: Long) {
        ctx.sink.evict(customerId)
        ctx.scheduler.scheduleAfter(Duration.ofMillis(max(0, cursorMs - ctx.clock.now().toEpochMilli()))) {
            ctx.bus.publish(CustomerExited(ctx.clock.now(), customerId))
        }
    }

    private companion object {
        const val WALK_GAP_MS = 4_000L
        const val MIN_DWELL_MS = 6_000L // must clear millisTillImpression (5000) or the visit produces nothing

        // Per-FIXTURE dwell. §8's by_zone_min is ZONE dwell and is split across the fixtures visited inside
        // that zone; 30-90s at one rack matches the literature and observed behaviour.
        const val FIXTURE_DWELL_MIN_MS = 30_000L
        const val FIXTURE_DWELL_MAX_MS = 90_000L
        const val MAX_FIXTURES_PER_ZONE = 3

        // Gate walk-out. Emitted at the same 5 Hz as every other episode so the crossing is visible in the
        // position stream rather than only in the event twin publishes.
        const val EMIT_HZ = 5.0
        const val GATE_WALK_STEPS = 20
        const val APPROACH_MM = 1_500.0 // start 1.5m inside, end 1.5m outside — an unambiguous sign change
    }
}

private fun java.util.Random.asKotlinRandom(): KRandom = KRandom(this.nextLong())

/** Real SKUs off the store's `assortment.csv`, used to build priced baskets. */
class StoreCatalog(private val skus: List<Sku>) {
    data class Sku(val itemCd: String, val ean: String, val priceUsd: Double, val department: String, val fixture: String, val category: String)

    private val byCategory: Map<String, List<Sku>> = skus.groupBy { it.category }

    /**
     * The stock a vendor's EAS system would have tagged — see [EasTagging] for the rule and, importantly,
     * for the statement that this is TWIN-SIDE state the platform does not hold. Denver yields 271 of 2,586.
     */
    fun taggedSkus(): List<Sku> = skus.filter { EasTagging.isTagged(it) }

    /**
     * Sample a basket: unit count around `units_per_txn`, then SKUs drawn with **price-band weighting** so
     * most lines are mid-price with an occasional high-value item — exactly what §5 of the operating model
     * specifies and what a uniform draw does not do.
     *
     * This matters more than it looks. The Denver assortment is a real Decathlon catalog: median line $55
     * but a mean of $323, because it contains Riverside carbon gravel bikes up to $11,999. Drawing SKUs
     * uniformly gave every basket a shot at a bike and produced an ATV of ~$1,900 against a modelled $58.
     * The prices are correct; uniform sampling was the error.
     *
     * Method: draw a target line price from a log-normal centred on §5 `avg_line_price_usd`, then take the
     * nearest-priced SKU in the chosen category. Real SKUs, real prices, realistic frequency — a bike is
     * reachable but rare, as in life.
     *
     * Note the model's own economics (`avg_line_price_usd` $26.40) were calibrated against the superseded
     * 871-SKU running-store catalog, not this 2,586-SKU chain assortment. Same staleness class as the §8
     * zone-affinity codes. Recalibrating §5 against the live assortment is a separate, flagged decision;
     * this sampler at least makes the generator obey the model as written.
     */
    fun sampleBasket(rng: KRandom, model: com.m8trx.twin.layer3.OperatingModel): List<SaleLine> {
        val target = model.basket.unitsPerTxn.value
        // ROUND, don't truncate. toInt() truncates, so a 2.2 target with ±0.8 spread lands in 1.4..3.0 and
        // collapses to a mean near 1.6 — a systematic ~27% under-count of units, not sampling noise.
        val units = max(1, Math.round(target + (rng.nextDouble() - 0.5) * 1.6).toInt())
        val mix = mappedUnitMix(model)
        val targetLine = model.basket.avgLinePriceUsd.value

        return (1..units).map {
            val pool = if (mix.isNotEmpty()) byCategory[weightedPick(mix, rng)] ?: skus else skus
            val sku = nearestPriced(pool, drawTargetPrice(targetLine, rng), rng)
            SaleLine(sku.itemCd, sku.ean, sku.priceUsd, sku.department, sku.fixture)
        }
    }

    /**
     * §5's buckets are catalog-agnostic names; the store's `category` column uses its own vocabulary.
     * Map bucket → store categories, drop buckets this store has none of, and renormalise. Intersecting
     * the raw names instead would silently keep only footwear/apparel/outdoor and skew every basket.
     */
    private fun mappedUnitMix(model: com.m8trx.twin.layer3.OperatingModel): Map<String, Double> {
        val bucketToCategories = mapOf(
            "footwear" to listOf("footwear"),
            "apparel" to listOf("apparel"),
            "outdoor" to listOf("outdoor"),
            "accessories_other" to listOf("accessories", "bag_pack"),
            "fitness_equipment" to listOf("outdoor"),
            "hardware_watches" to listOf("accessories"),
            "team_sport" to listOf("accessories"),
        )
        val out = mutableMapOf<String, Double>()
        model.basket.cleanUnitMix.forEach { (bucket, w) ->
            val cats = bucketToCategories[bucket]?.filter { byCategory.containsKey(it) } ?: return@forEach
            if (cats.isEmpty()) return@forEach
            cats.forEach { out[it] = (out[it] ?: 0.0) + w / cats.size }
        }
        val sum = out.values.sum()
        return if (sum <= 0.0) emptyMap() else out.mapValues { it.value / sum }
    }

    /** Log-normal around the target line price — right-skewed, so occasional expensive lines occur naturally. */
    private fun drawTargetPrice(mean: Double, rng: KRandom): Double {
        val sigma = 0.85
        val mu = kotlin.math.ln(mean) - sigma * sigma / 2.0
        // Box-Muller for a standard normal; kotlin.random has no gaussian.
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        val z = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        return kotlin.math.exp(mu + sigma * z)
    }

    /** Nearest-priced SKU to [targetPrice], with a small random tie-break so identical prices vary. */
    private fun nearestPriced(pool: List<Sku>, targetPrice: Double, rng: KRandom): Sku {
        if (pool.isEmpty()) return skus.random(rng)
        var best = pool[0]
        var bestD = Double.MAX_VALUE
        val start = rng.nextInt(pool.size)
        for (k in pool.indices) {
            val s = pool[(start + k) % pool.size]
            val d = kotlin.math.abs(s.priceUsd - targetPrice)
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        return best
    }

    private fun weightedPick(weights: Map<String, Double>, rng: KRandom): String {
        var r = rng.nextDouble() * weights.values.sum()
        for ((k, w) in weights) {
            r -= w
            if (r <= 0) return k
        }
        return weights.keys.last()
    }

    companion object {
        fun load(assortmentCsv: java.nio.file.Path): StoreCatalog {
            val lines = java.nio.file.Files.readAllLines(assortmentCsv)
            val header = splitCsv(lines.first())
            fun idx(name: String) = header.indexOf(name)
            val iItem = idx("item_cd")
            val iEan = idx("ean")
            val iPrice = idx("price_usd")
            val iDept = idx("department")
            val iFix = idx("fixture")
            val iCat = idx("category")
            val skus = lines.drop(1).mapNotNull { row ->
                val c = splitCsv(row)
                if (c.size <= maxOf(iItem, iEan, iPrice, iDept, iFix, iCat)) return@mapNotNull null
                val price = c[iPrice].toDoubleOrNull() ?: return@mapNotNull null
                Sku(c[iItem], c[iEan], price, c[iDept], c[iFix], c[iCat])
            }
            require(skus.isNotEmpty()) { "no priced SKUs parsed from $assortmentCsv" }
            return StoreCatalog(skus)
        }

        /**
         * RFC4180-ish field split — quoted fields may contain commas, and `""` is an escaped quote.
         *
         * A naive `split(",")` shifts every column after the first quoted product name, which silently
         * pulled a non-price field into `price_usd` and inflated ATV from $58 to $1,961. The catalog has
         * both cases: `"Quechua Camp Bed Air 79"" Inflatable..."` and `"S/M  5'2""-5'..."`.
         */
        internal fun splitCsv(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        sb.append('"')
                        i++
                    }
                    ch == '"' -> inQuotes = !inQuotes
                    ch == ',' && !inQuotes -> {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                    else -> sb.append(ch)
                }
                i++
            }
            out.add(sb.toString())
            return out
        }
    }
}
