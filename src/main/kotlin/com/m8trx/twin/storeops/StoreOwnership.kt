package com.m8trx.twin.storeops

import java.nio.file.Files
import java.nio.file.Path

/**
 * Who writes a store's sales, moves and stock to the platform: the hourly pass or the store-day ops driver. Two writers must never
 * run for one store, so ONE file says which, and both sides read it:
 *
 *     # store-ops-owner.csv  (on the box: $M8TRX_OPS_DIR/store-ops-owner.csv)
 *     slug,owner
 *     dec-us-denver,storeday
 *     dec-fr-paris,hourly
 *
 *  - the store-day driver ([StoreOpsLive]) refuses any store that is not listed `storeday` (unlisted counts as `hourly`: the
 *    hourly pass is the default owner of every store);
 *  - the hourly pass must serve only stores whose owner is `hourly`. `twin-hourly.sh` is a box-only script that this repo does not
 *    touch; `ops/INSTALL.md` (section "store-ops driver") gives the one-line edit that makes it read the same file.
 * Flipping Denver is one edit of this file, made in the same step that starts the driver (and stopping the hourly pass for it).
 */
enum class Owner { HOURLY, STOREDAY }

class StoreOwnership(private val owners: Map<String, Owner>) {
    fun ownerOf(slug: String): Owner = owners[slug] ?: Owner.HOURLY

    /** Null when the store-day driver may serve [slug]; otherwise the reason it must not. */
    fun refusal(slug: String): String? = when (ownerOf(slug)) {
        Owner.STOREDAY -> null
        Owner.HOURLY -> "$slug is served by the hourly pass (owner file does not list it as storeday); two writers must never run for one store"
    }

    fun hourlyStores(all: Collection<String>): List<String> = all.filter { ownerOf(it) == Owner.HOURLY }

    companion object {
        fun parse(lines: List<String>): StoreOwnership {
            val m = LinkedHashMap<String, Owner>()
            lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() && !it.startsWith("slug,") }.forEach { l ->
                val c = l.split(",").map { it.trim() }
                require(c.size == 2) { "owner file line must be 'slug,owner': '$l'" }
                m[c[0]] = when (c[1].lowercase()) {
                    "storeday" -> Owner.STOREDAY
                    "hourly" -> Owner.HOURLY
                    else -> throw IllegalArgumentException("owner of ${c[0]} must be storeday or hourly, got '${c[1]}'")
                }
            }
            return StoreOwnership(m)
        }

        /** A missing file is a refusal for the driver, not an empty list: the operator must have written down who owns Denver. */
        fun load(file: Path): StoreOwnership {
            require(Files.exists(file)) { "no owner file at $file: write 'slug,storeday' for each store the driver may serve" }
            return parse(Files.readAllLines(file))
        }
    }
}
