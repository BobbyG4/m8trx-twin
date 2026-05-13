package com.m8trx.twin.domain

import java.util.UUID

/**
 * Wire shapes for NATS area events — must match core's AreaEvent exactly.
 * Source of truth: m8trx-services/area/src/main/kotlin/com/m8trx/area/AreaEvent.kt
 *
 * The envelope published to NATS is Event<T> { type, ts, id, areaId?, body: T }.
 * Each body class carries a `type` field matching the NATS event-type literal.
 */

data class NatsEnvelope<T>(
    val type: String,
    val ts: Long,
    val id: String,
    val areaId: String?,
    val body: T,
)

data class ObjLocation(
    val objectId: String,
    val x: Double,
    val y: Double,
    val height: Float? = null,
    val isMale: Boolean? = null,
    val faceMask: Boolean? = null,
    val hasTag: Boolean? = null,
    val viewDirection: Array<Double>? = null,
    val layoutId: String,
    val type: String = "objLocation",
)

data class ObjEviction(
    val objectId: String,
    val layoutId: String,
    val type: String = "objEviction",
)

data class Crossing(
    val sliceId: UUID,
    val objectId: String,
    val leftToRight: Boolean,
    val sourceEventId: String? = null,
    val layoutId: String,
    val type: String = "crossing",
)

data class FittingRoomEntry(
    val objectId: String,
    val roomId: UUID,
    val prevEpcs: List<String>,
    val layoutId: String,
    val type: String = "fittingRoomEntry",
)

data class FittingRoomExit(
    val objectId: String,
    val roomId: UUID,
    val layoutId: String,
    val type: String = "fittingRoomExit",
)

data class FittingRoomAddItem(
    val roomId: UUID,
    val epc: String,
    val layoutId: String,
    val type: String = "fittingRoomAddItem",
)

data class AlarmEvent(
    val gateId: String,
    val alarmType: String,
    val epcs: List<String>,
    val layoutId: String,
    val type: String = "alarmEvent",
)
