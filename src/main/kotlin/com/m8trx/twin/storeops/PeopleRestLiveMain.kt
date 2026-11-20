package com.m8trx.twin.storeops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.peoplelive.PeopleRestHttp
import com.m8trx.twin.storeday.PeopleRest
import com.m8trx.twin.storeday.buildSim
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("com.m8trx.twin.storeops.PeopleRestLive")

/**
 * `./gradlew storeDayPeopleRestLive` - the live sender of people-by-REST for the nine non-Denver stores. DRY (nothing sent, no client built,
 * no file written) unless `M8TRX_PEOPLE_REST_LIVE=true` AND `M8TRX_PEOPLE_REST_CONFIRM=<the slugs of the run, comma list>` AND
 * `M8TRX_TWIN_PEOPLE_BEARER` are set. Backfill is refused, a date before the store-local today is refused (that is backfill).
 *
 * Env: `_STORES` (comma subset of the nine; default all nine; Denver refused) · `_DATE` (store-local day; default store-local today) ·
 * `_FROM` / `_UNTIL` (store-local HH:mm, default 00:00 / 24:00; a session is in when its BEGIN is in [FROM, UNTIL); its impressions and
 * close go out even after UNTIL) · `_CAP_PER_MIN` (300, max 600) · `_SEED` · `_OUT` (out/people-rest; the run is in `<out>/live/<date>/`) ·
 * `_HOLD_FILE` (default `$M8TRX_OPS_DIR/people-HOLD`, never `$OPS/HOLD`) · `_BASE_URL` (https://dev.m8trx.com/server) ·
 * `M8TRX_STORE_DAY_OUT` (sidecars). Exit: 0 done, 1 halted, 3 refused, 6 HOLD.
 */
object PeopleRestLiveMain {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    fun run(): Int {
        val live = env("M8TRX_PEOPLE_REST_LIVE")?.toBoolean() == true
        val backfill = env("M8TRX_PEOPLE_REST_BACKFILL")?.toBoolean() == true
        val stores = env("M8TRX_PEOPLE_REST_STORES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: PeopleRest.STORES
        val cap = env("M8TRX_PEOPLE_REST_CAP_PER_MIN")?.toInt() ?: PeoplePace.DEFAULT_CAP_PER_MIN
        val fromMin = PeopleWindow.parseMinutes(env("M8TRX_PEOPLE_REST_FROM") ?: "00:00")
        val untilMin = PeopleWindow.parseMinutes(env("M8TRX_PEOPLE_REST_UNTIL") ?: "24:00")
        val out = Path.of(env("M8TRX_PEOPLE_REST_OUT") ?: "out/people-rest")
        val bearer = env(PeopleLiveGate.BEARER_ENV)
        val chainDir = Path.of("reference/data/chain")

        val early = PeopleLiveGate.refusals(live, backfill, stores, env("M8TRX_PEOPLE_REST_CONFIRM"), bearer != null)
        if (early.isNotEmpty()) return refuse(early)
        PeoplePace.gapMs(cap) // validates 1..600

        val keys = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName))
        val siteIds = DatedStream.readSiteIds(chainDir.resolve("site_ids.csv"))
        val sims = stores.associateWith { buildSim(it) }
        val zones = stores.associateWith { ZoneId.of(sims.getValue(it).model.timezone) }
        val dates = stores.associateWith { s -> env("M8TRX_PEOPLE_REST_DATE")?.let(LocalDate::parse) ?: LocalDate.now(zones.getValue(s)) }
        val backdated = stores.filter { dates.getValue(it) < LocalDate.now(zones.getValue(it)) }
        val dirDate = dates.values.min()
        val dir = out.resolve("live").resolve(dirDate.toString())
        val planFile = dir.resolve("plan.jsonl")
        val metaFile = dir.resolve("plan-meta.json")
        val seed = env("M8TRX_PEOPLE_REST_SEED")?.toLong()
        val meta = ConnectMappers.snake.createObjectNode().also {
            it.put("stores", stores.sorted().joinToString(","))
            it.put("dates", stores.sorted().joinToString(",") { s -> "$s=${dates.getValue(s)}" })
            it.put("from", fromMin).put("until", untilMin).put("seed", seed?.toString() ?: "model")
        }
        val frozen = Files.exists(planFile) && Files.exists(metaFile)
        val gate = PeopleLiveGate.refusals(live, backfill, stores, env("M8TRX_PEOPLE_REST_CONFIRM"), bearer != null, backdated, frozen)
        if (gate.isNotEmpty()) return refuse(gate)

