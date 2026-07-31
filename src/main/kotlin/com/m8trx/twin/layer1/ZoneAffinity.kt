package com.m8trx.twin.layer1

import com.m8trx.twin.connect.model.ConnectMappers
import java.nio.file.Files
import java.nio.file.Path

/**
 * Zone affinity, re-keyed off structure instead of literal zone codes (Bob's ruling, 2026-07-28 decision 3).
 *
 * `STORE-OPERATING-MODEL.md` §8 was calibrated against the superseded 600 sqm single Manhattan store and
 * keys affinity to literal codes — `Z-04_main_sales_floor`, `Z-10_fitting_rooms`. **Neither zone exists in
 * the seeded chain.** The single "Main Sales Floor" was replaced by 2–7 sport-universe department bands
 * (`D-01…D-07`), and fitting rooms became their own `space`, not a zone on the floor.
 *
 * The fix is to key off `(space_type, zone_type)` plus the structural code prefix, all of which are stable
 * across all 10 stores. Same effort as patching the two codes, and it then resolves unchanged in every
 * store rather than only in Denver.
 *
 * **Never key off `name`** — names are localized (EN/FR/KO per `reference/data/chain/localization`), so
 * "Fitting Stall 1" is not a stable identifier.
 *
 * Verified vocabulary across all 10 stores: every store has exactly 1 `entry_exit`, 1 `checkout`,
 * 4 Z-prefixed regions (Z-03/Z-06/Z-07/Z-11), 2 sales-floor try-on zones (Z-08 Footwear Bench,
 * Z-09 Gait Analysis), 2–7 D-prefixed department bands, and its own `fitting_room` space.
 */
enum class ZoneRole {
    ENTRANCE,
    CHECKOUT,
    DEPARTMENT_BAND,
    GPS_ACCESSORIES,
    ACCESSORIES_WALL,
    SERVICE_CLUSTER,
    NON_RETAIL,
    FOOTWEAR_BENCH,
    GAIT_ANALYSIS,
    FITTING_ROOM,
    RECEIVING,
    BACKROOM_RACK,
    FIXTURE,
    ;

    /** True where a shopper journey may legitimately go. Back-of-house is staff-only. */
    val shopperReachable: Boolean
        get() = this !in setOf(RECEIVING, BACKROOM_RACK, NON_RETAIL)
}

/** A zone resolved to its structural role, with the store/space context a journey needs. */
data class RoleZone(
    val storeCode: String,
    val spaceType: String,
    val zoneCode: String,
    val zoneType: String,
    val role: ZoneRole,
    val department: String?,
)

/**
 * P(session visits) per role.
 *
 * Values carried over from `STORE-OPERATING-MODEL.md` §8 keep their original confidence tag. Roles §8 never
 * covered are marked **[A-new]** — they are reasoned defaults introduced by this re-key, NOT calibrated
 * research, and are listed explicitly by [uncalibratedRoles] so they never masquerade as sourced figures.
 */
object ZoneAffinityModel {

    /** Roles whose probability §8 supplied. */
    private val calibrated: Map<ZoneRole, Double> = mapOf(
        // was Z-04 "Main Sales Floor (gondolas)" 0.95 — now P(visits at least one department band)
        ZoneRole.DEPARTMENT_BAND to 0.95,
        ZoneRole.FOOTWEAR_BENCH to 0.35, // was Z-08
        ZoneRole.GAIT_ANALYSIS to 0.12, // was Z-09
        ZoneRole.GPS_ACCESSORIES to 0.30, // was Z-06
        ZoneRole.FITTING_ROOM to 0.18, // was Z-10; now P(enters the fitting_room SPACE)
    )

    /** Roles the old §8 table had no equivalent for. Reasoned defaults — tune freely, do not cite. */
    private val introduced: Map<ZoneRole, Double> = mapOf(
        ZoneRole.ENTRANCE to 1.00, // every session crosses it by definition
        ZoneRole.ACCESSORIES_WALL to 0.22, // impulse depth, browsable perimeter
        ZoneRole.SERVICE_CLUSTER to 0.06, // returns/advice desk — minority of sessions
        ZoneRole.NON_RETAIL to 0.00, // structural strip, not shoppable
        ZoneRole.RECEIVING to 0.00, // staff-only
        ZoneRole.BACKROOM_RACK to 0.00, // staff-only
        ZoneRole.FIXTURE to 0.00, // fixtures are dwell targets within a band, not visit units
    )

    /** Roles whose value this re-key invented rather than inherited — surfaced so they stay honest. */
    val uncalibratedRoles: Set<ZoneRole> get() = introduced.keys.filter { introduced[it] != 0.0 }.toSet()

    /** CHECKOUT is not a constant — §8 defines it as equal to the conversion rate. */
    fun probability(role: ZoneRole, conversionRate: Double): Double = when (role) {
        ZoneRole.CHECKOUT -> conversionRate
        else -> calibrated[role] ?: introduced[role] ?: 0.0
    }

