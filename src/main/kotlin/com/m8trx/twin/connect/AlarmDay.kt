package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.webhook.AlarmEnvelope
import com.m8trx.twin.connect.sim.AlarmDriver
import com.m8trx.twin.history.HistoryCsv
import com.m8trx.twin.history.RecentDay
import com.m8trx.twin.layer2.EasTagging
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * The DAY mode of `connectAlarmDrive` (BRIEF-TWIN-RECENT-WINDOW § 1.3) — on when `M8TRX_ALARM_DATE` is set;
 * without it the driver runs the §A acceptance chain exactly as before.
 *
 * A day run sends that day's gate alarms, store by store, as the outside EAS vendor:
 *
 *  - **Which alarms:** the day's own `EasGateCrossed` with `shouldAlarm` (an outbound shopper carrying a
 *    concealed tagged unit, nothing paid) from [RecentDay] — the same shoppers the dated sale stream sells to,
 *    at the history's ~3 per store-week. Plus any SCRIPTED alarm (`M8TRX_ALARM_SCRIPT`, e.g.
 *    `dec-us-denver@14:07` — the demo script's Tuesday alarm at 14:07 store-local time).
 *  - **`occurred_at` = the crossing's own time** (the end of the walk-out), so `alert.created_at` lands on the
 *    store's hours once core honors it.
 *  - **`zone_ref` = `M8TRX_ALARM_ZONE_REF`** when set (the platform zone's own code — the chain's Entrance zones are
 *    `Z-01`, which core resolves directly), else the store's layout gate code (`CS-01`), which resolves only where
 *    that label is registered in `fixture_code_mapping`; otherwise the alarm lands site-level (§8.1).
 *  - **The subject is a real unit**: a floor EPC of the concealed EAN (a shoplifter takes from the floor),
 *    never one already sold or reserved by a dated sale plan. A scripted alarm takes a floor unit that
 *    [EasTagging]'s rule would have tagged.
 *  - **Resumable + nothing in the future**, the same discipline as [DatedStream]: a live run persists its plan
 *    (`.twin-state/alarm/<runId>.plan.csv`) and a sent ledger; future-dated alarms are held unless
 *    `M8TRX_ALARM_ALLOW_FUTURE=true` (`/alerts/query` reads `[now-24h, now)`).
 *
 * DRY-RUN unless `M8TRX_ALARM_LIVE=true`; a dry run makes no network call and writes no state (unlike the
 * acceptance mode, whose dry-run still probes the diagnostics).
 *
 * Env: `M8TRX_ALARM_DATE` · `M8TRX_ALARM_SEED` (default 20261016, the history's) · `M8TRX_ALARM_STORES` (csv,
 * default all ten) · `M8TRX_ALARM_SCRIPT` (csv of `<slug>@HH:mm`) · `M8TRX_ALARM_SOURCE` (twin-eas) ·
 * `M8TRX_ALARM_LIVE` · `M8TRX_ALARM_ALLOW_FUTURE` · `M8TRX_ALARM_GAP_MS` (500) · `M8TRX_STREAM_SOLD_LOG` ·
 * `M8TRX_CHAIN_DIR`.
 */
object AlarmDay {
    private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.AlarmDay")

    data class PlannedAlarm(
        val id: String,
        val store: String,
        val at: Instant,
        val gate: String,
        val epc: String,
        val ean: String,
        val scripted: Boolean,
    )

    /** `<slug>@HH:mm` list → (slug, local time). */
    fun parseScript(s: String?): List<Pair<String, LocalTime>> = s.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.map {
        val (slug, t) = it.split("@", limit = 2)
        slug.trim() to LocalTime.parse(t.trim())
    }

