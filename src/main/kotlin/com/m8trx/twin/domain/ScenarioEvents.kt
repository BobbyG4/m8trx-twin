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

// ── loss prevention ───────────────────────────────────────────────────────────

/**
 * A shopper took an EAS-tagged unit off a fixture and did not present it at a till.
 *
 * ⚠ **Concealment is twin-side state and is NOT sent to the platform.** No M8TRX surface knows an item was
 * picked up — inventory only moves on a scan, a sale or a receive. This event exists so the arc is
 * *coherent inside twin*: it is what makes the later gate crossing mean something rather than being an
 * alarm fired at an arbitrary EPC.
 */
data class ItemConcealed(
    override val at: Instant,
    val customerId: String,
    val itemCd: String,
    val ean: String,
    val priceUsd: Double,
    val fixture: String,
) : DomainEvent

/**
 * A tracked shopper crossed an EAS gate line outbound.
 *
 * **This is the consequence-carrier, and the reason there is no `EasAlarmRaised` here.** The alarm must be a
 * consequence of the *track* — a shopper whose `objLocation` path actually crossed `y=600` while carrying
 * unpaid tagged units — not a separate event fired alongside it. A journey that published an alarm directly
 * would produce an LP surface that looks populated and means nothing, which is the "structural zero rendered
 * as fact" pattern in twin's own output.
 *
 * So the journey emits the crossing **derived from the samples it published**, and an alarm emitter
 * subscribes to this. [carriedEpcs] empty ⇒ a clean exit and no alarm; non-empty ⇒ the alarm has a real
 * shopper, a real dwell, a real fixture and a real unit behind it.
 */
data class EasGateCrossed(
    override val at: Instant,
    val customerId: String,
    val gateCode: String,
    val gateZoneCode: String,
    val outbound: Boolean,
    val carriedEpcs: List<String>,
    val paid: Boolean,
) : DomainEvent {
    /** The LP-relevant case: outbound, carrying tagged stock, nothing paid for. */
    val shouldAlarm: Boolean get() = outbound && carriedEpcs.isNotEmpty() && !paid
}
