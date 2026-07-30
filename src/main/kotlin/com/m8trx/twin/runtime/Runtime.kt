package com.m8trx.twin.runtime

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue
import java.util.Random
import kotlin.reflect.KClass

/**
 * Orchestrator runtime skeleton — the Layer-4 contract locked in `LAYER4-CONFIG-SCHEMA.md` §Runtime model
 * (Q2 Generator / Q3 Scheduler / Q6 EventBus, all LOCKED 2026-05-09).
 *
 * Built to the locked design, not re-designed. The one deliberate divergence is noted on [Scheduler].
 */

/** Q6 — cross-generator correlation. Concrete events live in twin's domain package, not here. */
interface DomainEvent {
    val at: Instant
}

/** Q2 — generators are stateless except for what they capture in subscription closures. No `tick()`. */
interface Generator {
    val id: String
    fun start(ctx: GeneratorContext)
    fun stop(ctx: GeneratorContext) {}
}

/**
 * Q3 — shared scheduler owned by the orchestrator. Priority queue keyed by `(scenarioTime, insertionOrder)`;
 * `insertionOrder` is a global monotonic counter and is the determinism tiebreaker for two callbacks at the
 * same scenario time.
 */
interface Scheduler {
    fun scheduleAt(time: Instant, callback: () -> Unit): ScheduledHandle
    fun scheduleAfter(delay: Duration, callback: () -> Unit): ScheduledHandle
}

interface ScheduledHandle {
    fun cancel()
    val isPending: Boolean
}

interface Clock {
    fun now(): Instant
}

interface EventBus {
    fun <T : DomainEvent> subscribe(type: KClass<T>, handler: (T) -> Unit)
    fun <T : DomainEvent> publish(event: T)
}

/** `meta.failurePolicy` — the scheduler wraps every callback; only post-log behaviour varies. */
enum class FailurePolicy { SKIP_AND_LOG, HALT }

// ── implementations ────────────────────────────────────────────────────────────

class SimpleClock(private var current: Instant) : Clock {
    override fun now(): Instant = current
    internal fun advanceTo(t: Instant) {
        if (t.isAfter(current)) current = t
    }
}

class SimpleEventBus : EventBus {
    private val handlers = mutableMapOf<KClass<*>, MutableList<(DomainEvent) -> Unit>>()
    private val log = LoggerFactory.getLogger(SimpleEventBus::class.java)

    @Suppress("UNCHECKED_CAST")
    override fun <T : DomainEvent> subscribe(type: KClass<T>, handler: (T) -> Unit) {
        handlers.getOrPut(type) { mutableListOf() }.add(handler as (DomainEvent) -> Unit)
    }

    override fun <T : DomainEvent> publish(event: T) {
        handlers[event::class]?.forEach {
            runCatching { it(event) }.onFailure { e -> log.error("bus handler failed for {}", event::class.simpleName, e) }
        }
    }
}

/**
 * Priority-queue scheduler.
 *
 * **Divergence from the locked spec, deliberate:** `scheduleEvery` is specified as a first-class primitive
 * but is not implemented here — nothing in the current generator set uses a repeating cadence, and an
 * unused primitive with drift-tracking semantics is design debt, not capability. Add it when a generator
 * needs it; the interface shape is already decided so it is a drop-in.
 */
class QueueScheduler(private val clock: SimpleClock, private val failurePolicy: FailurePolicy = FailurePolicy.HALT) : Scheduler {

    private val log = LoggerFactory.getLogger(QueueScheduler::class.java)
    private var insertionCounter = 0L
    private var errors = 0

    private inner class Entry(val time: Instant, val order: Long, val callback: () -> Unit) : ScheduledHandle {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
        override val isPending: Boolean get() = !cancelled
    }

    private val queue = PriorityQueue<Entry>(compareBy({ it.time }, { it.order }))

    override fun scheduleAt(time: Instant, callback: () -> Unit): ScheduledHandle = Entry(time, insertionCounter++, callback).also { queue.add(it) }

    override fun scheduleAfter(delay: Duration, callback: () -> Unit): ScheduledHandle = scheduleAt(clock.now().plus(delay), callback)

    val pending: Int get() = queue.count { it.isPending }
    val errorCount: Int get() = errors

    /** Drain at `rate = +∞` — fire everything immediately, zero wall sleep. The regression/headless mode. */
    fun drain(maxCallbacks: Int = 5_000_000) {
        var fired = 0
        while (queue.isNotEmpty() && fired < maxCallbacks) {
            val e = queue.poll()
            if (e.cancelled) continue
            clock.advanceTo(e.time)
            fired++
            try {
                e.callback()
            } catch (ex: Exception) {
                errors++
                log.error("scheduled callback failed at {} (order={})", e.time, e.order, ex)
                if (failurePolicy == FailurePolicy.HALT) throw ex
            }
        }
        if (queue.isNotEmpty()) log.warn("drain stopped at maxCallbacks={} with {} still queued", maxCallbacks, queue.size)
    }
}

/** Q2 — everything a generator needs to act. */
class GeneratorContext(
    val clock: Clock,
    val scheduler: Scheduler,
    val bus: EventBus,
    val rng: Random,
    val log: Logger,
    /** Where Layer-0/1 sample streams land. Recording in headless mode; NATS when live. */
    val sink: AtomSink,
)

/**
 * Where emitted position samples go. Keeping this behind an interface is what lets a whole scenario day be
 * generated, reconciled and oracle-checked offline before a single event reaches the edge.
 */
interface AtomSink {
    fun emitSamples(objectId: String, samples: List<com.m8trx.twin.layer1.ImpressionOracle.Sample>)
    fun evict(objectId: String)
}

/** Headless sink — keeps everything in memory so the oracle and the reconciliation gate can inspect it. */
class RecordingSink : AtomSink {
    private val _streams = LinkedHashMap<String, MutableList<com.m8trx.twin.layer1.ImpressionOracle.Sample>>()
    val streams: Map<String, List<com.m8trx.twin.layer1.ImpressionOracle.Sample>> get() = _streams
    var evictions = 0
        private set

    override fun emitSamples(objectId: String, samples: List<com.m8trx.twin.layer1.ImpressionOracle.Sample>) {
        _streams.getOrPut(objectId) { mutableListOf() }.addAll(samples)
    }

    override fun evict(objectId: String) {
        evictions++
    }

    val totalSamples: Int get() = _streams.values.sumOf { it.size }
}

/** Deterministic per-generator RNG stream, forked from the scenario seed by generator id (Q2). */
fun forkRng(seed: Long, generatorId: String): Random = Random(seed * 31 + generatorId.hashCode().toLong())