    fun run() {
        val date = LocalDate.parse(System.getenv("M8TRX_ALARM_DATE"))
        val seed = System.getenv("M8TRX_ALARM_SEED")?.toLongOrNull() ?: RecentDay.HISTORY_SEED
        val live = System.getenv("M8TRX_ALARM_LIVE")?.toBoolean() == true
        val allowFuture = System.getenv("M8TRX_ALARM_ALLOW_FUTURE")?.toBoolean() == true
        val source = System.getenv("M8TRX_ALARM_SOURCE") ?: "twin-eas"
        val gapMs = System.getenv("M8TRX_ALARM_GAP_MS")?.toLongOrNull() ?: 500L
        val chainDir = Path.of(System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
        val soldLog = Path.of(System.getenv("M8TRX_STREAM_SOLD_LOG")?.takeIf { it.isNotBlank() } ?: ".twin-state/sold-epcs.txt")
        val state = soldLog.parent ?: Path.of(".twin-state")
        val runId = "twin-alarm-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}-$seed"
        val planFile = state.resolve("alarm/$runId.plan.csv")
        val sentFile = state.resolve("alarm/$runId.sent")
        val script = parseScript(System.getenv("M8TRX_ALARM_SCRIPT"))
        val recent = RecentDay(seed, chainDir)
        val stores = System.getenv("M8TRX_ALARM_STORES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: recent.stores.map { it.slug }
        val t0 = System.currentTimeMillis()
        log.info("═══ alarm day ═══ date={} seed={} stores={} script={} source={} live={}", date, seed, stores.size, script, source, live)

        // units the dated sale stream has sold or reserved are not on the floor to be stolen
        val excluded = readPriorSold(soldLog) + reservedEpcs(state.resolve("stream"))
        val planned: List<PlannedAlarm> = if (live && Files.exists(planFile)) {
            readPlan(planFile).also { log.info("reusing the persisted alarm plan {} ({} alarms)", planFile, it.size) }
        } else {
            stores.flatMap { slug -> planStore(recent, chainDir, slug, date, seed, script, excluded) }.sortedBy { it.at }
        }

        val sent = if (Files.exists(sentFile)) Files.readAllLines(sentFile).map { it.trim() }.toSet() else emptySet()
        val now = Instant.now()
        val due = planned.filter { it.id !in sent && (allowFuture || !it.at.isAfter(now)) }
        val held = planned.count { it.id !in sent && !allowFuture && it.at.isAfter(now) }
        val driver = AlarmDriver(WebhookClient(ConnectConfig.fromEnv()), ConnectClient(ConnectConfig.fromEnv()))
        planned.forEach { a ->
            val local = a.at.atZone(recent.store(a.store).zone)
            log.info(
                "  {} {} {} local={} gate={} epc={} ean={}{}",
                if (a.id in sent) {
                    "[sent]"
                } else if (a.at.isAfter(now) && !allowFuture) {
                    "[held]"
                } else {
                    "[due] "
                },
                a.store,
                a.at,
                local.toLocalTime().withNano(0),
                a.gate,
                a.epc,
                a.ean,
                if (a.scripted) " ★ scripted" else "",
            )
        }
        log.info(
            "PLAN: {} alarm(s) · per store {} · due {} · held (future) {}",
            planned.size,
            planned.groupingBy {
                it.store
            }.eachCount(),
            due.size,
            held,
        )
        planned.firstOrNull()?.let { log.info("  first envelope: {}", driver.dryRun(envelope(driver, source, it))) }

        if (!live) {
            log.info("DRY-RUN (M8TRX_ALARM_LIVE!=true) — nothing sent, no state written.")
            log.info(
                "SIM-RUN alarm date={} seed={} alarms={} sent_ok=0 sent_err=0 held_future={} wall={}",
                date,
                seed,
                planned.size,
                held,
                fmt(System.currentTimeMillis() - t0),
            )
            return
        }
        Files.createDirectories(planFile.parent)
        if (!Files.exists(planFile)) writePlan(planFile, planned)
        var ok = 0
        var err = 0
        for ((i, a) in due.withIndex()) {
            when (val r = driver.drive(envelope(driver, source, a))) {
                is ConnectResponse.Ok -> {
                    ok++
                    Files.writeString(sentFile, "${a.id}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    appendSold(soldLog, a.epc) // a stolen unit is gone from the floor — never sell it later
                }

                is ConnectResponse.Err -> {
                    err++
                    log.error("  {} refused {} {} — stopping; a re-run resumes from the ledger", a.id, r.error.status, r.error.code)
                    break
                }
            }
            if (i < due.size - 1) Thread.sleep(gapMs)
        }
        log.info(
            "SIM-RUN alarm date={} seed={} alarms={} sent_ok={} sent_err={} held_future={} wall={}",
            date,
            seed,
            planned.size,
            ok,
            err,
            held,
            fmt(System.currentTimeMillis() - t0),
        )
        if (err > 0) error("an alarm was refused — see the log")
    }

    fun envelope(driver: AlarmDriver, source: String, a: PlannedAlarm): AlarmEnvelope =
        driver.gateExitUnpaid(source, a.store, a.gate, a.epc, a.at.toEpochMilli())

    private fun planStore(
        recent: RecentDay,
        chainDir: Path,
        slug: String,
        date: LocalDate,
        seed: Long,
        script: List<Pair<String, LocalTime>>,
        excluded: Set<String>,
    ): List<PlannedAlarm> {
        val store = recent.store(slug)
        val storeDir = chainDir.resolve("stores/$slug")
        val layoutGate = EasTagging.loadGates(storeDir.resolve("layout.json")).firstOrNull()?.code
        if (layoutGate == null) {
            log.warn("  {} has no eas_gate crossing slice — no alarms (absence stays absence)", slug)
            return emptyList()
        }
        // The platform places an alarm by `zone_ref`: a zone UUID, the zone's own code (`zone.properties.code`), or a
        // label registered in `fixture_code_mapping`. The layout's crossing slice (`CS-01`) is twin-side only and a gate
        // label cannot be registered through the product today (the fixture-code save accepts fixture zones only), so
        // M8TRX_ALARM_ZONE_REF names the platform zone to send instead — the chain's Entrance zones all carry `Z-01`.
        // Unset, the layout code is sent and the alarm lands site-level (§8.1).
        val gate = System.getenv("M8TRX_ALARM_ZONE_REF")?.takeIf { it.isNotBlank() } ?: layoutGate
        val floor = DatedStream.loadEpcRows(storeDir.resolve("epcs.csv")).filter { !it.backOfHouse && it.epc !in excluded }.sortedBy { it.epc }
        val byEan = floor.groupBy { it.ean }
        val used = HashSet<String>()
        fun unitOf(ean: String): DatedStream.EpcRow? = byEan[ean]?.firstOrNull { it.epc !in used }?.also { used += it.epc }
        val day = recent.run(slug, date)
        val out = ArrayList<PlannedAlarm>()
        day?.alarms?.forEachIndexed { i, a ->
            val unit = unitOf(a.ean)
            if (unit == null) {
                log.warn("  {} alarm at {} — no floor unit of {} left; skipped", slug, a.at, a.ean)
            } else {
                out += PlannedAlarm("$slug-$i", slug, a.at, gate, unit.epc, unit.ean, scripted = false)
            }
        }
        if (day == null) log.info("  {} CLOSED on {} — no generated alarms", slug, date)
        val scripted = script.filter { it.first == slug }
        if (scripted.isNotEmpty()) {
            val catalog = DatedStream.loadCatalog(storeDir.resolve("assortment.csv"))
            val categories = categoriesOf(storeDir.resolve("assortment.csv"))
            val tagged = floor.filter { r ->
                val price = catalog[r.ean]?.second ?: return@filter false
                EasTagging.isTagged(price, categories[r.ean] ?: return@filter false) && r.epc !in used
            }
            scripted.forEachIndexed { k, (_, t) ->
                val rng = Random(HistoryCsv.derive(seed, slug, date, "scripted-alarm", k))
                val unit = tagged.filter { it.epc !in used }.let { if (it.isEmpty()) null else it[rng.nextInt(it.size)] }
                if (unit == null) {
                    log.warn("  {} scripted alarm {} — no tagged floor unit left; skipped", slug, t)
                } else {
                    used += unit.epc
                    val at = ZonedDateTime.of(date, t, store.zone).toInstant()
                    out += PlannedAlarm("$slug-s$k", slug, at, gate, unit.epc, unit.ean, scripted = true)
                }
            }
        }
        return out
    }

    private fun categoriesOf(assortment: Path): Map<String, String> {
        val lines = Files.readAllLines(assortment)
        val h = lines.first().split(",")
        val iEan = h.indexOf("ean")
        val iCat = h.indexOf("category")
        return lines.drop(1).mapNotNull { l -> csvSplit(l).let { c -> c.getOrNull(iCat)?.let { c[iEan].trim() to it.trim() } } }.toMap()
    }

    private fun reservedEpcs(streamDir: Path): Set<String> {
        if (!Files.isDirectory(streamDir)) return emptySet()
        return Files.list(streamDir).use { s -> s.filter { it.fileName.toString().endsWith(".plan.csv") }.toList() }
            .flatMap { DatedStream.readPlan(it).flatMap { p -> p.epcs } }.toSet()
    }

    private fun writePlan(file: Path, alarms: List<PlannedAlarm>) {
        Files.write(
            file,
            listOf("id,store,at,gate,epc,ean,scripted") + alarms.map {
                "${it.id},${it.store},${it.at},${it.gate},${it.epc},${it.ean},${it.scripted}"
            },
        )
    }

    private fun readPlan(file: Path): List<PlannedAlarm> = Files.readAllLines(file).drop(1).filter { it.isNotBlank() }.map {
        val c = it.split(",")
        PlannedAlarm(c[0], c[1], Instant.parse(c[2]), c[3], c[4], c[5], c[6].toBoolean())
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%dh%02dm%02ds".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
