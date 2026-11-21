package com.m8trx.twin.ops

import com.m8trx.twin.connect.model.bearer.ItemDetail
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The twin's `missing-epcs.txt` follows the PLATFORM: an EPC is on it exactly when the platform's state for the unit is `missing`
 * (a close's `missing` list does not always flip the unit, and a unit a later count or a receive finds again was never taken off).
 * Both reads are `POST /inventory/items/details` (data-plane bearer, `inventory:read`, tenant-wide) in chunks of [CHUNK].
 *
 * Exception: `stolen-epcs.txt` (units the alarm scenarios took). A stolen unit stays on the missing list until the platform says
 * `missing` too: it is never removed here, whatever the platform reads, because the platform learns of a theft only at a count.
 * A `sold` unit on the list is left and logged (the sold log is a separate concern).
 */
class MissingFollow(private val details: (List<String>) -> List<ItemDetail>?, private val stateDir: Path) {
    private val log = LoggerFactory.getLogger(MissingFollow::class.java)

    data class Split(val listed: Int, val platformMissing: List<String>, val platformOther: Int)

    data class Reconcile(val checked: Int, val removed: Int, val keptStolen: Int, val sold: Int, val other: Int, val requests: Int)

    fun stolenFile(): Path = stateDir.resolve("stock").resolve("stolen-epcs.txt")

    /** State by upper-cased EPC for [epcs] (a unit with no itemId is absent); null = a chunk's read failed. */
    private fun states(epcs: List<String>): Map<String, String?>? {
        val out = HashMap<String, String?>()
        for (chunk in epcs.chunked(CHUNK)) {
            val rows = try {
                details(chunk)
            } catch (e: Exception) {
                log.error("items/details read failed: {}", e.message)
                null
            } ?: return null
            rows.forEach { if (it.itemId != null) out[it.epc.uppercase()] = it.state }
        }
        return out
    }

    /** A live close's missing list, cut to what the platform reports `missing`; null = the read failed (nothing to append). */
    fun closeMissing(listed: List<String>): Split? {
        if (listed.isEmpty()) return Split(0, emptyList(), 0)
        val st = states(listed) ?: return null
        val pm = listed.filter { st[it.uppercase()] == "missing" }
        return Split(listed.size, pm, listed.size - pm.size)
    }

    /** Takes every platform-`in_stock`, non-stolen EPC off the missing file (atomic rewrite); null = the read failed, file untouched. */
    fun reconcile(): Reconcile? {
        val file = StoreStock.missingFile(stateDir)
        if (!Files.exists(file)) return Reconcile(0, 0, 0, 0, 0, 0)
        val lines = Files.readAllLines(file)
        val epcs = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val st = states(epcs) ?: return null
        val stolen = StoreStock.readLines(stolenFile()).map { it.uppercase() }.toSet()
        val drop = HashSet<String>()
        var keptStolen = 0
        var sold = 0
        var other = 0
        for (e in epcs) {
            when (st[e.uppercase()]) {
                "in_stock" -> if (e.uppercase() in stolen) keptStolen++ else drop += e
                "sold" -> sold++
                "missing" -> {}
                else -> other++
            }
        }
        if (drop.isNotEmpty()) {
            val keep = lines.filter { it.trim() !in drop }
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, keep.joinToString("") { "$it\n" })
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return Reconcile(epcs.size, drop.size, keptStolen, sold, other, (epcs.size + CHUNK - 1) / CHUNK)
    }

    companion object {
        const val CHUNK = 200
    }
}