        val items: List<SendItem>
        if (frozen) {
            if (ConnectMappers.snake.readTree(Files.readString(metaFile)) != meta) {
                return refuse(
                    listOf("the frozen plan in $dir was made with other stores/date/window/seed; use another M8TRX_PEOPLE_REST_OUT or remove it"),
                )
            }
            items = PeopleLivePlan.read(planFile)
            log.info("FROZEN plan reused: {} ({} lines)", planFile, items.size)
        } else {
            val perStore = stores.map { slug ->
                val sim = sims.getValue(slug)
                val date = dates.getValue(slug)
                val r = sim.simulate(sim.initialState(), date, seed ?: sim.behavior.params.seed)
                val day = PeopleRest.plan(slug, date, r, PeopleRest.idsFor(slug, keys, siteIds))
                slug to PeopleWindow.select(day, zones.getValue(slug), date, fromMin, untilMin)
            }
            items = PeopleLivePlan.merge(perStore)
            if (live) {
                PeopleLivePlan.write(planFile, items)
                Files.writeString(metaFile, meta.toPrettyString())
            }
        }
        val ledger = PeopleLedger(dir.resolve("sent-ledger.tsv").takeIf { live })
        val ledgerRead = PeopleLedger(dir.resolve("sent-ledger.tsv"))
        printExpected(items, ledgerRead, System.currentTimeMillis(), stores, dates, fromMin, untilMin, cap, live)
        if (!live) {
            println("DRY: M8TRX_PEOPLE_REST_LIVE is not true; nothing was sent, no client was built, no file was written.")
            return 0
        }
        val ops = env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"
        val hold = Path.of(env("M8TRX_PEOPLE_REST_HOLD_FILE") ?: "$ops/people-HOLD")
        if (Files.exists(hold)) {
            log.error("REFUSING: HOLD is set ({}): nothing is sent until it is removed", hold)
            return 6
        }
        Files.createDirectories(dir)
        FileChannel.open(dir.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            val lock = ch.tryLock() ?: return refuse(listOf("another people-rest run holds ${dir.resolve("lock")}"))
            lock.use {
                val transport = PeopleRestHttp.create(true, env("M8TRX_PEOPLE_REST_BASE_URL") ?: PeopleRestHttp.DEFAULT_BASE, bearer!!)
                val runner = PeopleLiveRunner(
                    items,
                    transport,
                    ledger,
                    PeopleLiveParams(cap),
                    System::currentTimeMillis,
                    { Thread.sleep(it) },
                    { Files.exists(hold) },
                    dir,
                )
                val end = runner.run()
                println("people-rest live: $end ${runner.haltReason.orEmpty()} counts=${runner.counters.toJson()}")
                return when (end) {
                    PeopleLiveRunner.End.DONE -> 0
                    PeopleLiveRunner.End.HOLD -> 6
                    PeopleLiveRunner.End.HALTED -> 1
                }
            }
        }
    }

    private fun refuse(reasons: List<String>): Int {
        reasons.forEach { log.error("REFUSING: {}", it) }
        return 3
    }

    @Suppress("LongParameterList")
    fun printExpected(
        items: List<SendItem>,
        ledger: PeopleLedger,
        nowMs: Long,
        stores: List<String>,
        dates: Map<String, LocalDate>,
        fromMin: Int,
        untilMin: Int,
        cap: Int,
        live: Boolean,
    ) {
        println(
            "people-rest ${if (live) "LIVE" else "DRY"}: window %02d:%02d-%02d:%02d store-local, cap %d/min".format(
                fromMin / 60,
                fromMin % 60,
                untilMin / 60,
                untilMin % 60,
                cap,
            ),
        )
        println("%-16s %-10s %7s %12s %7s %8s".format("store", "date", "begins", "impressions", "closes", "total"))
        val c = PeopleLivePlan.counts(items)
        var t = intArrayOf(0, 0, 0)
        stores.forEach { s ->
            val m = c[s].orEmpty()
            val v = listOf(m["begins"] ?: 0, m["impressions"] ?: 0, m["closes"] ?: 0)
            println("%-16s %-10s %7d %12d %7d %8d".format(s, dates[s], v[0], v[1], v[2], v.sum()))
            t = intArrayOf(t[0] + v[0], t[1] + v[1], t[2] + v[2])
        }
        println("%-16s %-10s %7d %12d %7d %8d".format("all", "", t[0], t[1], t[2], t.sum()))
        val done = items.count { ledger.isSent(it.extId) }
        val due = items.count { it.dueMs <= nowMs && !ledger.isSent(it.extId) }
        println("already in the sent-ledger: $done; due now (would go out at the pace): $due; later (sent when due): ${items.size - done - due}")
    }
}

fun main() {
    exitProcess(PeopleRestLiveMain.run())
}
