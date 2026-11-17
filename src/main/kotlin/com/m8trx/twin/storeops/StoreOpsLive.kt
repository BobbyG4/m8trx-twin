package com.m8trx.twin.storeops

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.storeday.StoreDaySim
import com.m8trx.twin.storeday.StoreState
import com.m8trx.twin.storeday.buildSim
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * The live ops driver (Phase 3 brief § 2): at each store's local midnight it runs the store-day for the new date from the saved
 * [StoreState], then every few seconds sends what is due, in plan order, through a [Pump]. In this slice it is **dry-run only**:
 * `gradle storeOpsLiveDryRun` runs it against a simulated clock (a whole day in seconds) and writes what it WOULD send, asserting the
 * per-unit ordering rule before each "send". The live sender exists behind `M8TRX_STORE_OPS_LIVE=true` plus a confirmation and is
 * never built otherwise.
 *
 * Shape (mirrors the live plane, `layer3/LiveDrive`): started by hand; the HOLD file (`M8TRX_LIVE_HOLD_FILE`, default
 * `$M8TRX_OPS_DIR/HOLD`, the SAME file the live plane polls) stops it cleanly; refusals exit 3 (never restarted) and HOLD 6.
 * It refuses a store the owner file does not give it ([StoreOwnership]). The lane per store is a FIFO of day pumps: a new day's
 * pump waits behind an undrained one, so an outage over midnight never reorders a unit's events.
 *
 * Restart: the state at the start of the day being served is saved (`<out>/<slug>/state/day-start`); a restart re-simulates the
 * same day from it (same seed, same date: the same ids) and the sent-ledger skips what was accepted.
 *
 * Counts and the people plane: `count` wire events need the store manager's login (OpsClient plus [LoginGate]); that wiring is a
 * later slice, so a live run halts at the first count, loudly. `person_samples_ref` events belong to the people plane (the
 * LiveDrive store-day sidecar); the driver counts them and sends none.
 *
 * Env: `M8TRX_STORE_OPS_STORES` (dec-us-denver) · `M8TRX_STORE_OPS_OWNER_FILE` · `M8TRX_STORE_OPS_OUT` (out/store-ops) ·
 * `M8TRX_STORE_OPS_TICK_S` (5) · dry run: `M8TRX_STORE_OPS_FROM` (first simulated date; default the day after the state's) ·
 * `M8TRX_STORE_OPS_DAYS` (1) · `M8TRX_STORE_OPS_STATE` (a snapshot base path, no extension; default the offline initial state) ·
 * live: `M8TRX_STORE_OPS_LIVE` (false) and `M8TRX_STORE_OPS_CONFIRM` (= `<slug>`, a second key) · `M8TRX_STORE_OPS_HEALTH_EVERY_S` (30).
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeops.StoreOpsLive")

/** Pace per wire kind after any delay: a backlog after an outage must not burst. Moves one batch per 2 s, receipts one at a time (A16), sales 2/s. */
val OPS_SPACING_MS: Map<String, Long> = mapOf("move" to 2_000L, "sale" to 500L, "receive" to 40_000L)

class OpsLiveConfig(
    val stores: List<String>,
    val ownerFile: Path,
    val outDir: Path,
    val chainDir: Path,
    val stateDir: Path,
    val tickMs: Long,
    val holdFile: Path?,
    val healthEveryMs: Long,
    val live: Boolean,
    val confirm: String?,
    val from: LocalDate?,
    val days: Int,
    val statePath: String?,
)

/** What one store served: the items planned and the items the sender was handed, both in order. */
class StoreServed(
    val slug: String,
    val planned: List<SendItem>,
    val sent: List<SendItem>,
    val peopleRefs: Int,
    val healthChecks: Int,
    val halted: String?,
    val held: Boolean,
)

object StoreOpsLive {
    fun refusal(cfg: OpsLiveConfig, ownership: StoreOwnership, slug: String): String? {
        ownership.refusal(slug)?.let { return it }
        if (cfg.live && cfg.confirm != slug) return "live run for $slug needs M8TRX_STORE_OPS_CONFIRM=$slug"
        return null
    }

    private fun unsold(s: StoreState): List<String> = s.locations().flatMap { l -> s.eansAt(l).flatMap { e -> s.epcsAt(l, e) } }

    fun midnight(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Serve one store from [first]'s local midnight for [days] days against [clock] (a simulated clock in the dry run). Returns what
     * was planned and what was sent. [mkSender] is called once; a dry run passes a [DryRunSender].
     */
    fun serve(
        sim: StoreDaySim,
        state: StoreState,
        first: LocalDate,
        days: Int,
        clock: SimClock,
        tickMs: Long,
        sender: OpsSender,
        policy: RetryPolicy,
        ledger: SentLedger,
        hold: () -> Boolean,
        healthEveryMs: Long,
        stateDir: Path?,
        zone: ZoneId,
        onEvent: (String) -> Unit = {},
    ): StoreServed {
        val slug = sim.model.slug
        val seed = sim.behavior.params.seed
        val planned = ArrayList<SendItem>()
        val lane = ArrayDeque<Pump>()
        var peopleRefs = 0
        var health = 0
        var halted: String? = null
        var held = false
        val end = midnight(first.plusDays(days.toLong()), zone)
        var nextDate = first
        clock.nowMs = midnight(first, zone)
        while (true) {
            val now = clock.nowMs
            if (now >= midnight(nextDate, zone) && nextDate < first.plusDays(days.toLong())) {
                val date = nextDate
                nextDate = date.plusDays(1)
                // the state the day starts from is saved first: a restart re-simulates this same day from it
                stateDir?.let { state.save(it.resolve("day-start")) }
                require(state.asOf == null || state.asOf!! < date) { "$slug: the saved state is already as of ${state.asOf}, not before $date" }
                val known = unsold(state)
                val r = sim.simulate(state, date, seed)
                val items = r.wire.filter { it.kind != "person_samples_ref" }.map { SendItem.fromWire(it, slug) }
                peopleRefs += r.wire.size - items.size
                planned += items
                lane += Pump(
                    items, sender, policy, ledger, OrderingGuard(known), OPS_SPACING_MS, healthEveryMs, 5_000, hold, onEvent,
                )
                onEvent("$date: ${items.size} items planned (${items.groupingBy { it.kind }.eachCount()}), seed=$seed")
            }
            while (lane.isNotEmpty()) {
                val p = lane.first()
                when (p.pump(now)) {
                    Pump.State.IDLE -> {
                        health += p.healthChecks
                        lane.removeFirst()
                        continue
                    }

                    Pump.State.WAITING -> {}

                    Pump.State.HOLD -> held = true

                    Pump.State.HALTED -> halted = p.haltReason
                }
                break
            }
            if (held || halted != null) break
            if (lane.isEmpty() && now >= end) break
            clock.advance(tickMs)
            check(clock.nowMs < end + 48 * 3_600_000L) {
                "$slug: the lane did not drain within two days after the last midnight: ${lane.size} pumps, " +
                    "head ${lane.firstOrNull()?.queue?.head()?.let {
                        "${it.kind} ${it.extId} due ${it.dueMs}"
                    }}, clock ${clock.nowMs}"
            }
        }
        return StoreServed(slug, planned, (sender as? DryRunSender)?.sent.orEmpty(), peopleRefs, health, halted, held)
    }
}

