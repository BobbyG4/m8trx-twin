package com.m8trx.twin.ops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.layer2.StoreCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

/**
 * Targeted counts: one count per store over exactly the named fixtures, NOW (not the daily plan). Purpose: the units on
 * `stolen-epcs.txt` are still in stock on the platform, which learns of a loss only from counts. The server's "missing bar":
 * a unit expected at a fixture and not heard is first `notYetHeard` (reason `first_silence`, stays in stock) and is judged
 * `missing` when silent AGAIN at that fixture in a LATER count (the earlier silence one of the last two counts that judged
 * the fixture, within the rule's window). A silence only counts when the fixture was really worked. So the same fixtures need
 * two targeted counts, one after the other: the first makes the silence, the second earns `missing`.
 *
 * "Really worked" (ASSUMPTION, from the services review notes on the missing-bar PRs #425/#426 and migration 306): the pull
 * time at the fixture must reach the plateau, `>= 4.0` seconds (summed over the fixture's pulls); under it the reason is
 * `short_pull`, neither silence nor reset. The twin states each pull's time as startedAt..endedAt, so [WORKED_SECONDS_FLOOR]
 * is that plateau and every targeted pull is stated at [PULL_SECONDS_MIN] or more (15x the plateau; the daily counts' model of
 * 60 s plus a quarter second a tag). A real edge also sends `aimedSeconds`; the cloud pull the twin sends carries none, so
 * whether the cloud path reads startedAt..endedAt is to be confirmed on the first live run (a `short_pull` reason in the
 * result would say it does not).
 *
 * Target string: `M8TRX_STOCK_TARGET=<slug>:<CODE>[,<CODE>...][;<slug>:...]`.
 */
object StockTargeted {
    const val WORKED_SECONDS_FLOOR = 4.0
    const val PULL_SECONDS_MIN = 60L

