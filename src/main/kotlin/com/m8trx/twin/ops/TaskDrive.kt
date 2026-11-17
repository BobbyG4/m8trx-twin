package com.m8trx.twin.ops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * `./gradlew connectTaskDay` — the `tasks` step ([TaskDay] plans it). Through the task surface only, as the store's
 * own people (the lifecycle verbs are user actions: a service key is `401 User context required` by design):
 *
 *  - read: Hasura `task` (the site's open work, READ only) and `GET /api/v2/tasks/assignees?siteId=` (who may take it)
 *  - `POST /api/v2/tasks/{id}/cancel {reason}` — the pre-Oct-5 backlog (`task:manage`)
 *  - `POST /api/v2/tasks/{id}/reassign {assigned_to}` — today's rule-fired tasks (`task:assign`)
 *  - `POST /api/v2/tasks {site_id, zone_id, type, title, priority, due_at, assigned_to, metadata}` — the top-up from the
 *    walls that sold down (`task:create`)
 *  - `POST /api/v2/tasks/{id}/start` then `/complete` — as the ASSIGNEE when their login works (users.csv by name, the
 *    same shared demo password), else as the manager (`task:complete`; Floor Associate and staff kits hold it)
 *
 * SAFE BY DEFAULT — dry run (plans from the ledger and the sales plan only; no login, no read, no send).
 * `M8TRX_TASKS_LIVE=true` sends. Env: [OpsConfig] (prefix `M8TRX_TASKS`) plus `M8TRX_TASKS_BACKLOG_BEFORE` (2026-10-05).
 * Ledger `.twin-state/tasks/tasks-<yyyymmdd>-<seed>.csv` (`action,id,user,slug`) — a done action is never repeated, the
 * day's target counts what earlier passes of the date already assigned or created (so the step may run every hour), and
 * the next date reads it for the carried completions; `tasks/backlog-<slug>.done` marks the one-time backlog close.
 */
class TaskDrive(private val cfg: OpsConfig, private val http: ConnectHttp = ConnectHttp()) {
    private val log = LoggerFactory.getLogger(TaskDrive::class.java)
    private val ops = OpsClient(cfg.apiBase, cfg.hasuraUrl, cfg::requirePassword, http, cfg.stateDir)
    private val dir = cfg.stateDir.resolve("tasks")

    data class Summary(
        val cancelled: Int,
        val assigned: Int,
        val created: Int,
        val completed: Int,
        val held: Int,
        val err: Int,
        val shapes: List<String>,
    )

    private fun ledgerFile(d: LocalDate) = dir.resolve("tasks-${d.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}-${cfg.seed}.csv")

    data class Entry(val action: String, val id: String, val user: String, val slug: String)

    /** `action,id,user,slug` lines of a date's ledger. */
    private fun ledger(d: LocalDate): List<Entry> = StoreStock.readLines(ledgerFile(d)).map {
        val c = it.split(",")
        Entry(c[0], c[1], c.getOrElse(2) { "" }, c.getOrElse(3) { "" })
    }

    /** Units sold off each wall of [slug] on the date (the sent part of the dated sales plan), by fixture code. */
    fun soldDown(slug: String): Map<String, Int> {
        val plan = RestockMoves.planFile(cfg.stateDir.resolve("stream"), cfg.date, cfg.seed)
        if (!Files.exists(plan)) return emptyMap()
        val sent = RestockMoves.readLedger(plan.resolveSibling(plan.fileName.toString().removeSuffix(".plan.csv") + ".sent"))
        val placements = RestockMoves.readPlacements(cfg.stateDir.resolve("moves").resolve("placements.csv"))
        val home = DatedStream.loadEpcRows(cfg.chainDir.resolve("stores/$slug/epcs.csv")).associate { it.epc to it.fixture }
        val out = HashMap<String, Int>()
        DatedStream.readPlan(plan).filter { it.store == slug && it.saleId in sent }.flatMap { it.epcs }.forEach { e ->
            val f = placements[e] ?: home[e] ?: return@forEach
            if (!StoreStock.isBoh(f)) out.merge(f, 1, Int::plus)
        }
        return out
    }

    fun run(): Summary {
        val backlogBefore = LocalDate.parse(System.getenv("M8TRX_TASKS_BACKLOG_BEFORE") ?: "2026-10-05")
        var cancelled = 0
        var assigned = 0
        var created = 0
        var completed = 0
        var held = 0
        var err = 0
        val shapes = ArrayList<String>()
        val today = ledger(cfg.date)
        val doneIds = today.map { it.action + ":" + it.id }.toSet()
        val yesterday = ledger(cfg.date.minusDays(1))
        val carriedAll = yesterday.filter { it.action == "assign" || it.action == "create" }.map { it.id }.toSet() -
            yesterday.filter { it.action == "complete" }.map { it.id }.toSet() - today.filter { it.action == "complete" }.map { it.id }.toSet()
        for (slug in cfg.stores) {
            val store = cfg.keys.store(slug)
            val siteId = cfg.siteIds[slug] ?: "<site>"
            val zoneOf = store.fixtures.associate { it.code to it.zoneId }
            val close = ZonedDateTime.of(cfg.date, LocalTime.of(21, 0), store.zone).toInstant()
            val sold = soldDown(slug)
            if (!cfg.live) {
                val p = TaskDay.plan(
                    slug = slug,
                    date = cfg.date,
                    zone = store.zone,
                    seed = cfg.seed,
                    open = emptyList(),
                    people = DRY_PEOPLE,
                    manager = DRY_PEOPLE[0],
                    soldDown = sold,
                    zoneOf = zoneOf,
                    carried = emptyList(),
                    backlogBefore = backlogBefore,
                    backlogDone = true,
                    closeLocal = close,
                )
                log.info(
                    "  [DRY] {} target={} create={} (from {} sold-down walls) completeToday={}",
                    slug,
                    p.target,
                    p.create.size,
                    sold.size,
                    p.completeToday.size,
                )
                p.create.firstOrNull()?.let { (c, who) ->
                    if (shapes.isEmpty()) shapes += shapesFor(siteId, c, who)
                }
                created += p.create.size
                continue
            }
            // a store that fails (login, a refused read, a dropped connection) fails alone; the pass goes on to the next store
            try {
                val refusal = cfg.pastRefusal(slug)
                if (refusal != null) {
                    log.warn("  {} HELD — {}", slug, refusal)
                    held++
                    continue
                }
                val email = cfg.actorEmail(slug)
                val jwt = ops.jwt(email)
                if (jwt == null) {
                    log.error("  {} login refused for {}", slug, email)
                    err++
                    continue
                }
                val open = readOpen(jwt, siteId)
                val people = readPeople(jwt, siteId)
                val managerName = cfg.staff.rows.firstOrNull { it.email == email }?.name
                val manager = people.firstOrNull { it.name.equals(managerName, true) }
                val carried = open.filter { it.id in carriedAll }.map { it.id }
                val backlogFlag = dir.resolve("backlog-$slug.done")
                // the day's target is a daily total: what earlier passes of this date already assigned or created counts against it
                val already = today.count { it.slug == slug && (it.action == "assign" || it.action == "create") }
                val p = TaskDay.plan(
                    slug, cfg.date, store.zone, cfg.seed, open, people, manager, sold, zoneOf, carried, backlogBefore,
                    Files.exists(
                        backlogFlag,
                    ),
                    close, already,
                )
                log.info(
                    "  [LIVE] {} open={} people={} backlog={} assign={} create={} completeToday={} carried={}",
                    slug,
                    open.size,
                    people.size,
                    p.cancel.size,
                    p.assign.size,
                    p.create.size,
                    p.completeToday.size,
                    p.completeCarried.size,
                )
                fun record(action: String, id: String, user: String) = StoreStock.appendLines(ledgerFile(cfg.date), listOf("$action,$id,$user,$slug"))

                // the list goes into the run's output BEFORE the first cancel (a cancel is terminal; undoing one is a DB write)
                p.cancel.forEach {
                    log.info("    SIM-CANCEL-PLAN {} {} status={} created={} \"{}\"", slug, it.id, it.status, it.createdAt, it.title.take(100))
                }
                var cancelFailed = false
                for (t in p.cancel) {
                    if ("cancel:${t.id}" in doneIds) continue
                    val r = ops.send(jwt, "POST", "/tasks/${t.id}/cancel", mapOf("reason" to TaskDay.CANCEL_REASON))
                    if (r is ConnectResponse.Ok) {
                        cancelled++
                        record("cancel", t.id, email)
                        log.info("    SIM-CANCELLED {} {} status={} created={}", slug, t.id, t.status, t.createdAt)
                        StoreStock.appendLines(
                            dir.resolve("cancelled.csv"),
                            listOf("${cfg.now},$slug,${t.id},${t.status},${t.createdAt},${t.title.replace(',', ';').replace('\n', ' ').take(100)}"),
                        )
                    } else {
                        err++
                        cancelFailed = true
                        log.error("    cancel {} refused: {} — the backlog stays open for the next pass", t.id, ops.describe(r))
                    }
                }
                // the one-time backlog close is marked only when every cancel went through; a failed cancel is retried next pass
                if (!cancelFailed && !Files.exists(backlogFlag)) StoreStock.appendLines(backlogFlag, listOf("${cfg.now}"))

                val toComplete = HashMap<String, TaskDay.Person>()
                for ((t, who) in p.assign) {
                    if ("assign:${t.id}" in doneIds) continue
                    val r = ops.send(jwt, "POST", "/tasks/${t.id}/reassign", mapOf("assigned_to" to who.userId))
                    if (r is ConnectResponse.Ok) {
                        assigned++
                        record("assign", t.id, who.userId)
                        if (t.id in p.completeToday) toComplete[t.id] = who
                    } else {
                        err++
                        log.error("    reassign {} refused: {}", t.id, ops.describe(r))
                    }
                }
                for ((c, who) in p.create) {
                    if (today.any { it.action == "create" && it.user.endsWith("|${c.key}") }) continue
                    val r = ops.send(jwt, "POST", "/tasks", createBody(siteId, store.salesFloorSpaceId, c, who))
                    val id = if (r is ConnectResponse.Ok) ops.json(r).path("task_id").asText("") else ""
                    if (id.isNotBlank()) {
                        created++
                        record("create", id, "${who.userId}|${c.key}")
                        if (c.key in p.completeToday) toComplete[id] = who
                    } else {
                        err++
                        log.error("    create {} refused: {}", c.key, ops.describe(r))
                    }
                }
                for (id in p.completeCarried) {
                    val who = people.firstOrNull { pp -> yesterday.any { it.id == id && it.user.startsWith(pp.userId) } }
                    if (who != null) toComplete[id] = who
                }
                for ((id, who) in toComplete) {
                    if ("complete:$id" in doneIds) continue
                    val asEmail = cfg.staff.emailByName(slug, who.name)
                    val asJwt = asEmail?.let { ops.jwt(it) } ?: jwt
                    ops.send(asJwt, "POST", "/tasks/$id/start")
                    val r = ops.send(asJwt, "POST", "/tasks/$id/complete", emptyMap())
                    if (r is ConnectResponse.Ok) {
                        completed++
                        record("complete", id, asEmail ?: email)
                    } else {
                        err++
                        log.error("    complete {} refused: {}", id, ops.describe(r))
                    }
                }
            } catch (e: Exception) {
                err++
                log.error("  {} FAILED — {}: {} (the pass goes on to the next store)", slug, e.javaClass.simpleName, e.message)
            }
        }
        log.info(
            "SIM-RUN tasks date={} seed={} cancelled={} assigned={} created={} completed={} held={} sent_err={}",
            cfg.date,
            cfg.seed,
            cancelled,
            assigned,
            created,
            completed,
            held,
            err,
        )
        return Summary(cancelled, assigned, created, completed, held, err, shapes)
    }

    private fun readOpen(jwt: String, siteId: String): List<TaskDay.Existing> = ops.query(jwt, OPEN_GQL, mapOf("site" to siteId)).path("task").map {
        TaskDay.Existing(
            it.path("id").asText(),
            it.path("type").asText(),
            it.path("status").asText(),
            it.path("title").asText(),
            it.path("zone_id").asText("").ifBlank { null },
            it.path("assigned_to").asText("").ifBlank { null },
            parseTs(it.path("created_at").asText()),
        )
    }

    private fun readPeople(jwt: String, siteId: String): List<TaskDay.Person> {
        val r = ops.send(jwt, "GET", "/tasks/assignees?siteId=$siteId")
        if (r !is ConnectResponse.Ok) error("assignees refused: ${ops.describe(r)}")
        return ops.json(r).path("people").map { p ->
            TaskDay.Person(p.path("userId").asText(), p.path("name").asText(), p.path("roles").map { it.asText() })
        }
    }

    private fun shapesFor(siteId: String, c: TaskDay.Create, who: TaskDay.Person): List<String> {
        val m = com.m8trx.twin.connect.model.ConnectMappers.camel
        return listOf(
            "GraphQL ${OPEN_GQL.substringBefore("{ task(")}… task(where: site_id = <site>, status in open/assigned/overdue/in_progress)",
            "GET /tasks/assignees?siteId=$siteId",
            "POST /tasks/<id>/cancel ${m.writeValueAsString(mapOf("reason" to TaskDay.CANCEL_REASON))}",
            "POST /tasks/<id>/reassign {\"assigned_to\":\"<userId>\"}",
            "POST /tasks ${m.writeValueAsString(createBody(siteId, "<space>", c, who))}",
            "POST /tasks/<id>/start · POST /tasks/<id>/complete {}",
        )
    }

    companion object {
        /** Hasura `timestamptz` (`2026-10-01T03:04:05.123+00:00`) or a bare ISO instant. */
        fun parseTs(s: String): Instant = runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrElse { Instant.parse(s) }

        const val OPEN_GQL = "query TwinOpenTasks(\$site: uuid!) { task(where: {site_id: {_eq: \$site}, " +
            "status: {_in: [\"open\", \"assigned\", \"overdue\", \"in_progress\"]}}, order_by: {created_at: asc}, limit: 2000) " +
            "{ id type status title zone_id assigned_to created_at } }"

        val DRY_PEOPLE = listOf(
            TaskDay.Person("00000000-0000-0000-0000-00000000000a", "Store Manager (dry)", listOf("site-manager")),
            TaskDay.Person("00000000-0000-0000-0000-00000000000b", "Floor Associate (dry)", listOf("Floor Associate")),
        )

        fun createBody(siteId: String, spaceId: String, c: TaskDay.Create, who: TaskDay.Person): Map<String, Any?> = mapOf(
            "site_id" to siteId,
            "space_id" to spaceId,
            "zone_id" to c.zoneId,
            "type" to c.type,
            "title" to c.title,
            "priority" to c.priority,
            "due_at" to c.dueAt.toString(),
            "assigned_to" to who.userId,
            "metadata" to c.metadata,
        )
    }
}

fun main() {
    val cfg = OpsConfig.fromEnv("M8TRX_TASKS")
    val log = LoggerFactory.getLogger("com.m8trx.twin.ops.TaskDrive")
    log.info("Task day → date={} seed={} stores={} live={}", cfg.date, cfg.seed, cfg.stores.size, cfg.live)
    val s = TaskDrive(cfg).run()
    s.shapes.forEach { log.info("  shape: {}", it) }
    if (cfg.live && s.err > 0) error("a task call was refused — see the log")
}