fun main() {
    fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    val live = env("M8TRX_STORE_OPS_LIVE")?.toBoolean() == true
    val ops = env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
    val stateDir = (env("M8TRX_STREAM_SOLD_LOG")?.let(Path::of) ?: Path.of(".twin-state/sold-epcs.txt")).let { it.parent ?: Path.of(".twin-state") }
    val cfg = OpsLiveConfig(
        stores = (env("M8TRX_STORE_OPS_STORES") ?: "dec-us-denver").split(",").map { it.trim() }.filter { it.isNotEmpty() },
        ownerFile = Path.of(env("M8TRX_STORE_OPS_OWNER_FILE") ?: if (live) "$ops/store-ops-owner.csv" else "ops/store-ops-owner.example.csv"),
        outDir = Path.of(env("M8TRX_STORE_OPS_OUT") ?: "out/store-ops"),
        chainDir = chainDir,
        stateDir = stateDir,
        tickMs = ((env("M8TRX_STORE_OPS_TICK_S")?.toDouble() ?: 5.0) * 1000).toLong(),
        holdFile = Path.of(env("M8TRX_LIVE_HOLD_FILE") ?: "$ops/HOLD"),
        healthEveryMs = ((env("M8TRX_STORE_OPS_HEALTH_EVERY_S")?.toDouble() ?: 30.0) * 1000).toLong(),
        live = live,
        confirm = env("M8TRX_STORE_OPS_CONFIRM"),
        from = env("M8TRX_STORE_OPS_FROM")?.let(LocalDate::parse),
        days = env("M8TRX_STORE_OPS_DAYS")?.toInt() ?: 1,
        statePath = env("M8TRX_STORE_OPS_STATE"),
    )
    val ownership = try {
        StoreOwnership.load(cfg.ownerFile)
    } catch (e: IllegalArgumentException) {
        log.error("REFUSING: {}", e.message)
        exitProcess(3)
    }
    cfg.stores.forEach { s ->
        StoreOpsLive.refusal(cfg, ownership, s)?.let {
            log.error("REFUSING: {}", it)
            exitProcess(3)
        }
    }
    if (cfg.holdFile != null && Files.exists(cfg.holdFile)) {
        log.error("REFUSING: HOLD is set ({}): nothing is sent until resume", cfg.holdFile)
        exitProcess(6)
    }
    if (cfg.live) {
        log.error(
            "REFUSING: the live loop (real clock, real sender) is not part of the Phase 3 tooling slice; run storeOpsLiveDryRun. " +
                "LiveSender exists and is built only on a live run, which this slice never makes.",
        )
        exitProcess(3)
    }
    log.info("storeOpsLiveDryRun: simulated clock, nothing is sent, no client is built. stores={} owner file={}", cfg.stores, cfg.ownerFile)
    val keys = ChainKeys.load(cfg.chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName))
    for (slug in cfg.stores) {
        val sim = buildSim(slug)
        val state = cfg.statePath?.let { StoreState.load(Path.of(it)) } ?: sim.initialState()
        val first = cfg.from ?: state.asOf?.plusDays(1) ?: LocalDate.of(2026, 11, 23)
        val zone = keys.store(slug).zone
        val clock = SimClock(StoreOpsLive.midnight(first, zone))
        val out = cfg.outDir.resolve(slug)
        Files.createDirectories(out)
        val log0 = Files.newBufferedWriter(out.resolve("would-send.jsonl"))
        val sender = DryRunSender { i ->
            val n = ConnectMappers.snake.createObjectNode()
            n.put("sim_time", Instant.ofEpochMilli(clock.nowMs).toString())
            n.put("due", Instant.ofEpochMilli(i.dueMs).toString())
            n.put("kind", i.kind)
            n.put("route", i.route.label)
            n.put("ext_id", i.extId)
            n.put("units", i.epcs.size)
            log0.write(ConnectMappers.snake.writeValueAsString(n) + "\n")
        }
        val t0 = System.nanoTime()
        val served = log0.use {
            StoreOpsLive.serve(
                sim, state, first, cfg.days, clock, cfg.tickMs, sender, RetryPolicy(), SentLedger(null),
                { cfg.holdFile != null && Files.exists(cfg.holdFile) }, cfg.healthEveryMs, out.resolve("state"), zone,
            ) { log.info("{}", it) }
        }
        val inOrder = served.sent.map { it.extId } == served.planned.map { it.extId }
        println(
            "$slug $first +${cfg.days}d: planned ${served.planned.size} items, would send ${served.sent.size}, in plan order: $inOrder, " +
                "people refs not sent ${served.peopleRefs}, health checks ${served.healthChecks}, halted=${served.halted}, held=${served.held}; " +
                "${served.sent.groupingBy { it.kind }.eachCount()}; simulated ${cfg.days} day(s) in ${(System.nanoTime() - t0) / 1_000_000} ms; " +
                "live clients built: ${LiveClients.constructed}",
        )
        if (!inOrder || served.halted != null) exitProcess(1)
    }
}
