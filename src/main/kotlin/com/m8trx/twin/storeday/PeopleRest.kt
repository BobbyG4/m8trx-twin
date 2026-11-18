package com.m8trx.twin.storeday

import com.fasterxml.jackson.databind.node.ObjectNode
import com.m8trx.twin.behavior.ShopperAgent
import com.m8trx.twin.behavior.Visit
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

/**
 * Phase 3 step 1 (plan 2026-11-17 § 5, § 8 A5 option 2): the nine non-Denver stores' PEOPLE as Connect REST, not an edge. OFFLINE: this object
 * only maps a simulated store day to a wire plan; nothing here opens a socket (the dry-run sender is in `storeops`).
 *
 * One shopper of the store-day simulation becomes, by the doc's own payload fields (`M8TRX-CONNECT-API.md` § 6):
 *  - a session BEGIN `POST /api/v2/visionai/person-sessions` `{trackingId, siteId, spaceId, entryTime}`, due at the entrance crossing;
 *  - one IMPRESSION `POST /api/v2/visionai/impressions` `{trackingId, siteId, spaceId, zoneId, firstLook, lastLook, firstDwell, lastDwell}`
 *    per visit the truth ledger counts (`Visit.impression`, the rule `StoreDayOutput.fixtureHours` uses), due when the dwell ends;
 *  - a session CLOSE `PATCH /api/v2/visionai/person-sessions/{id}` `{exitTime}`, due at the exit crossing.
 *
 * The impression is tied to its session by `trackingId` (the platform resolves it by space and window, +-60 s), so it needs no server id. The
 * CLOSE does: the server's session id comes back in the begin's 201 and is known only at send time, so a close line carries `session_ref`
 * (the begin's idempotent id) and the sender resolves it. Ids: `ppl-<slug>-<yyyymmdd>-<shopper>-b | i<n> | c`; the trackingId is the id
 * without its suffix. Time model of an impression, from the behavior model's episode (approach 1.2 s, engaged span, disengage 1.2 s):
 * firstDwell = firstLook = the visit's start, lastLook = start + approach + dwell, lastDwell = lastLook + disengage.
 */
object PeopleRest {
    const val SESSIONS_PATH = "/api/v2/visionai/person-sessions"
    const val IMPRESSIONS_PATH = "/api/v2/visionai/impressions"
    const val DENVER = "dec-us-denver"

    /** The nine stores that get people by REST. Denver stays on its edge. */
    val STORES: List<String> = StoreState.STORE_SLUGS.filter { it != DENVER }

    const val RANK_BEGIN = 0
    const val RANK_IMPRESSION = 1
    const val RANK_CLOSE = 2

    /** The ids one store needs: its site and sales-floor space, and the fixture zone id per fixture code (all from the chain keys export). */
    class StoreIds(val slug: String, val siteId: String, val spaceId: String, val zoneByFixture: Map<String, String>)

    /** One request of the wire plan. [sessionRef] is the begin's id on an impression or a close. */
    class Line(
        val dueMs: Long,
        val rank: Int,
        val seq: Int,
        val kind: String,
        val id: String,
        val method: String,
        val path: String,
        val sessionRef: String?,
        val body: ObjectNode,
        val shopper: String,
    )

    /** Null when [slug] may get REST people; otherwise why not. Denver is refused unless [includeDenver] (a test flag, never a live one). */
    fun refusal(slug: String, includeDenver: Boolean = false): String? = when {
        slug == DENVER && !includeDenver ->
            "$slug keeps its edge (edge-twin-denver :4223): REST people would be a second writer of the same people; " +
                "M8TRX_PEOPLE_REST_INCLUDE_DENVER=true includes it for offline tests only"

        slug !in StoreState.STORE_SLUGS -> "$slug is not one of the ten sales-floor stores"

        else -> null
    }

    fun idsFor(slug: String, keys: ChainKeys, siteIds: Map<String, String>): StoreIds {
        val st = keys.store(slug)
        val site = siteIds[slug] ?: error("no site id for $slug in site_ids.csv")
        return StoreIds(slug, site, st.salesFloorSpaceId, st.fixtures.associate { it.code to it.zoneId })
    }

    fun stamp(date: LocalDate): String = date.toString().replace("-", "")

