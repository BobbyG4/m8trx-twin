package com.m8trx.twin.history

import com.m8trx.twin.layer2.StoreCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

/**
 * The read-only key export the Coordinator took from mother on 2026-10-11
 * (`reference/data/chain/keys-2026-10-11.csv`) — the ONLY keys the history generator may emit.
 *
 * Migration 259 resolves every row's key against the live tables and refuses the whole load if one fails,
 * so the generator reads its store list, departments and fixtures from this file rather than from
 * `layout.json` (they agree today — 10/10 stores, every code and name — and `historySelfTest` asserts it).
 *
 * Columns: `site_slug, timezone, sales_floor_space_id, area_sqm, zone_id, zone_type, zone_name, fixture_code, department`.
 */
class ChainKeys(val stores: List<Store>) {

    data class Store(val slug: String, val zone: ZoneId, val salesFloorSpaceId: String, val areaSqm: Double, val zones: List<Zone>) {
        /** Every region zone — departments AND the non-merchandise regions (accessories wall, GPS, service). */
        val regions: List<Zone> get() = zones.filter { it.zoneType == "region" }

        /** Region zones that carry merchandise (a sport-universe department) — the commerce departments. */
        val departments: List<Zone> get() = regions.filter { !it.department.isNullOrBlank() }

        val fixtures: List<Zone> get() = zones.filter { it.zoneType == "fixture" }

        /** Department slug (`running`) → the region zone's NAME, the key mig 259 resolves departments by. */
        val departmentZoneName: Map<String, String> get() = departments.associate { it.department!! to it.name }

        /** Zone `properties.code` → the zone, across every zone type on the sales floor. */
        val byCode: Map<String, Zone> get() = zones.associateBy { it.code }
    }

    data class Zone(val zoneId: String, val zoneType: String, val name: String, val code: String, val department: String?)

    fun store(slug: String): Store = stores.first { it.slug == slug }

    companion object {
        val DEFAULT_PATH: Path = Path.of("reference/data/chain/keys-2026-10-11.csv")

        fun load(path: Path = DEFAULT_PATH): ChainKeys {
            val lines = Files.readAllLines(path).filter { it.isNotBlank() }
            val header = StoreCatalog.splitCsv(lines.first())
            fun idx(n: String) = header.indexOf(n).also { require(it >= 0) { "keys export lacks column $n" } }
            val iSlug = idx("site_slug")
            val iTz = idx("timezone")
            val iSpace = idx("sales_floor_space_id")
            val iArea = idx("area_sqm")
            val iZoneId = idx("zone_id")
            val iType = idx("zone_type")
            val iName = idx("zone_name")
            val iCode = idx("fixture_code")
            val iDept = idx("department")
            val rows = lines.drop(1).map { StoreCatalog.splitCsv(it) }
            val stores = rows.groupBy { it[iSlug] }.toSortedMap().map { (slug, rs) ->
                val first = rs.first()
                Store(
                    slug = slug,
                    zone = ZoneId.of(first[iTz]),
                    salesFloorSpaceId = first[iSpace],
                    areaSqm = first[iArea].toDouble(),
                    zones = rs.map { Zone(it[iZoneId], it[iType], it[iName], it[iCode], it[iDept].ifBlank { null }) }
                        .sortedBy { it.code },
                )
            }
            return ChainKeys(stores)
        }
    }
}
