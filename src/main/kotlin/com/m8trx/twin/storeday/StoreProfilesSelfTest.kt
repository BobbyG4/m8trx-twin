package com.m8trx.twin.storeday

import com.fasterxml.jackson.databind.node.ObjectNode
import com.m8trx.twin.behavior.BehaviorModel
import com.m8trx.twin.behavior.BehaviorParams
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.system.exitProcess

/**
 * `./gradlew storeProfilesSelfTest` - the D3 store profiles (hours, location factor, delivery lead) as the store-day honours them.
 * Offline; sends nothing. Estimates, not measurements: the numbers are the profile's, the checks are that the code obeys them.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeday.StoreProfilesSelfTest")
private var nPass = 0
private var nFail = 0

private fun ok(name: String, cond: Boolean, detail: String) {
    if (cond) nPass++ else nFail++
    log.info("  {} {} - {}", if (cond) "✓" else "✗", name, detail)
}

private val FROM: LocalDate = LocalDate.of(2026, 11, 2) // a Monday
private const val DAYS = 28

/** Weekly open hours Bob's table implies (Mon-Sat + Sun), by store. */
private val WEEKLY_HOURS = mapOf(
    "dec-us-nyc" to 6 * 11.0 + 9.0,
    "dec-us-denver" to 6 * 11.0 + 7.0,
    "dec-us-sf" to 6 * 10.0 + 8.0,
    "dec-fr-paris" to 6 * 11.0 + 9.0,
    "dec-fr-lyon" to 6 * 10.5,
    "dec-fr-lille" to 6 * 10.5,
    "dec-fr-bordeaux" to 6 * 10.5,
    "dec-fr-marseille" to 6 * 10.5,
    "dec-kr-seoul" to 7 * 11.5,
    "dec-kr-busan" to 7 * 11.0,
)
private val LOCATION = mapOf(
    "dec-us-nyc" to 1.8, "dec-fr-paris" to 1.6, "dec-kr-seoul" to 1.5, "dec-us-sf" to 1.1, "dec-us-denver" to 1.0, "dec-fr-lyon" to 1.1,
    "dec-fr-lille" to 1.0, "dec-kr-busan" to 1.1, "dec-fr-bordeaux" to 0.9, "dec-fr-marseille" to 0.85,
)
private val SUNDAY_CLOSED = setOf("dec-fr-lyon", "dec-fr-lille", "dec-fr-bordeaux", "dec-fr-marseille")
private const val SOURCE = "twin estimate, Bob 2026-11-17: best guess from normal retail practice per city and store size"

private fun hours(m: StoreDayModel): Double = DayOfWeek.values().sumOf { d ->
    m.hoursOn(d)?.let { (o, c) -> (c.toSecondOfDay() - o.toSecondOfDay()) / 3600.0 } ?: 0.0
}

/** One store's 28 days (default knobs: calendar on, periods on). */
class ProfileRun(val sim: StoreDaySim, val results: List<DayResult>, val tally: RunTally)

fun profileRun(site: String): ProfileRun {
    val sim = buildSim(site, loadCalibration(site), Knobs())
    val seed = sim.behavior.params.seed
    val state = StoreState.initial(sim.model)
    val tally = RunTally(sim)
    val rs = (0 until DAYS).map { i -> sim.simulate(state, FROM.plusDays(i.toLong()), seed).also { tally.add(it) } }
    return ProfileRun(sim, rs, tally)
}

private fun unitsPerWeek(r: ProfileRun) = r.tally.units * 7.0 / DAYS

