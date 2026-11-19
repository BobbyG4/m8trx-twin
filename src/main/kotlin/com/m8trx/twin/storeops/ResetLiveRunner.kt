package com.m8trx.twin.storeops

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ReceiveItem
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a reset schedule in three kinds of lane at once:
 *  - up to [ResetGate.MAX_LANES] RECEIVE lanes; [lanesNow] says how many may be in flight and is read again on every loop, so the count
 *    can be changed during the run (the ramp 2 to 4) without a restart; each change is logged. Never more than 4 in flight;
 *  - one MOVE lane, a batch every [ResetLiveParams.moveSpacingMs]. A batch that contains any EPC received in THIS run waits until THAT
 *    tag is counted by a receive answer (not in `errors[]`); batches of units already on hand start at once, and a blocked batch does not
 *    hold up the ones behind it;
 *  - the TAIL (old directive end(s), then the new plan), only after every receive and move is accepted and, for items gated to the
 *    store's local midnight, not before it. It waits for that, honouring HOLD.
 *
 * Every send, in every lane: HOLD checked first (it stops the whole run cleanly: lanes finish the request in flight, nothing new
 * starts, the end is [End.HOLD]), the health gate answered ok in under [ResetLiveParams.healthMaxMs] (before each receive; move lane at
 * least every [ResetLiveParams.moveHealthEveryMs]), then the send, then [RetryPolicy]: 5xx / no answer back off, 429 waits the minute and
 * honours Retry-After, 10 failures in a row on one item halt the run. After a timeout or a 504 on a receive the SAME request waits at
 * least 60 s before it is re-sent (the server finishes the original anyway; the re-send is a safe no-op).
 *
 * Receive answers ([Outcome.ReceiveAnswer]), per `errors[]` code:
 *  - `internal_error` (the shared DB pool was briefly short): those tags are collected and re-sent later in a retry request of those tags
 *    only (a different body, id `<original>-retryN`, not before [ResetLiveParams.retryDelayMs]); the night goes on. STOP if more than 5
 *    tags in a row (request order) or more than 10 in one request are `internal_error`, or after [MAX_RETRY_DEPTH] retry rounds of a request;
 *  - every other code (`unknown_product`, `undecodable_tag`, `invalid_space`, `not_admitted`, anything new) STOPS the run, entries printed.
 * Only tags COUNTED (not in `errors[]`) count as accepted: they unblock moves and are booked. The original request is marked in the
 * sent-ledger only when its last tag is counted; a crash before that re-sends the whole request on resume, which is safe.
 *
 * Accepted items go to [books] (the box ledger) and then to the sent-ledger, under one lock.
 */
