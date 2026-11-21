package com.m8trx.twin.livefloor

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.m8trx.twin.domain.ObjEviction
import com.m8trx.twin.domain.ObjLocation
import com.m8trx.twin.layer0.NatsEmitter
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue
import kotlin.math.round

/** One event of the feed in plan time: a position ([sample] set) or the walker's eviction ([sample] null). [seq] is the sample index from entry. */
class FloorMsg(val planMs: Long, val walker: Walker, val sample: FloorSample?, val seq: Int)

/**
 * The live floor feed of a [DayWalk]: events in time order (positions at 5 Hz per person, one eviction when a person leaves), and the wire
 * form of each. The wire form uses ONLY `m8trx.<tenant>.<site>.xovis.objLocation|objEviction`, built by [NatsEmitter.xovisSubject].
 */
object LiveFloorFeed {
    /** Unknown fields (a null gender, height, mask) are left out of the JSON rather than sent as null. */
    val mapper: ObjectMapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

    private class Active(val w: Walker, val it: Iterator<FloorSample>, var cur: FloorSample?, var k: Int, var evicted: Boolean) {
        val t: Long get() = if (cur != null) cur!!.tMs else w.tExitMs
        val rank: Int get() = if (cur != null) 0 else 1
    }

    /**
     * Every event from [fromMs] up to (not including) [toMs], in (time, position-before-eviction, id) order. Of a walker's positions only every
     * [decimate]-th is kept (the live drive's speed-up keeps the wall rate at 5 Hz); the eviction is never dropped.
     */
    fun stream(walkers: List<Walker>, fromMs: Long, toMs: Long = Long.MAX_VALUE, decimate: Int = 1): Iterator<FloorMsg> {
        val sorted = walkers.filter { it.tExitMs >= fromMs && it.tEnterMs < toMs }.sortedWith(compareBy({ it.tEnterMs }, { it.objectId }))
        val heap = PriorityQueue<Active>(compareBy<Active>({ it.t }, { it.rank }, { it.w.objectId }))
        var next = 0

        fun start(w: Walker): Active {
            val it = w.samples().iterator()
            var k = 0
            var cur = it.next()
            while (cur.tMs < fromMs && it.hasNext()) {
                cur = it.next()
                k++
            }
            val a = Active(w, it, if (cur.tMs < fromMs) null else cur, k, false)
            return a
        }

        return object : Iterator<FloorMsg> {
            private var pending: FloorMsg? = null

            private fun fill() {
                while (pending == null) {
                    while (next < sorted.size && (heap.isEmpty() || sorted[next].tEnterMs <= heap.peek().t)) heap.add(start(sorted[next++]))
                    val a = heap.poll() ?: return
                    if (a.t >= toMs) {
                        heap.clear()
                        next = sorted.size
                        return
                    }
                    val s = a.cur
                    if (s != null) {
                        val emit = a.k % decimate == 0
                        val m = FloorMsg(s.tMs, a.w, s, a.k)
                        a.k++
                        a.cur = if (a.it.hasNext()) a.it.next() else null
                        heap.add(a)
                        if (emit) pending = m
                    } else if (!a.evicted) {
                        pending = FloorMsg(a.w.tExitMs, a.w, null, a.k)
                    }
                }
            }

            override fun hasNext(): Boolean {
                fill()
                return pending != null
            }

            override fun next(): FloorMsg {
                fill()
                return pending!!.also { pending = null }
            }
        }
    }

    private fun mm(v: Double) = round(v * 10.0) / 10.0

    private fun unit(v: Double) = round(v * 10_000.0) / 10_000.0

    /** The `objLocation` body: millimetres in the sales-floor frame, the unit view direction, gender (omitted when unknown), the staff tag. */
    fun locationBody(w: Walker, s: FloorSample, layoutId: String) = ObjLocation(
        objectId = w.objectId,
        x = mm(s.x),
        y = mm(s.y),
        isMale = w.isMale,
        hasTag = w.staff,
        viewDirection = arrayOf(unit(s.hx), unit(s.hy)),
        layoutId = layoutId,
    )

    /** A wire message: the subject and the envelope bytes. */
    class Wire(val subject: String, val bytes: ByteArray, val type: String)

    fun evictionWire(objectId: String, tenantId: String, siteId: String, layoutId: String, tsMs: Long) = Wire(
        NatsEmitter.xovisSubject(tenantId, siteId, "objEviction"),
        NatsEmitter.envelopeBytes(mapper, layoutId.replace("-", ""), "objEviction", tsMs, "$objectId-evict", ObjEviction(objectId, layoutId)),
        "objEviction",
    )

    fun wire(m: FloorMsg, tenantId: String, siteId: String, layoutId: String, tsMs: Long): Wire {
        val s = m.sample
        val areaId = layoutId.replace("-", "")
        return if (s == null) {
            evictionWire(m.walker.objectId, tenantId, siteId, layoutId, tsMs)
        } else {
            Wire(
                NatsEmitter.xovisSubject(tenantId, siteId, "objLocation"),
                NatsEmitter.envelopeBytes(mapper, areaId, "objLocation", tsMs, "${m.walker.objectId}-${m.seq}", locationBody(m.walker, s, layoutId)),
                "objLocation",
            )
        }
    }
}

/** Dry mode: the would-be messages as NDJSON, one `{"subject":..,"payload":{envelope}}` per line. Nothing is published, no connection is opened. */
class NdjsonWriter(file: Path) : AutoCloseable {
    private val out: BufferedWriter = run {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.newBufferedWriter(file)
    }
    var lines = 0L
        private set

    fun write(w: LiveFloorFeed.Wire) {
        out.write("{\"subject\":\"")
        out.write(w.subject)
        out.write("\",\"payload\":")
        out.write(String(w.bytes, Charsets.UTF_8))
        out.write("}\n")
        lines++
    }

    override fun close() = out.close()
}
