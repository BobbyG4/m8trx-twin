package com.m8trx.twin.ops

import java.nio.file.Path

/**
 * `./gradlew stolenCountTargets` — offline, read-only. Reads the twin's `stolen-epcs.txt` and a platform units CSV
 * (`epc,site,state,space,fixture,...`) and prints the `M8TRX_STOCK_TARGET` string covering every fixture that still holds a
 * stolen unit the platform has in stock. Env: `M8TRX_PLATFORM_UNITS_CSV` (required) · `M8TRX_STOLEN_FILE`
 * (default `.twin-state/stock/stolen-epcs.txt`) · `M8TRX_CHAIN_DIR` (default `reference/data/chain`).
 */
fun main() {
    val csv = System.getenv("M8TRX_PLATFORM_UNITS_CSV")?.takeIf { it.isNotBlank() } ?: error("set M8TRX_PLATFORM_UNITS_CSV")
    val stolenFile = Path.of(System.getenv("M8TRX_STOLEN_FILE") ?: ".twin-state/stock/stolen-epcs.txt")
    val chain = Path.of(System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
    val stolen = StoreStock.readLines(stolenFile)
    val t = StockTargeted.targets(stolen, StockTargeted.readUnits(Path.of(csv))) { StockTargeted.loadNamesToCodes(chain, it) }
    println("stolen units listed: ${stolen.size} · targeted: ${t.units.values.sum()} · skipped: ${t.skipped.size}")
    t.byStore.forEach { (slug, codes) -> println("  $slug: ${codes.size} fixture(s), ${t.units[slug]} stolen unit(s): ${codes.joinToString(",")}") }
    t.skipped.forEach { println("  skipped $it") }
    println()
    println("M8TRX_STOCK_TARGET='${StockTargeted.format(t.byStore)}'")
}
