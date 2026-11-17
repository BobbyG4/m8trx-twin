package com.m8trx.twin.storeday

/** One verified (or failed) claim about a planted pattern, with the numbers that decided it. */
class PlantCheck(val scenario: String, val name: String, val ok: Boolean, val measured: String)

/**
 * Slice 2d: confirms each planted pattern really shows up in the twin's truth ledger at the stated strength. The thresholds are the
 * eval's own words (`twin-scenarios.md`): a fast seller is 3x any other product of its department, the under-seen fixture holds the
 * largest on-hand value and sits in the bottom decile of impressions, quiet stock sold zero for the whole window, the looked-at fixtures
 * are in the top three for impressions with near-zero sales and the control is normal.
 */
object PlantVerify {
    private fun f1(x: Double) = "%.1f".format(x)

    private fun f2(x: Double) = "%.2f".format(x)

    fun verify(l: PlantLedger, p: StorePlant, plan: PeriodPlan): List<PlantCheck> = p.scenarios.flatMap { sc ->
        val out = ArrayList<PlantCheck>()
        fun add(name: String, ok: Boolean, measured: String) {
            out += PlantCheck(sc.id, name, ok, measured)
        }
        when (sc.archetype) {
            "promote" -> promote(l, sc, plan, ::add)
            "layout" -> layout(l, sc, ::add)
            "move_fast_seller" -> moveFast(l, sc, plan, ::add)
            "under_seen" -> underSeen(l, sc, ::add)
            "restock_first" -> restock(l, sc, ::add)
            "quiet_stock" -> quietStock(l, sc, plan, ::add)
            "look_not_buy" -> lookNotBuy(l, sc, ::add)
            else -> error("no verification for archetype ${sc.archetype}")
        }
        add(
            "no planted slot ever held more than its planned (full) quantity at a close (a same-day misplaced put-back excepted)",
            l.overCapacity.isEmpty(),
            "${l.overCapacity.size} over-capacity closes (e.g. ${l.overCapacity.take(
                6,
            )}); ${l.strayUnits} off-plan unit-closes on planted fixtures (put-backs)",
        )
        add("the plant window holds for at least 28 days", l.days >= 28, "${l.days} days from ${l.dates.firstOrNull()} to ${l.dates.lastOrNull()}")
        out
    }

    private fun fastCut(l: PlantLedger) = l.percentile(l.floorEans.map { l.velocity(it) }, 0.95)

    private fun medianImps(l: PlantLedger) = l.median(l.merch.map { l.impressions(it).toDouble() })

    private fun topImps(l: PlantLedger, p: Double) = l.percentile(l.merch.map { l.impressions(it).toDouble() }, p)

    private fun promote(l: PlantLedger, sc: PlantScenario, plan: PeriodPlan, add: (String, Boolean, String) -> Unit) {
        val fq = sc.one("fast_quiet")
        val fb = sc.one("fast_busy")
        val lc = sc.one("low_cover")
        val lookers = sc.role("lookers")
        val cut = fastCut(l)
        add(
            "the two promotion candidates sell fast (each at or above the store's 95th percentile)",
            l.velocity(fq) >= cut && l.velocity(fb) >= cut,
            "fast_quiet ${f2(l.velocity(fq))}/day, fast_busy ${f2(l.velocity(fb))}/day, p95 ${f2(cut)}",
        )
        val ratio = l.velocity(fq) / maxOf(1e-9, l.velocity(fb))
        add("the two have the same sell-through (ratio 0.7 to 1.4)", ratio in 0.7..1.4, "fast_quiet / fast_busy = ${f2(ratio)}")
        val aq = l.impressions(l.homeFixture(fq, plan))
        val ab = l.impressions(l.homeFixture(fb, plan))
        add(
            "one sits in a quiet fixture (below the median impressions), the other in a busy one (top decile)",
            aq <= medianImps(l) && ab >= topImps(l, 0.9),
            "${l.homeFixture(fq, plan)} $aq impressions (median ${f1(medianImps(l))}), ${l.homeFixture(fb, plan)} $ab (p90 ${f1(topImps(l, 0.9))})",
        )
        val pickCut = l.percentile(l.floorEans.map { (l.eans[it]?.pickups ?: 0).toDouble() }, 0.9)
        lookers.forEach { e ->
            val pk = l.eans[e]?.pickups ?: 0
            val u = l.eans[e]?.units ?: 0
            add(
                "looked-at product $e: pickups in the top decile, near-zero sales (5 units or fewer, under 3% of pickups)",
                pk >= pickCut && u <= 5 && u <= 0.03 * pk,
                "$pk pickups (p90 ${f1(pickCut)}), $u units",
            )
        }
        add(
            "the low-cover product sells fast and has under 5 days of stock",
            l.velocity(lc) >= l.percentile(l.floorEans.map { l.velocity(it) }, 0.9) && l.coverDays(lc) < 5.0,
            "${f2(l.velocity(lc))}/day, on hand ${f1(l.onHandUnits(lc))}, cover ${f1(l.coverDays(lc))} days",
        )
    }

    private fun layout(l: PlantLedger, sc: PlantScenario, add: (String, Boolean, String) -> Unit) {
        val strong = sc.one("strong_dept")
        val busy = sc.one("busy_dept")
        val regions = l.departmentRegion.mapValues { l.regionImpressions(it.value) }
        val rs = regions.getValue(strong)
        val rb = regions.getValue(busy)
        add(
            "the strong department's region is the least visited of the department regions",
            regions.all { it.key == strong || it.value > rs },
            "$strong ${l.departmentRegion[strong]} $rs impressions; others ${regions.filterKeys {
                it != strong
            }.entries.joinToString { "${it.key} ${it.value}" }}",
        )
        add(
            "the slow department's region is the most visited",
            regions.all { it.key == busy || it.value < rb },
            "$busy ${l.departmentRegion[busy]} $rb impressions; others ${regions.filterKeys {
                it != busy
            }.entries.joinToString { "${it.key} ${it.value}" }}",
        )
        val all = l.floorEans.map { l.velocity(it) }
        val mean = all.average()

        fun deptMean(d: String) = l.floorEans.filter { l.sim.model.demand[it]?.department == d }.map { l.velocity(it) }.average()
        add(
            "the strong department's products sell at least 3x the store's mean product",
            deptMean(strong) >= 3 * mean,
            "$strong ${f2(deptMean(strong))}/day per product vs store mean ${f2(mean)}",
        )
        add(
            "the slow department's products sell at most half the store's mean product",
            deptMean(busy) <= 0.5 * mean,
            "$busy ${f2(deptMean(busy))}/day per product vs store mean ${f2(mean)}",
        )
    }

    private fun moveFast(l: PlantLedger, sc: PlantScenario, plan: PeriodPlan, add: (String, Boolean, String) -> Unit) {
        val prod = sc.one("product")
        val target = sc.one("target_fixture")
        val slow = sc.one("displaced")
        val dept = l.sim.model.demand.getValue(prod).department
        val others = l.floorEans.filter { it != prod && l.sim.model.demand[it]?.department == dept }
        val best = others.maxByOrNull { l.velocity(it) }
        val v = l.velocity(prod)
        add(
            "the product sells at least 3x any other product of its department",
            best == null || v >= 3 * l.velocity(best),
            "$prod ${f2(v)}/day; best other in $dept: $best ${f2(best?.let { l.velocity(it) } ?: 0.0)}/day",
        )
        add("the product sells fast (at or above the store's 95th percentile)", v >= fastCut(l), "${f2(v)}/day, p95 ${f2(fastCut(l))}")
        val a = l.homeFixture(prod, plan)
        add(
            "its fixture is low-visit (below the median impressions)",
            l.impressions(a) <= medianImps(l),
            "$a ${l.impressions(a)} impressions, median ${f1(medianImps(l))}",
        )
        val dwellCut = l.percentile(l.merchWithVisits.map { l.dwellPerVisit(it) }, 0.90)
        add(
            "its fixture is high-dwell: dwell per visit in the top decile of the merchandise fixtures",
            l.impressions(a) >= PlantLedger.MIN_VISITS_FOR_DWELL && l.dwellPerVisit(a) >= dwellCut,
            "$a ${f1(l.dwellPerVisit(a))} s per visit over ${l.impressions(a)} visits, p90 ${f1(dwellCut)} s",
        )
        add(
            "the target fixture is high-visit (top decile) and holds the slow seller there",
            l.impressions(target) >= topImps(l, 0.9) &&
                l.homeFixture(slow, plan) == target && l.velocity(slow) <= l.percentile(l.floorEans.map { l.velocity(it) }, 0.75),
            "$target ${l.impressions(target)} impressions (p90 ${f1(topImps(l, 0.9))}); $slow ${f2(l.velocity(slow))}/day",
        )
    }

    private fun underSeen(l: PlantLedger, sc: PlantScenario, add: (String, Boolean, String) -> Unit) {
        val f = sc.one("fixture")
        val top = l.merch.maxBy { l.onHandValue(it) }
        add(
            "the fixture holds the largest on-hand value of the floor",
            top == f,
            "$f ${"%.0f".format(l.onHandValue(f))} USD; largest is $top ${"%.0f".format(l.onHandValue(top))}",
        )
        add(
            "and is in the bottom decile of impressions",
            l.impressions(f) <= topImps(l, 0.10),
            "${l.impressions(f)} impressions, p10 ${f1(topImps(l, 0.10))}",
        )
        val dwellCut = l.percentile(l.merch.map { (l.fixtures[it]?.dwellMs ?: 0L).toDouble() }, 0.10)
        add(
            "and of dwell",
            (l.fixtures[f]?.dwellMs ?: 0L) <= dwellCut,
            "${(l.fixtures[f]?.dwellMs ?: 0L) / 1000} s, p10 ${(dwellCut / 1000).toInt()} s",
        )
    }

    private fun restock(l: PlantLedger, sc: PlantScenario, add: (String, Boolean, String) -> Unit) {
        val a = sc.one("a")
        val b = sc.one("b")
        val c = sc.one("c")
        val p90 = l.percentile(l.floorEans.map { l.velocity(it) }, 0.9)
        add(
            "A and B sell fast (at or above the 90th percentile)",
            l.velocity(a) >= p90 && l.velocity(b) >= p90,
            "A ${f2(l.velocity(a))}/day, B ${f2(l.velocity(b))}/day, p90 ${f2(p90)}",
        )
        add(
            "A has stock for under 2 days",
            l.coverDays(a) < 2.0,
            "on hand ${f1(l.onHandUnits(a))}, demand ${f2(l.demandPerDay(a))}/day, cover ${f1(l.coverDays(a))} days",
        )
        add("B has a healthy stock (5 days or more)", l.coverDays(b) >= 5.0, "on hand ${f1(l.onHandUnits(b))}, cover ${f1(l.coverDays(b))} days")
        add(
            "C sells slowly (at or below the median) and is nearly out (3 units or fewer on hand)",
            l.velocity(c) <= l.median(l.floorEans.map { l.velocity(it) }) && l.onHandUnits(c) <= 3.0,
            "${f2(l.velocity(c))}/day, on hand ${f1(l.onHandUnits(c))}",
        )
    }

    private fun quietStock(l: PlantLedger, sc: PlantScenario, plan: PeriodPlan, add: (String, Boolean, String) -> Unit) {
        val prods = sc.role("products")
        fun planned(e: String) = plan.lines.filter { it.ean == e }.sumOf { it.required }
        add(
            "the ${prods.size} products sold zero for the whole window",
            prods.all {
                (l.eans[it]?.units ?: 0) == 0
            },
            prods.joinToString { "$it ${l.eans[it]?.units ?: 0}" },
        )
        add(
            "each holds high on-hand: at or near full planned capacity (90% or more of its lines' required quantity on the floor)",
            prods.all { l.floorUnits(it) >= 0.9 * planned(it) },
            prods.joinToString { "$it ${f1(l.floorUnits(it))} of ${planned(it)}" },
        )
        val med = medianImps(l)
        add(
            "each sits on a floor fixture with below-median impressions",
            prods.all { l.impressions(l.homeFixture(it, plan)) <= med },
            prods.joinToString { "${l.homeFixture(it, plan)} ${l.impressions(l.homeFixture(it, plan))}" } + "; median ${f1(med)}",
        )
        val sells = prods.all { e ->
            val cat = l.sim.model.demand[e]?.category
            l.floorEans.filter { it !in prods && l.sim.model.demand[it]?.category == cat }.sumOf { l.eans[it]?.units ?: 0 } >= 50
        }
        add(
            "the products' categories sell elsewhere (50 units or more from other products)",
            sells,
            prods.joinToString { e ->
                val cat = l.sim.model.demand[e]?.category
                "$cat ${l.floorEans.filter { it !in prods && l.sim.model.demand[it]?.category == cat }.sumOf { l.eans[it]?.units ?: 0 }}"
            },
        )
    }

    private fun lookNotBuy(l: PlantLedger, sc: PlantScenario, add: (String, Boolean, String) -> Unit) {
        val price = sc.one("price_fixture")
        val out = sc.one("stockout_fixture")
        val ctl = sc.one("control_fixture")
        val top = l.merch.sortedByDescending { l.impressions(it) }.take(3).toSet()
        add(
            "the three fixtures are the top three for impressions",
            top == setOf(price, out, ctl),
            "top three: ${l.merch.sortedByDescending { l.impressions(it) }.take(5).joinToString { "$it ${l.impressions(it)}" }}",
        )

        fun conv(f: String) = (l.fixtures[f]?.units ?: 0).toDouble() / maxOf(1, l.impressions(f))
        add(
            "the price fixture and the stock-out fixture convert at under 15% of the control (near-zero; gift shoppers still buy a little anywhere)",
            conv(price) <= 0.15 * conv(ctl) && conv(out) <= 0.15 * conv(ctl),
            "units per impression: price ${"%.4f".format(
                conv(price),
            )} (${l.fixtures[price]?.units} units), stock-out ${"%.4f".format(
                conv(out),
            )} (${l.fixtures[out]?.units} units), control ${"%.4f".format(conv(ctl))} (${l.fixtures[ctl]?.units} units)",
        )
        val med = l.median(l.merch.map { conv(it) })
        add(
            "the control converts normally (half to double the median fixture)",
            conv(ctl) in 0.5 * med..2.0 * med,
            "control ${"%.4f".format(conv(ctl))}, median ${"%.4f".format(med)}",
        )
        val st = l.fixtures[out]
        add(
            "the stock-out fixture is empty (${PlantLedger.EMPTY_QTY} units or fewer) at the close of 90% of days and shoppers still reach for it",
            (st?.emptyDays ?: 0) >= 0.9 * l.openDays && (st?.stockOuts ?: 0) > 0,
            "empty ${st?.emptyDays}/${l.openDays} days, ${st?.stockOuts} stock-out events, ${st?.lost} lost sales",
        )

        fun meanPrice(f: String) = l.sim.model.lines.filter { it.fixture == f }.map { l.sim.model.demand[it.ean]?.priceUsd ?: 0.0 }.average()
        val prices = l.merch.map { meanPrice(it) }
        add(
            "the price fixture's mean unit price is at least 1.5x the median fixture",
            meanPrice(price) >= 1.5 * l.median(prices),
            "${f2(meanPrice(price))} vs median ${f2(l.median(prices))} USD",
        )
        fun pk(f: String) = (l.fixtures[f]?.pickups ?: 0).toDouble() / maxOf(1, l.impressions(f))
        add(
            "people handle the price fixture's goods (pickups per impression at least half the control's)",
            pk(price) >= 0.5 * pk(ctl),
            "price ${"%.3f".format(pk(price))}, control ${"%.3f".format(pk(ctl))}",
        )
    }

    fun markdown(slug: String, from: java.time.LocalDate, to: java.time.LocalDate, checks: List<PlantCheck>): String = buildString {
        appendLine("### $slug, $from to $to")
        appendLine()
        appendLine("| scenario | check | result | measured |")
        appendLine("|---|---|---|---|")
        checks.forEach { appendLine("| ${it.scenario} | ${it.name} | ${if (it.ok) "pass" else "FAIL"} | ${it.measured.replace("|", "/")} |") }
        appendLine()
    }
}