fun main() {
    // ── 1. the profile is what Bob's table says, and the model reads it ───────────────────────────────────
    log.info("[1] profiles")
    val denverArea = StoreDayModel("dec-us-denver").areaSqm
    for (site in StoreState.STORE_SLUGS) {
        val m = StoreDayModel(site)
        ok(
            "$site: weekly open hours equal the profile",
            kotlin.math.abs(hours(m) - WEEKLY_HOURS.getValue(site)) < 1e-9,
            "%.1f h (profile) = %.1f h (table)".format(hours(m), WEEKLY_HOURS.getValue(site)),
        )
        ok("$site: location factor", m.locationFactor == LOCATION.getValue(site), "${m.locationFactor}")
        ok(
            "$site: delivery lead 60 min from 450 m2, else 30",
            m.deliveryLeadMin == (if (m.areaSqm >= 450.0) 60 else 30),
            "${m.areaSqm} m2 -> ${m.deliveryLeadMin} min",
        )
        ok(
            "$site: not provisional, source stated",
            !m.profile.path("provisional").asBoolean(true) && m.profile.path("source").asText() == SOURCE,
            "ok",
        )
        ok(
            "$site: Sunday closed exactly where the table says, with the Saturday lift",
            (m.hoursOn(DayOfWeek.SUNDAY) == null) == (site in SUNDAY_CLOSED) &&
                m.saturdayLift == (if (site in SUNDAY_CLOSED) 1.10 else 1.0),
            "sun=${m.hoursOn(DayOfWeek.SUNDAY)} lift=${m.saturdayLift}",
        )
        ok(
            "$site: visit scale = location x area / Denver",
            kotlin.math.abs(m.visitScale - m.locationFactor * m.areaSqm / denverArea) < 1e-12,
            "%.3f".format(m.visitScale),
        )
    }
    ok(
        "the chain-default knob is in the profile file",
        StoreDayModel("dec-fr-lyon").profile.has("saturday_lift"),
        "closed_sunday_saturday_lift = 1.10",
    )

    // ── 2. the curve keeps its shape and total whatever the window ─────────────────────────────────────────
    log.info("[2] hourly curve over the window")
    val behavior = BehaviorModel(BehaviorParams.forSite("dec-us-denver"))
    for (weekend in listOf(false, true)) {
        val c = behavior.model.traffic.hourlyCurve.let { if (weekend) it.weekend else it.weekday }
        val peak = c.indices.maxByOrNull { c[it] }!! + 0.5
        for (len in listOf(7.0, 8.0, 9.0, 10.0, 10.5, 11.0, 11.5)) {
            val s = stretchCurve(c, len)
            val sp = (s.indices.maxByOrNull { s[it] }!! + 0.5) / len
            ok(
                "${if (weekend) "weekend" else "weekday"} curve over $len h: ${kotlin.math.ceil(
                    len,
                ).toInt()} buckets, total 1, peak at the same fraction of the day",
                s.size == kotlin.math.ceil(len).toInt() && kotlin.math.abs(s.sum() - 1.0) < 1e-9 && kotlin.math.abs(sp - peak / c.size) < 0.12,
                "peak at %.2f of the day (model %.2f)".format(sp, peak / c.size),
            )
        }
        ok("an 11-hour window is the model's own curve", stretchCurve(c, 11.0) == c, "identical")
    }

    // ── 3. visitors by day type x volume factors only; the window does not change them ────────────────────────
    log.info("[3] daily visitors do not depend on the window")
    val base = buildSim("dec-us-denver", loadCalibration("dec-us-denver"), Knobs())
    val tweaked =
        StoreDayModel("dec-us-denver") { p ->
            (p.path("hours") as ObjectNode).also { h -> DayOfWeek.values().forEach { h.put(it.name.take(3).lowercase(), "08:00-22:30") } }
        }
    val tweakedOverlay = DayOverlay(tweaked, base.behavior)
    var same = 0
    for (i in 0 until 14) {
        val d = FROM.plusDays(i.toLong())
        val a = base.overlay.plan(d)
        val b = tweakedOverlay.plan(d)
        if (!a.closed && !b.closed && kotlin.math.abs(a.shape!!.expectedVisitors - b.shape!!.expectedVisitors) < 1e-9 &&
            a.shape.lengthHours != b.shape.lengthHours
        ) {
            same++
        }
    }
    ok("Denver visitors identical with a 7-11 h or a 14.5 h window", same >= 12, "$same of 14 open days equal despite different windows")
    val noLift = StoreDayModel("dec-fr-lyon") { p -> p.put("saturday_lift", 1.0) }
    val lyon = buildSim("dec-fr-lyon", loadCalibration("dec-fr-lyon"), Knobs())
    val sat = FROM.plusDays(5)
    val withLift = lyon.overlay.plan(sat).shape!!.expectedVisitors
    val without = DayOverlay(noLift, lyon.behavior).plan(sat).shape!!.expectedVisitors
    ok("Sunday-closed Saturday carries x1.10", kotlin.math.abs(withLift / without - 1.10) < 1e-9, "%.1f vs %.1f".format(withLift, without))

    // ── 4. 28-day runs, all ten stores ──────────────────────────────────────────────────────────────────────
    log.info("[4] 28-day runs")
    val runs = StoreState.STORE_SLUGS.associateWith { profileRun(it) }
    for ((site, r) in runs) {
        val m = r.sim.model
        val zone = ZoneId.of(m.timezone)
        var deliveries = 0
        var deliveryOk = true
        var sundaysClosed = true
        var sundayUnits = 0
        var closedClean = true
        for (d in r.results) {
            val hrs = m.hoursOn(d.date.dayOfWeek)
            if (d.plan.closed) {
                closedClean = closedClean && d.records.isEmpty() && d.saleUnits.isEmpty() && d.wire.isEmpty() && d.receivedEpcs.isEmpty()
                if (hrs == null && d.date.dayOfWeek == DayOfWeek.SUNDAY) sundaysClosed = sundaysClosed && closedClean
                continue
            }
            if (d.date.dayOfWeek == DayOfWeek.SUNDAY) sundayUnits += d.saleUnits.size
            val open = ZonedDateTime.of(d.date, hrs!!.first, zone).toInstant().toEpochMilli()
            val receives = d.wire.filter { it.kind == "receive" }
            if (receives.isNotEmpty()) {
                deliveries++
                val first = receives.minOf { it.dueMs }
                val good = first == open - m.deliveryLeadMin * 60_000L && d.plan.shape!!.open == hrs.first
                if (!good) {
                    log.info(
                        "    {} {} first receive {} min before open (lead {})",
                        site,
                        d.date,
                        (open - first) / 60_000.0,
                        m.deliveryLeadMin,
                    )
                }
                deliveryOk = deliveryOk && good
            }
            // sales fall inside the window
            val close = open + (d.plan.shape!!.lengthHours * 3_600_000).toLong()
            deliveryOk = deliveryOk && d.saleUnits.all { it.exitMs >= open }
            val lateExit = d.saleUnits.count { it.exitMs > close + 3 * 3_600_000L }
            if (lateExit > 0 || d.saleUnits.any { it.exitMs < open }) log.info("    {} {} sales outside window: late {}", site, d.date, lateExit)
            deliveryOk = deliveryOk && lateExit == 0
        }
        val sundays = r.results.filter { it.date.dayOfWeek == DayOfWeek.SUNDAY }
        ok(
            "$site: ${if (site in SUNDAY_CLOSED) "Sundays closed: no visitors, sales, delivery or wire events" else "Sundays open"}",
            if (site in
                SUNDAY_CLOSED
            ) {
                sundays.all {
                    it.plan.closed && it.records.isEmpty() && it.saleUnits.isEmpty() && it.wire.isEmpty() &&
                        it.receivedEpcs.isEmpty()
                }
            } else {
                sundays.all { !it.plan.closed || it.plan.holiday != null }
            },
            "${sundays.size} Sundays, $sundayUnits units sold on open ones",
        )
        ok("$site: closed days are empty (visitors, sales, delivery, wire)", closedClean, "${r.tally.closedDays} closed days")
        ok(
            "$site: every delivery lands at open minus ${m.deliveryLeadMin} min of that day's own opening; no sale before opening or more than 3 h after closing (a late entrant's long visit)",
            deliveryOk && deliveries > 0,
            "$deliveries delivery days",
        )
    }

    // ── 5. volume ordering over the 28 days ──────────────────────────────────────────────────────────────────
    log.info("[5] volume ordering")
    val u = runs.mapValues { unitsPerWeek(it.value) }
    fun v(s: String) = u.getValue("dec-$s")
    fun gt(a: String, b: String) = ok("volume: $a > $b", v(a) > v(b), "%.0f > %.0f units/week".format(v(a), v(b)))
    fun near(a: String, b: String) = ok("volume: $a ~ $b", kotlin.math.abs(v(a) / v(b) - 1.0) < 0.15, "%.0f ~ %.0f units/week".format(v(a), v(b)))
    gt("us-nyc", "fr-paris")
    gt("fr-paris", "kr-seoul")
    near("kr-seoul", "us-sf")
    gt("us-sf", "us-denver")
    gt("us-denver", "fr-lyon")
    near("fr-lyon", "fr-lille")
    gt("fr-lille", "kr-busan")
    near("kr-busan", "fr-bordeaux")
    gt("fr-bordeaux", "fr-marseille")
    ok("the busiest store sells several times the quietest", v("us-nyc") > 3.0 * v("fr-marseille"), "%.1fx".format(v("us-nyc") / v("fr-marseille")))

    log.info("storeProfilesSelfTest: {} passed, {} failed", nPass, nFail)
    if (nFail > 0) exitProcess(1)
}
