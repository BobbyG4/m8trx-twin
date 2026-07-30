package com.m8trx.twin.layer1

import com.m8trx.twin.connect.model.ConnectMappers
import io.nats.client.Nats
import io.nats.client.Options
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * `./gradlew impressionWatch` — twin's **in-code wire counter** for the people plane.
 *
 * ## Why this exists
 *
 * S15 reported `observed fixtureImpression 3,664`. In S16 that number could not be substantiated: there was
 * **no NATS consumer anywhere in this repo**, so the observation had been made out-of-band (CLI or edge-side)
 * and left no artifact. The whole `Channel(100)`-vs-persistence question then turned on whether 3,664 was a
 * wire count or generator intent — unanswerable from twin's records, and the investigation had already had to
 * retract two other artifact-less claims the same day.
 *
 * So: never again. This subscribes to what core actually publishes, counts it, dedupes it, and writes a CSV
 * that outlives the session. A twin claim about wire volume now has a file behind it.
 *
 * ## What it counts
 *
 * All three people-plane subjects, on **both** publish patterns (`area.<space>.<type>` legacy and
 * `m8trx.<tenant>.<site>.xovis.<type>` modern), deduped by envelope `id` — so a dual-published event counts
 * once, and the count is a count of EVENTS, not of deliveries.
 *
 *  - `fixtureImpression`      — the number in dispute
 *  - `lookingAtFixture`       — look-clock transitions
 *  - `dwellingNearbyFixture`  — proximity transitions
 *
 * Per-minute buckets are keyed on **wall-clock arrival**, which is deliberately NOT the same clock as
 * `impression_event.recorded_at` (that is `firstLook`, event time). Both are emitted so the two can be
 * aligned rather than silently conflated — conflating them is what made the first emitted-vs-accepted diff
 * read a phantom 13% pre-step loss that was really a one-minute bucket offset.
 *
 * Runs until killed (Ctrl-C / SIGTERM); the shutdown hook writes the CSV and the summary.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.layer1.ImpressionWatcher")

private val SUBJECTS = listOf("fixtureImpression", "lookingAtFixture", "dwellingNearbyFixture")

fun main() {
    val natsUrl = System.getenv("M8TRX_NATS_URL") ?: error("M8TRX_NATS_URL is not set")
    val outDir = Path.of(System.getenv("M8TRX_WATCH_OUT") ?: ".twin-state")
    val tag = System.getenv("M8TRX_WATCH_TAG") ?: "watch"
    val expectServer = System.getenv("M8TRX_NATS_EXPECT_SERVER") // e.g. edge-twin-denver

    // ── counters ──
    // id -> subject, so a dual-published event is counted once and re-deliveries cannot inflate.
    val seen = ConcurrentHashMap<String, String>()
    val perSubject = ConcurrentHashMap<String, AtomicLong>()
    val perMinute = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()
    val perFixture = ConcurrentHashMap<String, AtomicLong>()
    val dupes = AtomicLong()
    val firstSeen = AtomicLong(0)
    val lastSeen = AtomicLong(0)

    val minFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

    val conn = Nats.connect(
        Options.Builder().server(natsUrl).connectionTimeout(java.time.Duration.ofSeconds(10)).build(),
    )
    val serverName = conn.serverInfo?.serverName
    log.info("connected: url={} server_name={}", natsUrl, serverName)

    // ⛔ Two edges share .29 — :4222 is edge-itx-office with REAL Xovis hardware. A watcher is read-only,
    // but asserting anyway keeps the habit uniform across every driver in this repo.
    if (expectServer != null && serverName != expectServer) {
        log.error("REFUSING: expected server_name={} but connected to {}", expectServer, serverName)
        conn.close()
        exitProcess(2)
    }

    val dispatcher = conn.createDispatcher { msg ->
        val type = SUBJECTS.firstOrNull { msg.subject.endsWith(".$it") } ?: return@createDispatcher
        val now = System.currentTimeMillis()
        val id = runCatching {
            ConnectMappers.camel.readTree(msg.data).let { n ->
                n.path("id").asText(null) ?: n.path("eventId").asText(null)
            }
        }.getOrNull() ?: "$type-$now-${msg.subject.hashCode()}" // no id ⇒ cannot dedupe; count it

        if (seen.putIfAbsent(id, type) != null) {
            dupes.incrementAndGet()
            return@createDispatcher
        }
        firstSeen.compareAndSet(0, now)
        lastSeen.set(now)
        perSubject.computeIfAbsent(type) { AtomicLong() }.incrementAndGet()
        perMinute.computeIfAbsent(minFmt.format(Instant.ofEpochMilli(now))) { ConcurrentHashMap() }
            .computeIfAbsent(type) { AtomicLong() }.incrementAndGet()

        if (type == "fixtureImpression") {
            val code = runCatching {
                ConnectMappers.camel.readTree(msg.data).let { n ->
                    n.path("zoneCode").asText(null) ?: n.path("fixtureId").asText(null) ?: "?"
                }
            }.getOrNull() ?: "?"
            perFixture.computeIfAbsent(code) { AtomicLong() }.incrementAndGet()
        }
    }
    // Both publish patterns, all three subjects. Wildcards rather than a resolved space id so the watcher
    // needs no spatial config and cannot silently miss a re-keyed space.
    SUBJECTS.forEach {
        dispatcher.subscribe("area.*.$it")
        dispatcher.subscribe("m8trx.*.*.xovis.$it")
    }
    log.info("subscribed: {} × (area.*.<type>, m8trx.*.*.xovis.<type>)", SUBJECTS.size)
    log.info("★ counting. Ctrl-C to stop and write {}/{}-wire-counts.csv", outDir, tag)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            Files.createDirectories(outDir)
            val csv = outDir.resolve("$tag-wire-counts.csv")
            val minutes = perMinute.keys.sorted()
            Files.newBufferedWriter(csv).use { w ->
                w.write("# twin in-code wire counter — tag=$tag server=$serverName\n")
                w.write("# deduped by envelope id; minute = WALL-CLOCK arrival (UTC), NOT recorded_at/firstLook\n")
                w.write("minute_utc,${SUBJECTS.joinToString(",")}\n")
                minutes.forEach { m ->
                    val row = perMinute[m]!!
                    w.write("$m,${SUBJECTS.joinToString(",") { (row[it]?.get() ?: 0).toString() }}\n")
                }
                w.write("# per-fixture fixtureImpression counts\n")
                perFixture.entries.sortedByDescending { it.value.get() }.forEach { w.write("# ${it.key},${it.value.get()}\n") }
            }
            log.info("")
            log.info("═══ WIRE COUNTS (deduped, in twin code — citable) ═══")
            SUBJECTS.forEach { log.info("  {} = {}", it.padEnd(22), perSubject[it]?.get() ?: 0) }
            log.info("  duplicate deliveries suppressed = {}", dupes.get())
            log.info("  distinct fixtures with impressions = {}", perFixture.size)
            if (firstSeen.get() > 0) {
                log.info(
                    "  window {} → {} ({}s)",
                    minFmt.format(Instant.ofEpochMilli(firstSeen.get())),
                    minFmt.format(Instant.ofEpochMilli(lastSeen.get())),
                    (lastSeen.get() - firstSeen.get()) / 1000,
                )
            }
            log.info("  CSV → {}", csv)
            runCatching { conn.close() }
        },
    )

    Thread.currentThread().join()
}