    /** Parsed target: slug -> fixture codes (order kept, duplicates dropped). */
    fun parse(spec: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (part in spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val slug = part.substringBefore(':', "").trim()
            val codes = part.substringAfter(':', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require(slug.isNotEmpty() && codes.isNotEmpty()) { "bad M8TRX_STOCK_TARGET part '$part' (want <slug>:<CODE>[,<CODE>...])" }
            require(slug !in out) { "store $slug named twice in M8TRX_STOCK_TARGET" }
            out[slug] = codes
        }
        require(out.isNotEmpty()) { "M8TRX_STOCK_TARGET is empty" }
        return out
    }

    fun format(t: Map<String, List<String>>): String = t.entries.joinToString(";") { (s, c) -> "$s:${c.joinToString(",")}" }

    /** A layout.json's sales-floor zone names to codes (a name carried by two codes is left out: ambiguous). */
    fun namesToCodes(layout: JsonNode): Map<String, String> {
        val by = HashMap<String, MutableSet<String>>()
        layout.path("spaces").filter { it.path("space_type").asText() == "sales_floor" }.forEach { sp ->
            sp.path("zones").forEach { by.getOrPut(it.path("name").asText()) { HashSet() } += it.path("code").asText() }
        }
        return by.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }

    fun loadNamesToCodes(chainDir: Path, slug: String): Map<String, String> =
        namesToCodes(ObjectMapper().readTree(Files.readString(chainDir.resolve("stores/$slug/layout.json"))))

    val SITE_SLUGS = mapOf(
        "Decathlon Bordeaux" to "dec-fr-bordeaux",
        "Decathlon Busan" to "dec-kr-busan",
        "Decathlon Denver" to "dec-us-denver",
        "Decathlon Lyon" to "dec-fr-lyon",
        "Decathlon Marseille" to "dec-fr-marseille",
        "Decathlon New York" to "dec-us-nyc",
        "Decathlon Paris" to "dec-fr-paris",
        "Decathlon San Francisco" to "dec-us-sf",
        "Decathlon Seoul" to "dec-kr-seoul",
        "Decathlon Villeneuve-d'Ascq" to "dec-fr-lille",
    )

    /** Platform site name to slug ("Decathlon " prefix optional). */
    fun slugOf(site: String): String? = SITE_SLUGS[site.trim()] ?: SITE_SLUGS["Decathlon ${site.trim()}"]

    data class UnitRow(val epc: String, val site: String, val state: String, val space: String, val fixture: String)

    fun readUnits(csv: Path): List<UnitRow> {
        val lines = Files.readAllLines(csv).filter { it.isNotBlank() }
        val h = StoreCatalog.splitCsv(lines.first())

        fun col(n: String) = h.indexOf(n).also { require(it >= 0) { "units csv has no '$n' column" } }
        val ie = col("epc")
        val isite = col("site")
        val ist = col("state")
        val isp = col("space")
        val ifx = col("fixture")
        return lines.drop(1).map { StoreCatalog.splitCsv(it) }.map { UnitRow(it[ie].trim(), it[isite], it[ist], it[isp], it[ifx]) }
    }

    data class Targets(val byStore: Map<String, List<String>>, val units: Map<String, Int>, val skipped: List<String>)

    /**
     * The fixtures that hold stolen units the platform still has in stock. A unit not `in_stock` (already missing or sold), not on
     * the sales floor, or at a fixture name no layout code answers is skipped and named in [Targets.skipped].
     */
    fun targets(stolen: Set<String>, units: List<UnitRow>, namesOf: (String) -> Map<String, String>): Targets {
        val stolenUp = stolen.map { it.uppercase() }.toSet()
        val codes = LinkedHashMap<String, MutableSet<String>>()
        val count = LinkedHashMap<String, Int>()
        val skipped = ArrayList<String>()
        val names = HashMap<String, Map<String, String>>()
        for (u in units.filter { it.epc.uppercase() in stolenUp }.sortedBy { it.epc }) {
            val slug = slugOf(u.site)
            val code = slug?.let { names.getOrPut(it) { namesOf(it) }[u.fixture] }
            val why = when {
                slug == null -> "unknown site '${u.site}'"
                u.state != "in_stock" -> "platform state ${u.state}"
                u.space != "Sales Floor" -> "space ${u.space}"
                code == null -> "no code for '${u.fixture}' at $slug"
                else -> null
            }
            if (why != null) {
                skipped += "${u.epc}: $why"
            } else {
                codes.getOrPut(slug!!) { sortedSetOf() } += code!!
                count[slug] = (count[slug] ?: 0) + 1
            }
        }
        val seen = units.map { it.epc.uppercase() }.toSet()
        stolenUp.filter { it !in seen }.sorted().forEach { skipped += "$it: not on the platform units file" }
        return Targets(codes.toSortedMap().mapValues { it.value.toList() }, count.toSortedMap(), skipped)
    }

    /**
     * The one count of [store] over [codes], stated so it ENDS [endMargin] seconds before [now]: each fixture pulled for
     * `max(PULL_SECONDS_MIN, 60 + tags/4)` seconds, 20 s between fixtures. The read set at a fixture = the units [stock] (the
     * platform's in-stock locations, minus the twin's missing list) holds there, minus [stolen] (belt and braces: a stolen unit
     * is silent whatever the lists say). Nothing is injected: no `over`, no planned `missing`, no observations.
     * A fixture with no unit to read is dropped (a pull of nothing works nothing) and returned in the second part.
     */
    fun plan(
        store: ChainKeys.Store,
        now: Instant,
        codes: List<String>,
        stock: StoreStock,
        stolen: Set<String>,
        endMargin: Long = 10,
    ): Pair<StockCounts.CountPlan?, List<String>> {
        val zoneOf = store.zones.associate { it.code to it.zoneId }
        val stolenUp = stolen.map { it.uppercase() }.toSet()
        val bad = codes.filter { it !in zoneOf }
        require(bad.isEmpty()) { "${store.slug}: fixture code(s) not in the chain keys: $bad" }
        val reads = codes.associateWith { c -> stock.byFixture[c].orEmpty().filter { it.uppercase() !in stolenUp } }
        val empty = codes.filter { reads.getValue(it).isEmpty() }
        val use = codes - empty.toSet()
        if (use.isEmpty()) return null to empty
        val runKey = now.epochSecond
        val countId = StockCounts.uuid("twin-stock-target", store.slug, runKey)
        val durations = use.map { maxOf(PULL_SECONDS_MIN, 60L + reads.getValue(it).size / 4) }
        val total = durations.sum() + 20L * (use.size - 1)
        var t = now.minusSeconds(endMargin + total)
        val rng = kotlin.random.Random(runKey xor store.slug.hashCode().toLong())
        val pulls = use.mapIndexed { n, code ->
            val seconds = durations[n]
            val start = t
            val end = start.plusSeconds(seconds)
            t = end.plusSeconds(20)
            val chunks = reads.getValue(code).chunked(StockCounts.FRAME_MAX_TAGS)
            val span = seconds / chunks.size
            val frames = chunks.mapIndexed { seq, chunk ->
                val fs = start.plusSeconds(span * seq)
                val fe = if (seq == chunks.lastIndex) end else fs.plusSeconds(span)
                StockCounts.Frame(seq, fs, fe, chunk.map { StockCounts.tag(it, false, fs, fe, rng) })
            }
            StockCounts.FixturePull(code, zoneOf.getValue(code), StockCounts.uuid("twin-stock-pull", countId, code), frames)
        }
        val date = LocalDate.ofInstant(now, store.zone)
        return StockCounts.CountPlan(countId, store.slug, date, 0, false, pulls, emptyList(), emptyList()) to empty
    }

    /** The key a round is matched on: the store and its fixture codes (sorted), i.e. "the same target string" for that store. */
    fun targetKey(slug: String, codes: List<String>): String = "$slug:${codes.sorted().joinToString(",")}"

    /** The last round for a target: when it closed, and the notYetHeard units it left that are NOT on the stolen list. */
    data class Prior(val closedAt: Instant, val nonStolen: List<String>)

    data class Gate(val refusal: String?, val earliestNow: Instant?)

    /**
     * Round ordering + the non-stolen guard. A round may start only when its first pull ([plan] start, stated relative to [now])
     * is after the previous round's close: `now - total pull span - margin > prior.closedAt`; [Gate.earliestNow] is the first
     * `now` that satisfies it. And a previous round that left notYetHeard units which are not stolen ones blocks the next round
     * until [acceptNonStolen] (`M8TRX_STOCK_TARGET_ACCEPT_NONSTOLEN=true`).
     */
    fun gate(prior: Prior?, planStart: Instant, now: Instant, acceptNonStolen: Boolean): Gate {
        if (prior == null) return Gate(null, null)
        val earliest = prior.closedAt.plusSeconds(java.time.Duration.between(planStart, now).seconds + 1)
        val why = when {
            !planStart.isAfter(prior.closedAt) ->
                "round ordering: the previous round closed ${prior.closedAt}; this round's pulls would start $planStart. Earliest allowed start: $earliest"

            prior.nonStolen.isNotEmpty() && !acceptNonStolen ->
                "the previous round left ${prior.nonStolen.size} notYetHeard unit(s) that are NOT stolen: ${prior.nonStolen}. " +
                    "Set M8TRX_STOCK_TARGET_ACCEPT_NONSTOLEN=true to run the next round anyway"

            else -> null
        }
        return Gate(why, earliest)
    }

    /** The latest closed round of [key] among `targeted-*.json` in [stockDir] (entries carry targetKey, endState, closedAt, notYetHeardUnits). */
    fun loadPrior(stockDir: Path, key: String): Prior? {
        if (!Files.isDirectory(stockDir)) return null
        val m = ObjectMapper()
        val best = Files.list(stockDir).use { s -> s.filter { it.fileName.toString().matches(Regex("targeted-.*\\.json")) }.toList() }
            .flatMap { f -> runCatching { m.readTree(Files.readString(f)).toList() }.getOrDefault(emptyList()) }
            .filter { it.path("targetKey").asText() == key && it.path("endState").asText().startsWith("closed") && it.hasNonNull("closedAt") }
            .maxByOrNull { Instant.parse(it.path("closedAt").asText()) } ?: return null
        val nonStolen = best.path("notYetHeardUnits").filter { !it.path("stolen").asBoolean(true) }.map { it.path("epc").asText() }
        return Prior(Instant.parse(best.path("closedAt").asText()), nonStolen)
    }

    /** Pull seconds per fixture, as the plan states them (the self-test asserts each stays above the plateau). */
    fun pullSeconds(p: StockCounts.CountPlan): Map<String, Long> =
        p.pulls.associate { it.code to (it.endedAt.epochSecond - it.startedAt.epochSecond) }

    data class Entry(val epc: String?, val itemId: String?, val zoneId: String?, val reason: String?)

    /** What a closed count's exceptions answer: the three totals and the notYetHeard entries (item id and/or EPC, zone, reason). */
    data class Result(val missing: Int, val notYetHeard: Int, val misplaced: Int, val entries: List<Entry>, val totals: JsonNode?)

    private fun norm(s: String) = s.lowercase().replace("_", "")

    private fun first(o: JsonNode, vararg keys: String): String? = keys.firstNotNullOfOrNull { o.path(it).asText("").ifBlank { null } }

    private fun entry(o: JsonNode) =
        Entry(first(o, "epc", "identifier"), first(o, "itemId", "item_id", "id"), first(o, "zoneId", "zone_id"), first(o, "reason"))

    /**
     * Reads [ex] (the `GET /count/{id}/exceptions` body). The reconcile lists notYetHeard items under `summary.notYetHeard`
     * (itemId, zoneId, reason); the exceptions body's own shape is not documented to the twin, so every array keyed
     * notYetHeard / not_yet_heard anywhere in the tree is read, and every row whose verdict/type says not_yet_heard too.
     */
    fun readResult(ex: JsonNode): Result {
        val totals = ex.path("totals").takeIf { !it.isMissingNode } ?: ex.path("summary").path("totals").takeIf { !it.isMissingNode }
        val scopes = listOf(totals, ex.path("summary"), ex.path("summary").path("discrepancyCounts"), ex)

        fun n(vararg keys: String): Int = keys.firstNotNullOfOrNull { k ->
            scopes.firstNotNullOfOrNull { s -> s?.path(k)?.takeIf { it.isNumber }?.asInt() }
        } ?: 0

        val entries = ArrayList<Entry>()

        fun walk(node: JsonNode) {
            if (node.isObject) {
                node.fields().forEach { (k, v) ->
                    if (norm(k) == "notyetheard" && v.isArray) v.filter { it.isObject }.forEach { entries += entry(it) } else walk(v)
                }
            } else if (node.isArray) {
                node.forEach { r ->
                    val tag = first(r, "verdict", "type", "kind", "discrepancyType")
                    if (r.isObject && tag != null && norm(tag) == "notyetheard") entries += entry(r) else walk(r)
                }
            }
        }
        walk(ex)
        val nyh = n("notYetHeard", "not_yet_heard").takeIf { it > 0 } ?: entries.size
        return Result(n("missing", "under"), nyh, n("misplaced"), entries.distinctBy { it.itemId ?: it.epc }, totals)
    }
}
