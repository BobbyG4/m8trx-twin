package com.m8trx.twin.ops

import com.m8trx.twin.history.HistoryCsv
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * The `tasks` step's generator — pure and deterministic in (seed, store, date, what the platform and the ledger hold).
 *
 * Plan § 2 says the recent window's tasks are **rule-fired from drift** (core's rule engine already makes them from the
 * compliance events the sales and restock moves cause). What was missing is people working them (audit gap #11: 1 task
 * assigned chain-wide, nothing created Oct 5–10, 55 `overdue` from Oct 1–4). Per store per day:
 *
 *  1. **Backlog** — every task still open/assigned/overdue/in_progress that was created before [backlogBefore] is
 *     cancelled with a reason (once; the ledger keeps it from repeating).
 *  2. **Today's drift work** — N = 3–5 tasks: the day's own open, unassigned rule-fired tasks first (oldest first); if the
 *     rule engine made fewer than N today, the twin creates the rest from the walls that sold down most that day
 *     (`replenishment_request`, the fixture's zone, "Refill <code> — <n> units sold today"), the same drift the rule
 *     engine reacts to.
 *  3. **Assignment** — a third to the store manager (the login), the rest to floor staff from the site's own assignee
 *     list (`GET /tasks/assignees`), drawn by the seed.
 *  4. **Completion** — 40–60 % of today's assigned tasks are completed the same day; 70 % of what the twin assigned
 *     YESTERDAY and did not complete is completed today (the "next day" share). The rest stays open: a real queue.
 */
object TaskDay {
    data class Existing(
        val id: String,
        val type: String,
        val status: String,
        val title: String,
        val zoneId: String?,
        val assignedTo: String?,
        val createdAt: Instant,
    )

    data class Person(val userId: String, val name: String, val roles: List<String>)

    data class Create(
        val key: String,
        val type: String,
        val title: String,
        val zoneId: String?,
        val priority: String,
        val dueAt: Instant,
        val metadata: Map<String, Any?>,
    )

    data class Plan(
        val cancel: List<Existing>,
        val assign: List<Pair<Existing, Person>>,
        val create: List<Pair<Create, Person>>,
        /** today's ids (existing or created keys) to complete, by key → assignee */
        val completeToday: Set<String>,
        /** yesterday's assigned-and-open task ids to complete now */
        val completeCarried: List<String>,
        val target: Int,
    )

    const val CANCEL_REASON = "Backlog review: the drift this task reported was restocked and re-evaluated since; closed by the store manager."

    fun isFloor(p: Person) = p.roles.any { r -> r.contains("floor", true) || r.equals("staff", true) || r.contains("associate", true) }

    fun isManager(p: Person) = p.roles.any { r -> r.contains("site-manager", true) || r.contains("manager", true) }

    /**
     * [open] = the site's open/assigned/overdue/in_progress tasks (platform read). [soldDown] = fixture code → units sold off
     * that wall on [date] (twin's sales ledger), [zoneOf] = fixture code → zone id. [carried] = yesterday's ledger:
     * task ids the twin assigned and did not complete. [manager] = the login's own person row, if the platform lists it.
     */
    fun plan(
        slug: String,
        date: LocalDate,
        zone: ZoneId,
        seed: Long,
        open: List<Existing>,
        people: List<Person>,
        manager: Person?,
        soldDown: Map<String, Int>,
        zoneOf: Map<String, String>,
        carried: List<String>,
        backlogBefore: LocalDate,
        backlogDone: Boolean,
        closeLocal: Instant,
        alreadyToday: Int = 0,
    ): Plan {
        val rng = Random(HistoryCsv.derive(seed, slug, date, "tasks"))
        val backlogCut = backlogBefore.atStartOfDay(zone).toInstant()
        val cancel = if (backlogDone) emptyList() else open.filter { it.createdAt.isBefore(backlogCut) }.sortedBy { it.createdAt }
        val dayFrom = date.atStartOfDay(zone).toInstant()
        val dayTo = date.plusDays(1).atStartOfDay(zone).toInstant()
        val target = 3 + rng.nextInt(3)
        val left = (target - alreadyToday).coerceAtLeast(0)
        val todays = open.filter { it.assignedTo == null && !it.createdAt.isBefore(dayFrom) && it.createdAt.isBefore(dayTo) && it.status == "open" }
            .sortedBy { it.createdAt }
            .take(left)
        val floor = people.filter { isFloor(it) && it.userId != manager?.userId }.sortedBy { it.userId }
        fun assignee(): Person? = when {
            manager != null && (floor.isEmpty() || rng.nextInt(3) == 0) -> manager
            floor.isNotEmpty() -> floor[rng.nextInt(floor.size)]
            else -> null
        }
        val assign = todays.mapNotNull { t -> assignee()?.let { t to it } }
        val need = left - todays.size
        val create = soldDown.entries.filter { it.key in zoneOf }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(need.coerceAtLeast(0))
            .mapNotNull { (code, n) ->
                val who = assignee() ?: return@mapNotNull null
                Create(
                    key = "TK-$slug-$date-$code",
                    type = "replenishment_request",
                    title = "Refill $code — $n units sold today",
                    zoneId = zoneOf[code],
                    priority = if (n >= 6) "high" else "normal",
                    dueAt = closeLocal,
                    metadata = mapOf("source" to "twin-drift", "fixture_code" to code, "units_sold" to n, "date" to date.toString()),
                ) to who
            }
        val keys = assign.map { it.first.id } + create.map { it.first.key }
        val share = 0.4 + rng.nextDouble() * 0.2
        val completeToday = keys.shuffled(rng).take(Math.round(keys.size * share).toInt()).toSet()
        val completeCarried = carried.sorted().shuffled(rng).take(Math.round(carried.size * 0.7).toInt())
        return Plan(cancel, assign, create, completeToday, completeCarried, target)
    }
}
