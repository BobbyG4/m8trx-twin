package com.m8trx.twin.layer0

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.m8trx.twin.TwinConfig
import com.m8trx.twin.domain.AlarmEvent
import com.m8trx.twin.domain.Crossing
import com.m8trx.twin.domain.FittingRoomAddItem
import com.m8trx.twin.domain.FittingRoomEntry
import com.m8trx.twin.domain.FittingRoomExit
import com.m8trx.twin.domain.NatsEnvelope
import com.m8trx.twin.domain.ObjEviction
import com.m8trx.twin.domain.ObjLocation
import io.nats.client.Nats
import io.nats.client.Connection
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * NATS Layer 0 publisher.
 *
 * Dual-publishes on legacy subject `area.<spaceIdNoHyphens>.<eventType>` AND
 * new subject `m8trx.<tenantId>.<siteId>.<domain>.<eventType>` — matches
 * core's AreaEventPublisher dual-publish pattern (Phase 1, 2026-05-09).
 *
 * Domain mapping mirrors AreaEventPublisher.eventDomain().
 */
class NatsEmitter(
    private val config: TwinConfig,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(NatsEmitter::class.java)
    private val conn: Connection = Nats.connect(config.natsUrl)

    init {
        log.info("NATS connected to {} status={}", config.natsUrl, conn.status)
    }

    fun <T : Any> publish(eventType: String, domain: String, body: T, ts: Long, id: String) {
        val envelope = NatsEnvelope(
            type = eventType,
            ts = ts,
            id = id,
            areaId = config.spaceIdNoHyphens,
            body = body,
        )
        val bytes = mapper.writeValueAsBytes(envelope)

        val legacy = "area.${config.spaceIdNoHyphens}.$eventType"
        val modern = "m8trx.${config.tenantId}.${config.siteId}.$domain.$eventType"

        conn.publish(legacy, bytes)
        conn.publish(modern, bytes)

        if (log.isDebugEnabled) log.debug("published {} → [{}, {}]", eventType, legacy, modern)
    }

    fun close() {
        conn.close()
    }
}

/** Domain tag per AreaEventPublisher.eventDomain() */
private fun domain(eventType: String): String = when (eventType) {
    "objLocation", "objEviction", "crossing", "fixtureImpression",
    "dwellingNearbyFixture", "lookingAtFixture", "walkingPath",
    "personStateChange", "xovisTrackedPosition",
    -> "xovis"

    "fittingRoomEntry", "fittingRoomExit", "fittingRoomAddItem",
    "fittingRoom.service.new", "fittingRoom.service.accept",
    "fittingRoom.service.cancel", "fittingRoom.service.complete.timeout",
    "fittingRoom.service.complete.delivered", "fittingRoom.service.complete.canceled",
    -> "fitting"

    "alarmEvent" -> "eas"
    "scanEvent", "inventoryUpdate" -> "rfid"
    "arDevicePosition", "xovisCalibration.progress", "xovisCalibration.complete" -> "ar"
    else -> "edge"
}

/** Extension to make NatsEmitter satisfy the NATS-side of AtomEmitters easily. */
fun NatsEmitter.objLocation(body: ObjLocation, ts: Long, id: String) =
    publish("objLocation", domain("objLocation"), body, ts, id)

fun NatsEmitter.objEviction(body: ObjEviction, ts: Long, id: String) =
    publish("objEviction", domain("objEviction"), body, ts, id)

fun NatsEmitter.crossing(body: Crossing, ts: Long, id: String) =
    publish("crossing", domain("crossing"), body, ts, id)

fun NatsEmitter.fittingRoomEntry(body: FittingRoomEntry, ts: Long, id: String) =
    publish("fittingRoomEntry", domain("fittingRoomEntry"), body, ts, id)

fun NatsEmitter.fittingRoomExit(body: FittingRoomExit, ts: Long, id: String) =
    publish("fittingRoomExit", domain("fittingRoomExit"), body, ts, id)

fun NatsEmitter.fittingRoomAddItem(body: FittingRoomAddItem, ts: Long, id: String) =
    publish("fittingRoomAddItem", domain("fittingRoomAddItem"), body, ts, id)

fun NatsEmitter.easAlarm(body: AlarmEvent, ts: Long, id: String) =
    publish("alarmEvent", domain("alarmEvent"), body, ts, id)
