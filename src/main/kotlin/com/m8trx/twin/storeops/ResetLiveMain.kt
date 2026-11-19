package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.HistoryCsv
import com.m8trx.twin.ops.OpsConfig
import com.m8trx.twin.ops.StockDrive
import com.m8trx.twin.storeday.buildSim
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("com.m8trx.twin.storeops.ResetLive")

/** The live entry of `storeDayReset`: gates, plan (fresh or frozen), spot count, then [ResetLiveRunner] through [LiveSender]. Returns the exit code. */
object ResetLiveMain {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    fun run(slug: String, forDate: LocalDate, chainDir: Path, stateDir: Path, pacing: ResetPacing, oldFile: Path, out: Path): Int {
        val ops = env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"
        val ownerFile = Path.of(env("M8TRX_STORE_OPS_OWNER_FILE") ?: "$ops/store-ops-owner.csv")
        val holdFile = Path.of(env("M8TRX_LIVE_HOLD_FILE") ?: "$ops/HOLD")
        val frozen = Files.exists(ResetLivePlan.markerFile(out))
        val fresh = if (frozen) null else StoreDayReset.build(slug, forDate, chainDir, stateDir, pacing, oldFile)
        val items = fresh?.second?.items ?: ResetLivePlan.load(out)
        log.info(
            "storeDayReset LIVE {} first live day {}: {} plan ({} items), state={} out={}",
            slug,
            forDate,
            if (frozen) "FROZEN (resuming)" else "fresh",
            items.size,
            stateDir,
            out,
        )

        val oversize = ResetGate.oversizeReceives(items)
        if (oversize.isNotEmpty()) {
            log.error("REFUSING: receive requests carry at most {} tags; over the limit: {}", ResetGate.MAX_RECEIVE_TAGS, oversize.take(5))
            return 3
        }
        val verdict = ResetGate.check(slug, ::env, stateDir, ownerFile, items.count { it.kind == "planogram" && "-end-" in it.extId })
        if (verdict.refused) {
            verdict.reasons.forEach { log.error("REFUSING: {}", it) }
            return 3
        }
        if (Files.exists(holdFile)) {
            log.error("REFUSING: HOLD is set ({}): nothing is sent until resume", holdFile)
            return 6
        }
        val space = ReceiveStock.readSpaces(stateDir.resolve("receive/stockroom-spaces.csv"))[slug]
        if (space == null) {
            log.error("REFUSING: no stockroom space id for {} in {}", slug, stateDir.resolve("receive/stockroom-spaces.csv"))
            return 3
        }
        val zones = BackroomZones.resolve(slug, space, stateDir)
        val missing = BackroomZones.missing(items, zones)
        if (missing.isNotEmpty()) {
            log.error(
                "REFUSING: no stockroom zone id for rack(s) {} (read of /spatial/identity found {}); write {} as 'BR-nn,<zone id>' lines",
                missing,
                zones.keys.sorted(),
                BackroomZones.overrideFile(stateDir, slug),
            )
            return 3
        }