    fun plan(slug: String, date: LocalDate, r: DayResult, ids: StoreIds): List<Line> {
        val out = ArrayList<Line>()
        var seq = 0
        r.records.forEach { rec ->
            val sh = rec.id.substringAfterLast('-')
            val base = "ppl-$slug-${stamp(date)}-$sh"
            out += Line(
                rec.entryMs, RANK_BEGIN, seq++, "person_begin", "$base-b", "POST", SESSIONS_PATH, null,
                ConnectMappers.camel.createObjectNode().also {
                    it.put("trackingId", base)
                    it.put("siteId", ids.siteId)
                    it.put("spaceId", ids.spaceId)
                    it.put("entryTime", Instant.ofEpochMilli(rec.entryMs).toString())
                },
                rec.id,
            )
            var n = 0
            rec.visits.filter(Visit::impression).forEach { v ->
                val zone = ids.zoneByFixture[v.fixture] ?: error("$slug: fixture ${v.fixture} has no zone id in the chain keys (shopper ${rec.id})")
                val firstLook = v.startMs
                val lastLook = v.startMs + ShopperAgent.APPROACH_MS + v.dwellMs
                val lastDwell = lastLook + ShopperAgent.DISENGAGE_MS
                n++
                out += Line(
                    lastDwell, RANK_IMPRESSION, seq++, "person_impression", "$base-i$n", "POST", IMPRESSIONS_PATH, "$base-b",
                    ConnectMappers.camel.createObjectNode().also {
                        it.put("trackingId", base)
                        it.put("siteId", ids.siteId)
                        it.put("spaceId", ids.spaceId)
                        it.put("zoneId", zone)
                        it.put("firstLook", Instant.ofEpochMilli(firstLook).toString())
                        it.put("lastLook", Instant.ofEpochMilli(lastLook).toString())
                        it.put("firstDwell", Instant.ofEpochMilli(firstLook).toString())
                        it.put("lastDwell", Instant.ofEpochMilli(lastDwell).toString())
                    },
                    rec.id,
                )
            }
            out += Line(
                rec.exitMs, RANK_CLOSE, seq++, "person_close", "$base-c", "PATCH", "$SESSIONS_PATH/{session_ref}", "$base-b",
                ConnectMappers.camel.createObjectNode().also { it.put("exitTime", Instant.ofEpochMilli(rec.exitMs).toString()) },
                rec.id,
            )
        }
        return out.sortedWith(compareBy({ it.dueMs }, { it.rank }, { it.seq }))
    }

    fun toJson(l: Line): ObjectNode {
        val n = ConnectMappers.snake.createObjectNode()
        n.put("due_utc", Instant.ofEpochMilli(l.dueMs).toString())
        n.put("kind", l.kind)
        n.put("id", l.id)
        n.put("method", l.method)
        n.put("path", l.path)
        l.sessionRef?.let { n.put("session_ref", it) }
        n.set<ObjectNode>("body", l.body)
        n.put("shopper", l.shopper)
        return n
    }

    fun write(file: Path, lines: List<Line>) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.newBufferedWriter(file).use { w ->
            lines.forEach { w.write(ConnectMappers.snake.writeValueAsString(toJson(it)) + "\n") }
        }
    }

    fun read(file: Path): List<Line> = Files.readAllLines(file).filter { it.isNotBlank() }.mapIndexed { i, s ->
        val n = ConnectMappers.snake.readTree(s)
        val kind = n.path("kind").asText()
        Line(
            Instant.parse(n.path("due_utc").asText()).toEpochMilli(),
            when (kind) {
                "person_begin" -> RANK_BEGIN
                "person_impression" -> RANK_IMPRESSION
                else -> RANK_CLOSE
            },
            i, kind, n.path("id").asText(), n.path("method").asText(), n.path("path").asText(),
            n.path("session_ref").asText().ifBlank { null }, n.path("body") as ObjectNode, n.path("shopper").asText(),
        )
    }

    /** The sidecar's place: `<out>/<slug>/<date>/people-rest.jsonl`, next to the day's wire plan and truth. */
    fun sidecar(outRoot: Path, slug: String, date: LocalDate): Path = outRoot.resolve(slug).resolve(date.toString()).resolve("people-rest.jsonl")
}
