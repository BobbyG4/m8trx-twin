package com.m8trx.twin.domain

import com.m8trx.twin.runtime.DomainEvent
import java.time.Instant

/**
 * DomainEvent taxonomy for the scenario runtime — bus-internal correlation, NOT the M8TRX wire.
 * Shapes are from `LAYER4-CONFIG-SCHEMA.md` §Runtime model Q6.
 *
 * These never leave the process. What reaches M8TRX is Layer-0 atoms (`objLocation` over NATS,
 * `sale_event` over the Connect webhook); these are how generators correlate with each other.
 */

/**
 * A visitor entered.
 *
 * **This is the visitor denominator for the §1 reconciliation identity.** Ruled 2026-07-28: `person_session`
 * on the platform side IS the visit record (entry/exit, site, space, one row per tracked person), and it is
 * live and proven — twin's own S15 episodes created six. `crossing_line` is a door-counter instrument:
 * more precise for entrance counts, post-MVP, and NOT a prerequisite. An earlier twin note framed footfall
 * as blocked on a crossing consumer; that was wrong and is corrected here.
 */
data class CustomerEntered(override val at: Instant, val customerId: String, val journeyId: String) : DomainEvent

data class CustomerReachedZone(override val at: Instant, val customerId: String, val zoneCode: String) : DomainEvent

data class CustomerExited(override val at: Instant, val customerId: String) : DomainEvent

/** A completed basket. `lines` carry real SKUs off the store's assortment, priced from `price_usd`. */
data class SaleCompleted(override val at: Instant, val customerId: String, val lines: List<SaleLine>) : DomainEvent {
    val units: Int get() = lines.size
    val totalUsd: Double get() = lines.sumOf { it.priceUsd }
}

data class SaleLine(val itemCd: String, val ean: String, val priceUsd: Double, val department: String, val fixture: String)
