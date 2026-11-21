package com.m8trx.twin.livefloor

import io.nats.client.Connection
import io.nats.client.Nats

/** Thrown when the floor path is about to publish on a subject it is not allowed to use. Fatal for the floor thread only. */
class SubjectRefused(message: String) : RuntimeException(message)

/**
 * The hard runtime guard of the floor path: a subject goes out only if it is EXACTLY `m8trx.<tenant>.<siteId>.xovis.objLocation` or `...objEviction`,
 * with `<tenant>` the configured tenant and `<siteId>` one of the configured floor stores' site ids. The allowed set is built once, at start.
 *
 * WHY: the twin edge server (:33081) runs a build from 2026-10-18, older than the own-site check. Its `area.*.>` subscribers derive sessions and
 * impressions for ANY space that appears on :4223 and file them under Denver's site. A single `area.<space>.*` message for another store would
 * recreate the 2026-11-17 misfiling (22,657 sessions). So "never `area.*`" is enforced here at every publish, not only in a test.
 */
class SubjectGuard(tenantId: String, siteIds: Collection<String>) {
    private val allowed: Set<String> = siteIds.flatMap { site ->
        listOf("objLocation", "objEviction").map { type -> "m8trx.$tenantId.$site.xovis.$type" }
    }.toSet()

    /** [subject] itself when it is allowed, else throws [SubjectRefused]. */
    fun check(subject: String): String {
        if (subject !in allowed) throw SubjectRefused("refusing to publish on '$subject': the floor path may only use ${allowed.sorted()}")
        return subject
    }

    fun allows(subject: String): Boolean = subject in allowed
}

/**
 * The floor path's only way onto the broker. It owns its own connection and has ONE publish method, which passes every subject through the
 * [SubjectGuard]. It does not wrap or expose `NatsEmitter`, so the generator's `publish` (the one that also emits `area.*`) is unreachable from the
 * floor path by construction: there is no object of that type in this package to call it on.
 */
class FloorPublisher(url: String, private val guard: SubjectGuard) : AutoCloseable {
    private val conn: Connection = Nats.connect(url)

    fun publish(wire: LiveFloorFeed.Wire) {
        conn.publish(guard.check(wire.subject), wire.bytes)
    }

    override fun close() = conn.close()
}