class ResetLiveRunner(
    items: List<SendItem>,
    private val sender: OpsSender,
    private val policy: RetryPolicy,
    private val ledger: SentLedger,
    private val books: ResetBooks?,
    private val params: ResetLiveParams,
    private val hold: () -> Boolean,
    private val clock: ResetClock = SystemResetClock,
    private val onEvent: (String) -> Unit = {},
    private val onProgress: (ResetProgress) -> Unit = {},
    private val lanesNow: () -> Int = { params.receiveLanes },
) {
    enum class End { DONE, HOLD, HALTED }

    class Result(
        val end: End,
        val haltReason: String?,
        val accepted: Int,
        val skippedByLedger: Int,
        val alerts: List<String>,
        val healthChecks: Int,
        val retryRequests: Int = 0,
    )

    private class Retry(val item: SendItem, val root: SendItem, val depth: Int, val notBeforeMs: Long)

    private val lock = Any()
    private val receivesAll = items.filter { it.kind == "receive" }
    private val movesAll = items.filter { it.kind == "move" }
    private val tail = items.filter { it.kind != "receive" && it.kind != "move" }
    private val receives = ArrayDeque(receivesAll.filter { !ledger.isSent(it.extId) })
    private val retries = ArrayList<Retry>()
    private val moves = ArrayList(movesAll.filter { !ledger.isSent(it.extId) })

    /** Tags of this run's receives not yet counted by a receive answer: a move holding any of them waits. */
    private val pending = HashSet<String>()

    /** Per root request: the tags still to be counted. */
    private val outstanding = HashMap<String, MutableSet<String>>()
    private val healthOkAt = ConcurrentHashMap<String, Long>()
    private var activeReceives = 0
    private var accepted = 0
    private var healthChecks = 0
    private var retryRequests = 0
    private var movesAcceptedNow = 0
    private var tagsCounted = 0
    private var tagsCreated = 0
    private var lastProgressAt = Long.MIN_VALUE / 2
    private val alerts = ArrayList<String>()

    @Volatile
    private var lastHealthMs = -1L

    @Volatile
    private var stopped = false
    private var held = false
    private var haltReason: String? = null
    private var lastLanes = -1
    private val skipped = items.count { ledger.isSent(it.extId) }
    private val startedAt = clock.now()
    private val totalTags = receivesAll.sumOf { it.epcs.size }
    private val tagsAtStart = receivesAll.filter { ledger.isSent(it.extId) }.sumOf { it.epcs.size }

    init {
        require(items.map { it.extId }.toSet().size == items.size) { "the schedule has duplicate ext ids" }
        receivesAll.filter { !ledger.isSent(it.extId) }.forEach { r ->
            pending += r.epcs
            outstanding[r.extId] = r.epcs.toMutableSet()
        }
    }

    fun run(): Result {
        event(
            "start: ${receives.size} receive request(s), ${moves.size} move batch(es), ${tail.count {
                !ledger.isSent(it.extId)
            }} directive step(s) to go; " +
                "$skipped already accepted (skipped); receive lanes ${lanes()} (max ${ResetGate.MAX_LANES}, changeable in the run)",
        )
        val threads = ArrayList<Thread>()
        repeat(ResetGate.MAX_LANES) { i -> threads += Thread({ guarded { receiveLane(i) } }, "reset-receive-$i") }
        threads += Thread({ guarded { moveLane() } }, "reset-move")
        val monitor = Thread({ monitor() }, "reset-status").also { it.isDaemon = true }
        monitor.start()
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        if (!stopped) guarded { tailLane() }
        val end = synchronized(lock) {
            when {
                haltReason != null -> End.HALTED
                held -> End.HOLD
                else -> End.DONE
            }
        }
        done = true
        progress(end.name, force = true)
        event(statusLine())
        return Result(end, haltReason, accepted, skipped, alerts.toList(), healthChecks, retryRequests)
    }

    @Volatile
    private var done = false

    private fun event(s: String) = onEvent(s)

    /** The lane count in force: [lanesNow] clamped to 1..[ResetGate.MAX_LANES]; a change is logged. */
    private fun lanes(): Int {
        val n = lanesNow().coerceIn(1, ResetGate.MAX_LANES)
        synchronized(lock) {
            if (n != lastLanes) {
                if (lastLanes != -1) event("receive lanes changed: $lastLanes -> $n")
                lastLanes = n
            }
        }
        return n
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            halt("lane crashed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun halt(reason: String) {
        synchronized(lock) {
            if (haltReason == null) haltReason = reason
            stopped = true
        }
        event("HALT $reason")
    }

    /** True when the run must stop: a halt elsewhere, or HOLD set now. */
    private fun checkStop(): Boolean {
        if (stopped) return true
        if (hold()) {
            synchronized(lock) {
                if (!held && haltReason == null) event("HOLD is set: finishing the requests in flight, starting nothing new")
                held = true
                stopped = true
            }
            return true
        }
        return false
    }

    /** Sleeps [ms] in slices so a HOLD or a halt is noticed within a second. */
    private fun nap(ms: Long) {
        var left = ms
        while (left > 0 && !checkStop()) {
            val slice = minOf(left, 1_000L)
            clock.sleep(slice)
            left -= slice
        }
    }

    private fun receiveWorkLeft(): Boolean = receives.isNotEmpty() || retries.isNotEmpty() || activeReceives > 0

    private fun receiveLane(index: Int) {
        while (true) {
            if (checkStop()) return
            if (index >= lanes()) {
                // this lane is not in use at the moment (the count may be raised again); it ends only when the work is over
                if (!synchronized(lock) { receiveWorkLeft() }) return
                nap(params.idlePollMs)
                continue
            }
            var left = false
            val next = synchronized(lock) {
                val r = receives.removeFirstOrNull()?.let { Retry(it, it, 0, 0) }
                    ?: retries.firstOrNull { it.notBeforeMs <= clock.now() }?.also { retries.remove(it) }
                if (r != null) activeReceives++
                left = receiveWorkLeft()
                r
            }
            if (next == null) {
                if (!left) return
                nap(params.idlePollMs)
                continue
            }
            try {
                if (!deliverReceive(next)) return
            } finally {
                synchronized(lock) { activeReceives-- }
            }
        }
    }

    private fun moveLane() {
        var last = Long.MIN_VALUE / 2
        while (true) {
            if (checkStop()) return
            var done = false
            var deadlock = false
            val item = synchronized(lock) {
                if (moves.isEmpty()) {
                    done = true
                    null
                } else {
                    val i = moves.indexOfFirst { m -> m.epcs.none { it in pending } }
                    if (i >= 0) {
                        moves.removeAt(i)
                    } else {
                        deadlock = !receiveWorkLeft()
                        null
                    }
                }
            }
            if (done) return
            if (deadlock) {
                if (!checkStop()) halt("${moves.size} move batch(es) wait for tags that are neither queued nor in flight (schedule inconsistent)")
                return
            }
            if (item == null) {
                nap(params.idlePollMs)
                continue
            }
            val gap = last + params.moveSpacingMs - clock.now()
            if (gap > 0) nap(gap)
            if (checkStop()) return
            if (!deliver(item)) return
            last = clock.now()
        }
    }

    private fun tailLane() {
        var last = Long.MIN_VALUE / 2
        var waitedLogged = Long.MIN_VALUE / 2
        for (item in tail) {
            if (synchronized(lock) { ledger.isSent(item.extId) }) continue
            while (item.afterLocalMidnight && clock.now() < params.midnightMs) {
                if (checkStop()) return
                if (clock.now() - waitedLogged >= 600_000) {
                    waitedLogged = clock.now()
                    event(
                        "all receives and moves are accepted; waiting for the store's local midnight " +
                            "(${Instant.ofEpochMilli(params.midnightMs)}) before ${item.extId}: ${(params.midnightMs - clock.now()) / 60_000} min",
                    )
                }
                nap(minOf(30_000L, params.midnightMs - clock.now()))
            }
            val gap = last + params.directiveSpacingMs - clock.now()
            if (gap > 0) nap(gap)
            if (checkStop()) return
            if (!deliver(item)) return
            last = clock.now()
        }
    }

    private fun healthReady(item: SendItem, attempt: Int): Boolean {
        val maxAge = if (item.kind == "move") params.moveHealthEveryMs else 0L
        val due = attempt > 1 || maxAge == 0L || clock.now() - (healthOkAt[item.kind] ?: Long.MIN_VALUE / 2) >= maxAge
        if (!due) return true
        val t0 = System.nanoTime()
        val (status, body) = sender.health()
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        lastHealthMs = tookMs
        synchronized(lock) { healthChecks++ }
        if (!Health.isReady(status, body) || tookMs > params.healthMaxMs) {
            event("health not ready ($status, $tookMs ms): waiting, ${item.extId} stays due")
            nap(params.healthPollMs)
            return false
        }
        healthOkAt[item.kind] = clock.now()
        return true
    }

    /** Sends a non-receive [item] until accepted (true) or the run stops (false). */
    private fun deliver(item: SendItem): Boolean {
        var attempt = 1
        while (true) {
            if (checkStop()) return false
            if (!healthReady(item, attempt)) continue
            val outcome = sender.send(item)
            when (val d = policy.decide(outcome, attempt)) {
                Decision.Done -> {
                    accept(item)
                    return true
                }

                is Decision.Wait -> {
                    waitAfterFailure(item, d, attempt)
                    attempt++
                }

                is Decision.Halt -> {
                    halt("${item.extId}: ${d.reason}")
                    return false
                }
            }
        }
    }

    private fun waitAfterFailure(item: SendItem, d: Decision.Wait, attempt: Int, minMs: Long = 0) {
        if (d.dropToken) sender.dropToken()
        if (d.alert) synchronized(lock) { alerts += "${item.extId}: ${d.reason} (attempt $attempt)" }
        val ms = maxOf(d.ms, minMs)
        event("${item.extId} not sent: ${d.reason}; again in $ms ms (attempt $attempt)")
        nap(ms)
    }

    /** Sends a receive request (an original or a retry); true when it is settled (counted, or its failures queued for a retry round). */
    private fun deliverReceive(r: Retry): Boolean {
        val item = r.item
        var attempt = 1
        while (true) {
            if (checkStop()) return false
            if (!healthReady(item, attempt)) continue
            val outcome = sender.send(item)
            when {
                outcome is Outcome.ReceiveAnswer -> return settle(r, outcome)

                outcome is Outcome.ItemErrors -> {
                    event("${item.extId} REFUSED (${outcome.errors.size}):")
                    outcome.entries.forEach { event("  $it") }
                    halt("${item.extId}: ${outcome.entries.take(5).joinToString("; ")}")
                    return false
                }

                else -> when (val d = policy.decide(outcome, attempt)) {
                    Decision.Done -> return settle(r, Outcome.ReceiveAnswer(item.epcs.size, 0, emptyList()))

                    is Decision.Wait -> {
                        // a timeout or a 504: the server finishes the original anyway, so the same request waits at least 60 s
                        val slow = outcome is Outcome.Unreachable || (outcome is Outcome.Http && outcome.status == 504)
                        waitAfterFailure(item, d, attempt, if (slow) MIN_RESEND_AFTER_TIMEOUT_MS else 0)
                        attempt++
                    }

                    is Decision.Halt -> {
                        halt("${item.extId}: ${d.reason}")
                        return false
                    }
                }
            }
        }
    }

    private fun settle(r: Retry, a: Outcome.ReceiveAnswer): Boolean {
        val item = r.item
        val bad = a.errors.mapNotNull { it.epc }.toSet()
        val stopErrors = a.errors.filter { it.code != "internal_error" || it.epc == null }
        if (stopErrors.isNotEmpty()) {
            event("${item.extId} REFUSED ${a.errors.size} tag(s) (received ${a.received}, created ${a.created}):")
            a.errors.forEach { event("  $it") }
            halt("${item.extId}: ${stopErrors.take(5).joinToString("; ")}")
            return false
        }
        if (a.errors.isNotEmpty()) {
            val run = longestRun(item.epcs, bad)
            event("${item.extId}: ${a.errors.size} internal_error tag(s) (longest run $run); the pool was briefly short, they are re-sent later")
            if (a.errors.size > MAX_INTERNAL_IN_REQUEST || run > MAX_INTERNAL_IN_A_ROW) {
                a.errors.forEach { event("  $it") }
                halt(
                    "${item.extId}: internal_error on ${a.errors.size} tag(s), $run in a row (limits $MAX_INTERNAL_IN_REQUEST per request, $MAX_INTERNAL_IN_A_ROW in a row)",
                )
                return false
            }
            if (r.depth >= MAX_RETRY_DEPTH) {
                halt("${item.extId}: still internal_error on ${bad.size} tag(s) after $MAX_RETRY_DEPTH retry rounds")
                return false
            }
        }
        val counted = item.epcs.filter { it !in bad }
        synchronized(lock) {
            // the counted tags are booked and unblock their moves
            if (counted.isNotEmpty()) {
                books?.record(SendItem(item.seq, item.dueMs, item.rank, "receive", item.extId, item.payload, counted))
                pending.removeAll(counted.toSet())
                tagsCounted += counted.size
                tagsCreated += a.created
            }
            val left = outstanding[r.root.extId]
            left?.removeAll(counted.toSet())
            if (bad.isNotEmpty()) {
                retryRequests++
                val n = r.depth + 1
                val id = "${r.root.extId}-retry$n"
                val payload = ConnectMappers.camel.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                    ItemReceiveRequest("@site", "@backroom_space", item.epcs.filter { it in bad }.map { ReceiveItem(it) }),
                )
                val retryItem = SendItem(item.seq, item.dueMs, item.rank, "receive", id, payload, item.epcs.filter { it in bad })
                retries += Retry(retryItem, r.root, n, clock.now() + params.retryDelayMs)
            }
            if (left != null && left.isEmpty()) {
                ledger.markSent(r.root)
                outstanding.remove(r.root.extId)
                accepted++
            }
            progress("RUNNING", force = false)
        }
        return true
    }

    private fun longestRun(epcs: List<String>, bad: Set<String>): Int {
        var best = 0
        var cur = 0
        epcs.forEach { if (it in bad) best = maxOf(best, ++cur) else cur = 0 }
        return best
    }

    private fun accept(item: SendItem) {
        synchronized(lock) {
            if (item.kind == "move") movesAcceptedNow++
            books?.record(item)
            ledger.markSent(item)
            accepted++
            progress("RUNNING", force = false)
        }
    }

    private fun progress(state: String, force: Boolean) {
        val now = clock.now()
        if (!force && now - lastProgressAt < params.progressEveryMs) return
        lastProgressAt = now
        val rDone = receivesAll.filter { ledger.isSent(it.extId) }
        onProgress(
            ResetProgress(
                rDone.size,
                receivesAll.size,
                rDone.sumOf { it.epcs.size },
                totalTags,
                movesAll.count { ledger.isSent(it.extId) },
                movesAll.size,
                tail.count { ledger.isSent(it.extId) },
                tail.size,
                state,
                now,
            ),
        )
    }

    /** One status line, logged once a minute: receives in flight, tags received and created, move batches sent, health latency, ETA. */
    fun statusLine(): String = synchronized(lock) {
        val elapsedS = maxOf(1L, (clock.now() - startedAt) / 1000)
        val tagsLeft = pending.size
        val movesSent = movesAll.count { ledger.isSent(it.extId) }
        val movesLeft = moves.size
        val tagRate = tagsCounted.toDouble() / elapsedS
        val moveRate = movesAcceptedNow.toDouble() / elapsedS
        val etaReceive = if (tagsLeft == 0) {
            0.0
        } else if (tagRate > 0) {
            tagsLeft / tagRate
        } else {
            Double.NaN
        }
        val etaMove = if (movesLeft == 0) {
            0.0
        } else if (moveRate > 0) {
            movesLeft / moveRate
        } else {
            Double.NaN
        }
        val eta = if (etaReceive.isNaN() || etaMove.isNaN()) "n/a" else fmtEta((maxOf(etaReceive, etaMove)).toLong())
        "STATUS receives in flight $activeReceives (lanes $lastLanes) | tags received ${tagsAtStart + tagsCounted} created $tagsCreated " +
            "of $totalTags (retry requests $retryRequests) | move batches sent $movesSent of ${movesAll.size} | " +
            "health ${if (lastHealthMs < 0) "n/a" else "$lastHealthMs ms"} | ETA $eta"
    }

    private fun fmtEta(s: Long) = "%d h %02d min".format(s / 3600, s % 3600 / 60)

    private fun monitor() {
        var next = clock.now() + params.statusEveryMs
        while (!done) {
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                return
            }
            if (clock.now() >= next) {
                next = clock.now() + params.statusEveryMs
                event(statusLine())
            }
        }
    }

    companion object {
        const val MIN_RESEND_AFTER_TIMEOUT_MS = 60_000L
        const val MAX_INTERNAL_IN_A_ROW = 5
        const val MAX_INTERNAL_IN_REQUEST = 10
        const val MAX_RETRY_DEPTH = 5
    }
}