    /**
     * P(the shopper actually STOPS at a fixture | they are in this zone) — distinct from [probability],
     * which is P(they enter the zone at all).
     *
     * The two are not the same thing, and conflating them is what kept 18 of Denver's 115 fixtures out of an
     * entire generated day. Every shopper crosses the entrance ([probability] 1.00) but only a minority stops
     * at the promo island there; conversely a shopper who walks into the GPS zone went there to look at
     * watches. Without this split, keying fixture pools off the zone (the fix) would have swung the other way
     * and had all 790 shoppers browsing the three entrance circles for a full zone median.
     *
     * **Every value here is [A-new]** — §8 has no equivalent table, so none of these are calibrated research.
     * Reasoned from zone function: destination zones high, transit zones low. Tune freely; do not cite.
     * Surfaced by [uncalibratedBrowseRates] so they cannot masquerade as sourced figures.
     */
    fun fixtureBrowseRate(role: ZoneRole): Double = when (role) {
        // Destination zones — the shopper went there to look at the merchandise.
        ZoneRole.DEPARTMENT_BAND -> 0.95
        ZoneRole.GPS_ACCESSORIES -> 0.90
        ZoneRole.GAIT_ANALYSIS -> 0.85
        ZoneRole.ACCESSORIES_WALL -> 0.85
        ZoneRole.FOOTWEAR_BENCH -> 0.80
        // Transit zones — a fixture is there, most people walk past it.
        ZoneRole.CHECKOUT -> 0.25 // the impulse rack (`CO-IR`), not the counters
        ZoneRole.ENTRANCE -> 0.15 // promo islands catch a minority on the way in
        // No browsable fixtures inside these (or staff-only).
        else -> 0.0
    }

    /** Browse rates this model invented rather than inherited from §8 — i.e. all non-zero ones. */
    val uncalibratedBrowseRates: Set<ZoneRole>
        get() = ZoneRole.entries.filter { fixtureBrowseRate(it) > 0.0 }.toSet()

    /** Median dwell in minutes per role, from §8 `dwell.by_zone_min`; null where §8 gave none. */
    fun dwellMedianMin(role: ZoneRole): Double? = when (role) {
        ZoneRole.FOOTWEAR_BENCH, ZoneRole.GAIT_ANALYSIS -> 9.0
        ZoneRole.DEPARTMENT_BAND -> 6.0
        ZoneRole.GPS_ACCESSORIES -> 4.0
        ZoneRole.CHECKOUT -> 3.0
        else -> null
    }
}

/** Resolves a store's `layout.json` zones to structural roles. */
object ZoneRoleResolver {

    /** Structural resolution — `(space_type, zone_type)` first, code prefix only to disambiguate within a type. */
    fun roleOf(spaceType: String, zoneType: String, code: String, department: String?): ZoneRole? {
        val prefix = code.substringBefore("-")
        return when {
            spaceType == "sales_floor" && zoneType == "entry_exit" -> ZoneRole.ENTRANCE
            spaceType == "sales_floor" && zoneType == "checkout" -> ZoneRole.CHECKOUT

            spaceType == "sales_floor" && zoneType == "region" && department != null -> ZoneRole.DEPARTMENT_BAND
            spaceType == "sales_floor" && zoneType == "region" -> when (code) {
                "Z-03" -> ZoneRole.NON_RETAIL
                "Z-06" -> ZoneRole.GPS_ACCESSORIES
                "Z-07" -> ZoneRole.ACCESSORIES_WALL
                "Z-11" -> ZoneRole.SERVICE_CLUSTER
                else -> null
            }

            spaceType == "sales_floor" && zoneType == "try_on_zone" -> when (code) {
                "Z-08" -> ZoneRole.FOOTWEAR_BENCH
                "Z-09" -> ZoneRole.GAIT_ANALYSIS
                else -> null
            }

            spaceType == "fitting_room" && zoneType == "try_on_zone" -> ZoneRole.FITTING_ROOM

            spaceType == "stockroom" && zoneType == "fixture" && prefix == "RCV" -> ZoneRole.RECEIVING
            spaceType == "stockroom" && zoneType == "fixture" && prefix == "BR" -> ZoneRole.BACKROOM_RACK

            zoneType == "fixture" -> ZoneRole.FIXTURE
            else -> null
        }
    }

    /** Resolve every zone in a store. Unresolved zones surface as nulls in [Resolution.unmapped]. */
    fun resolve(layoutJson: Path): Resolution {
        val root = ConnectMappers.camel.readTree(Files.readAllBytes(layoutJson))
        val store = root.path("store_id").asText()
        val mapped = mutableListOf<RoleZone>()
        val unmapped = mutableListOf<String>()

        for (space in root.path("spaces")) {
            val spaceType = space.path("space_type").asText()
            for (z in space.path("zones")) {
                val code = z.path("code").asText()
                val zoneType = z.path("zone_type").asText()
                val dept = z.path("department").asText(null)?.takeIf { it.isNotBlank() && it != "null" }
                val role = roleOf(spaceType, zoneType, code, dept)
                if (role == null) {
                    unmapped += "$store/$spaceType/$zoneType/$code"
                } else {
                    mapped += RoleZone(store, spaceType, code, zoneType, role, dept)
                }
            }
        }
        return Resolution(store, mapped, unmapped)
    }

    data class Resolution(val storeCode: String, val zones: List<RoleZone>, val unmapped: List<String>) {
        fun withRole(role: ZoneRole): List<RoleZone> = zones.filter { it.role == role }
        val departments: List<String> get() = zones.mapNotNull { it.department }.distinct().sorted()
    }
}