        val spotNote: String
        if (fresh != null) {
            val ack = env("M8TRX_RESET_SPOTCOUNT_ACK")
            spotNote = if (ack != null) {
                "spot count: operator acknowledgment: $ack"
            } else {
                val ledger = fresh.first.ledger
                val sample = SpotCount.sample(slug, ledger, HistoryCsv.derive(1L, slug, forDate))
                val platform = try {
                    StockDrive(OpsConfig.fromEnv("M8TRX_RESET")).platformStockFor(slug)
                } catch (e: Exception) {
                    log.error("REFUSING: the spot count's platform read failed: {}", e.message)
                    return 3
                }
                val v = SpotCount.evaluate(ledger, platform, sample)
                v.perFixture.forEach { log.info("{}", it) }
                log.info("{}", v.summary())
                if (v.stop) {
                    log.error("REFUSING: {}", v.summary())
                    return 3
                }
                v.summary() + "\n" + v.perFixture.joinToString("\n")
            }
            StoreDayReset.write(out, slug, forDate, fresh.first, fresh.second, pacing)
            val zone = StoreDayReset.zoneOf(slug)
            Files.writeString(
                out.resolve("report.md"),
                "\n## LIVE RUN (started ${Instant.now()})\n" +
                    "- receive lanes: ${pacing.receiveLanes}; move pace ${pacing.moveSpacingMs / 1000.0} s a batch\n" +
                    "- $spotNote\n- the directive steps wait for ${forDate.atStartOfDay(zone).toInstant()} (local midnight of $forDate)\n",
                StandardOpenOption.APPEND,
            )
            ResetLivePlan.writeAtomic(
                ResetLivePlan.markerFile(out),
                "{\"slug\":\"$slug\",\"date\":\"$forDate\",\"started\":\"${Instant.now()}\",\"items\":${items.size}}\n",
            )
        } else {
            spotNote = "resumed: spot count not repeated"
        }
        val runLog = out.resolve("live-run.log")
        fun event(s: String) {
            log.info("{}", s)
            synchronized(runLog) {
                Files.writeString(runLog, "${Instant.now()} $s\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
        event("LIVE RUN start $slug $forDate lanes=${pacing.receiveLanes} frozen=$frozen items=${items.size}")
        event(spotNote.lineSequence().first())

        val model = buildSim(slug).model
        val rackDefault = model.stockPlan.filter { it.kind == "backroom" }.minOfOrNull { it.zone } ?: "BR-01"
        val zoneId = StoreDayReset.zoneOf(slug)
        val params = ResetLiveParams(
            receiveLanes = pacing.receiveLanes,
            moveSpacingMs = pacing.moveSpacingMs,
            directiveSpacingMs = 2_000,
            healthPollMs = 5_000,
            idlePollMs = 1_000,
            midnightMs = forDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )
        val result = ResetLiveRunner(
            items,
            LiveSender.create(true, slug, chainDir, stateDir, zones),
            RetryPolicy(),
            SentLedger(ResetLivePlan.sentLedgerFile(out)),
            ResetBooks(stateDir, slug, forDate) { ean -> model.homeRack[ean] ?: rackDefault },
            params,
            { Files.exists(holdFile) },
            SystemResetClock,
            onEvent = ::event,
            onProgress = { p -> ResetLivePlan.writeAtomic(out.resolve("progress.json"), ResetLivePlan.progressJson(p)) },
            lanesNow = { ResetLivePlan.lanesInForce(Path.of(ops).resolve("reset-lanes"), pacing.receiveLanes) },
        ).run()
        event(
            "LIVE RUN end: ${result.end} accepted=${result.accepted} skipped=${result.skippedByLedger} alerts=${result.alerts.size} " +
                "health checks=${result.healthChecks}${result.haltReason?.let { " reason=$it" } ?: ""}",
        )
        return when (result.end) {
            ResetLiveRunner.End.DONE -> {
                ResetLivePlan.writeAtomic(
                    out.resolve("reset-complete.json"),
                    "{\"slug\":\"$slug\",\"date\":\"$forDate\",\"finished\":\"${Instant.now()}\",\"post_state\":\"${out.resolve(
                        "state/post-reset",
                    )}\"}\n",
                )
                event("DONE. The store-day continues from ${out.resolve("state/post-reset")} (M8TRX_STORE_OPS_STATE).")
                0
            }

            ResetLiveRunner.End.HOLD -> 6

            ResetLiveRunner.End.HALTED -> 1
        }
    }
}

/**
 * `./gradlew storeDayResetOldDirective` — turns the `/compliance/state` JSON of the store's current directive (what the platform holds:
 * `directive_ref`, `name`, `targets[] {position, site_id, item_identifier, zone_name, required_quantity}`) into the file the reset reads,
 * `<state>/reset/old-directive-<slug>.json`, in the shape the twin sent (`name`, `external_directive_id`, `targets[]` with `site_id`,
 * `raw_fixture_code` = the zone NAME, `raw_item_identifier`, `required_quantity`, `position_sequence`). The read does not return
 * `facing_count` or `display_level`, so the ended directive's re-send omits them. Env: `M8TRX_RESET_COMPLIANCE_JSON` (input path),
 * `M8TRX_RESET_SITE`, `M8TRX_RESET_OLD_DIRECTIVES` (output; default the path above). Writes a file only; sends nothing.
 */
object OldDirectiveExport {
    fun convert(state: JsonNode): JsonNode {
        val id = state.path("directive_ref").asText("").ifBlank { null } ?: error("no directive_ref in the compliance state JSON")
        val out = ConnectMappers.snake.createObjectNode()
        out.put("name", state.path("name").asText(id))
        out.put("external_directive_id", id)
        val targets = out.putArray("targets")
        state.path("targets").forEach { t ->
            val item = t.path("item_identifier").asText("").ifBlank { null }
            val zone = t.path("zone_name").asText("").ifBlank { null }
            requireNotNull(zone) { "target ${t.path("position")} has no zone_name" }
            requireNotNull(item) { "target ${t.path("position")} has no item_identifier" }
            targets.addObject().apply {
                put("site_id", t.path("site_id").asText())
                put("raw_fixture_code", zone)
                put("raw_item_identifier", item)
                put("required_quantity", t.path("required_quantity").asInt())
                if (t.has("position")) put("position_sequence", t.path("position").asInt())
            }
        }
        require(targets.size() > 0) { "the compliance state has no targets" }
        return out
    }
}

fun main() {
    fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    val slug = env("M8TRX_RESET_SITE") ?: "dec-us-denver"
    val input = Path.of(env("M8TRX_RESET_COMPLIANCE_JSON") ?: error("set M8TRX_RESET_COMPLIANCE_JSON to the /compliance/state JSON file"))
    val stateDir = (env("M8TRX_STREAM_SOLD_LOG")?.let(Path::of) ?: Path.of(".twin-state/sold-epcs.txt")).let { it.parent ?: Path.of(".twin-state") }
    val outFile = env("M8TRX_RESET_OLD_DIRECTIVES")?.let(Path::of) ?: StoreDayReset.defaultOldFile(stateDir, slug)
    // the saved read may carry a leading status line (`200`) before the JSON object
    val text = Files.readString(input).let { it.substring(maxOf(0, it.indexOf('{'))) }
    val node = OldDirectiveExport.convert(ConnectMappers.snake.readTree(text))
    outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    Files.writeString(outFile, ConnectMappers.snake.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n")
    println("wrote $outFile: ${node.path("external_directive_id").asText()} with ${node.path("targets").size()} targets")
    exitProcess(0)
}
