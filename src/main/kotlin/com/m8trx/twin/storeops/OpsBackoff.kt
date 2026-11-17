package com.m8trx.twin.storeops

/**
 * The retry / backoff rules of the store-ops senders — PURE (no clock, no sleep, no socket), so `storeOpsSelfTest` can prove
 * every rule. From the Backend/Web lane's answer A11 (plan § 8), 2026-11-17:
 *
 *  - **Health gate.** `GET https://dev.m8trx.com/server/api/health` (no key). Ready = HTTP 200 **and** `"status":"ok"` in the body; a
 *    200 with `"status":"degraded"` is NOT ready, and a deploy restart shows as a 502 from the front door (about 30 s).
 *    On a 502 or a connection error: wait for the gate to say ok, then go on. There is no deploy window.
 *  - **429:** wait a full minute, and longer when `Retry-After` says so (the login limit is 10 a minute per client IP, counted
 *    before the password is checked; the people lane answers `Retry-After: 5`).
 *  - **Logins:** one every 7 s or more, each token kept for its lifetime (an hour) rather than logging in on every pass.
 *  - **A failure leaves the event due.** Nothing is skipped: a failed send keeps its place at the head of its lane, and the only
 *    ways off the queue are an accepted send or a halt that stops the whole lane (loud, the event still due).
 */
object Health {
    const val URL = "https://dev.m8trx.com/server/api/health"
    private val OK = Regex("\"status\"\\s*:\\s*\"ok\"")

    fun isReady(status: Int, body: String?): Boolean = status == 200 && body != null && OK.containsMatchIn(body)
}

/** What one attempt came to. */
sealed interface Outcome {
    /** A 2xx: the platform took it. */
    data object Accepted : Outcome

    /** Any non-2xx answer; [retryAfterS] is the `Retry-After` header when one was sent. */
    data class Http(val status: Int, val retryAfterS: Long? = null) : Outcome

    /** No answer at all (connection refused, reset, timeout). */
    data class Unreachable(val message: String = "") : Outcome
}

/** What to do next with the event that just had [Outcome]. */
sealed interface Decision {
    data object Done : Decision

    /**
     * Try the SAME event again no sooner than [ms] from now. [needHealth]: poll the health gate first and send only once it says
     * ok. [dropToken]: forget the cached login first. [alert]: the event has failed [RetryPolicy.stuckAfter] times, tell a human.
     */
    data class Wait(val ms: Long, val reason: String, val needHealth: Boolean = false, val dropToken: Boolean = false, val alert: Boolean = false) :
        Decision

    /** Stop the whole lane. The event stays due; a person looks. */
    data class Halt(val reason: String) : Decision
}

class RetryPolicy(
    val rateLimitWaitMs: Long = 60_000,
    val backoffBaseMs: Long = 5_000,
    val backoffCapMs: Long = 300_000,
    val stuckAfter: Int = 5,
    /** A16: stop on pool or health trouble. This many failures in a row on one event (5xx / no answer) halts the lane; the event stays due. */
    val haltAfter: Int = 10,
) {
    /** [attempt] is 1 for the first try of an event, 2 for its first retry, and so on. */
    fun decide(o: Outcome, attempt: Int): Decision {
        val stuck = attempt >= stuckAfter
        val trouble = attempt >= haltAfter && (o is Outcome.Unreachable || (o is Outcome.Http && o.status >= 500))
        if (trouble) return Decision.Halt("$attempt failures in a row ($o): pool or health trouble, lane stopped, the event stays due")
        return when (o) {
            Outcome.Accepted -> Decision.Done

            is Outcome.Unreachable -> Decision.Wait(
                backoff(attempt),
                "no answer (${o.message.take(80)}): wait for health ok",
                needHealth = true,
                alert = stuck,
            )

            is Outcome.Http -> when {
                o.status == 429 -> Decision.Wait(
                    maxOf(rateLimitWaitMs, (o.retryAfterS ?: 0L) * 1000L),
                    "429: wait ${rateLimitWaitMs / 1000} s, honouring Retry-After=${o.retryAfterS ?: "none"}",
                    alert = stuck,
                )

                o.status == 401 -> Decision.Wait(0, "401: token not accepted, drop it and log in again", dropToken = true, alert = stuck)

                o.status == 502 || o.status == 503 || o.status == 504 ->
                    Decision.Wait(backoff(attempt), "${o.status}: restart or overload, wait for health ok", needHealth = true, alert = stuck)

                o.status in 500..599 -> Decision.Wait(
                    backoff(attempt),
                    "${o.status}: server error, retry with backoff",
                    needHealth = true,
                    alert = stuck,
                )

                else -> Decision.Halt("${o.status}: the platform refused this event (not retriable); lane stopped, the event stays due")
            }
        }
    }

    /** base x 2^(attempt-1), capped. Deterministic: no jitter, so a test and a re-run see the same waits. */
    fun backoff(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        return minOf(backoffCapMs, backoffBaseMs shl shift)
    }
}

/** Store-manager logins: at least [minSpacingMs] apart (A11: one every 7 s or more), each token kept [tokenTtlMs]. */
class LoginGate(val minSpacingMs: Long = 7_000, val tokenTtlMs: Long = 3_600_000) {
    private var lastLoginMs = Long.MIN_VALUE / 2
    private val tokens = HashMap<String, Pair<String, Long>>()

    /** How long to wait before the next login may be made. */
    fun waitBeforeLoginMs(now: Long): Long = maxOf(0L, lastLoginMs + minSpacingMs - now)

    fun recordLogin(now: Long) {
        lastLoginMs = now
    }

    fun cached(email: String, now: Long): String? = tokens[email]?.takeIf { it.second > now }?.first

    fun store(email: String, token: String, now: Long) {
        tokens[email] = token to (now + tokenTtlMs)
    }

    fun drop(email: String) {
        tokens.remove(email)
    }
}

/**
 * One lane's queue of due items, in plan order. The head is the only item that can be sent; a failure moves ITS notBefore, not
 * its place. There is deliberately no way to remove an item except [ack].
 */
class DueQueue<T>(items: List<T>, private val dueMs: (T) -> Long) {
    private val items = ArrayDeque(items)
    private var notBefore = Long.MIN_VALUE
    var failures = 0
        private set
    var acked = 0
        private set

    val size: Int get() = items.size

    fun head(): T? = items.firstOrNull()

    /** The head when it may be sent at [now]: its own due time has come and no failure delay is running. */
    fun due(now: Long): T? = items.firstOrNull()?.takeIf { dueMs(it) <= now && notBefore <= now }

    fun ack() {
        items.removeFirst()
        notBefore = Long.MIN_VALUE
        failures = 0
        acked++
    }

    fun failed(now: Long, waitMs: Long) {
        failures++
        notBefore = now + waitMs
    }

    fun delayUntil(ms: Long) {
        notBefore = maxOf(notBefore, ms)
    }
}
