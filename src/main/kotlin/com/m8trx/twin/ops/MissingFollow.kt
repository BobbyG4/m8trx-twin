package com.m8trx.twin.ops

import com.m8trx.twin.connect.model.bearer.ItemDetail
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The twin's `missing-epcs.txt` follows the PLATFORM: an EPC is on it exactly when the platform's state for the unit is `missing`
 * (a close's `missing` list does not always flip the unit, and a unit a later count or a receive finds again was never taken off).
 * Both reads are `POST /inventory/items/details` (data-plane bearer, `inventory:read`, tenant-wide) in chunks of [CHUNK].
 *
 * Exception: `stolen-epcs.txt` (units the alarm scenarios took). A stolen unit stays on the missing list until the platform says
 * `missing` too: it is never removed here, whatever the platform reads, because the platform learns of a theft only at a count.
 * A `sold` unit on the list is left and logged (the sold log is a separate concern).
 */
class MissingFollow(
    private val details: (List<String>) -> List<ItemDetail>?,
    private val stateDir: Path,
    private val now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(MissingFollow::class.java)

    data class Split(val listed: Int, val platformMissing: List<String>, val platformOther: Int, val pending: List<String> = emptyList())

    data class Reconcile(
        val checked: Int,
        val removed: Int,
        val keptStolen: Int,
        val sold: Int,
        val other: Int,
        val requests: Int,
        val promoted: Int = 0,
        val pendingSold: Int = 0,
        val pendingExpired: Int = 0,
    )

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
        val pend = listed.filter { st[it.uppercase()] != "missing" && st[it.uppercase()] != "sold" }
        return Split(listed.size, pm, listed.size - pm.size, pend)
    }

    private fun writeAtomic(file: Path, lines: List<String>) {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, lines.joinToString("") { "$it\n" })
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun pendingFile(): Path = stateDir.resolve("stock").resolve("missing-pending.txt")

    /** Close candidates the platform did not (yet) report missing: `epc,listed_at`, kept until it does, it sells, or [PENDING_DAYS] pass. */
    fun addPending(epcs: List<String>) {
        if (epcs.isEmpty()) return
        val file = pendingFile()
        val lines = if (Files.exists(file)) Files.readAllLines(file) else emptyList()
        val have = lines.map { it.substringBefore(",").trim().uppercase() }.toSet()
        val add = epcs.filter { it.uppercase() !in have }.distinct().map { "$it,${now()}" }
        if (add.isNotEmpty()) writeAtomic(file, lines + add)
    }

    /**
     * One pass over the missing list AND the pending list, in the same details requests. Missing list: platform-`in_stock`, non-stolen
     * EPCs come off (stolen/sold/other stay). Pending: now `missing` -> onto the missing list; `sold` -> dropped; older than
     * [PENDING_DAYS] -> dropped and logged. Both files rewritten atomically, only when changed (idempotent). null = a read failed, files untouched.
     */
    fun reconcile(): Reconcile? {
        val file = StoreStock.missingFile(stateDir)
        val lines = if (Files.exists(file)) Files.readAllLines(file) else emptyList()
        val epcs = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val pFile = pendingFile()
        val pLines = if (Files.exists(pFile)) Files.readAllLines(pFile) else emptyList()
        val pending = pLines.map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringBefore(",") to it.substringAfter(",", "") }
        val all = (epcs + pending.map { it.first }).distinct()
        if (all.isEmpty()) return Reconcile(0, 0, 0, 0, 0, 0)
        val st = states(all) ?: return null
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
        val onList = epcs.map { it.uppercase() }.toSet()
        val promote = ArrayList<String>()
        val keepPending = ArrayList<String>()
        var pSold = 0
        var pExpired = 0
        val cutoff = now().minus(PENDING_DAYS, ChronoUnit.DAYS)
        for ((e, at) in pending) {
            val listedAt = runCatching { Instant.parse(at) }.getOrNull()
            when {
                st[e.uppercase()] == "missing" -> if (e.uppercase() !in onList) promote += e

                st[e.uppercase()] == "sold" -> pSold++

                listedAt != null && listedAt.isBefore(cutoff) -> {
                    pExpired++
                    log.info("  {} count listed, platform never marked missing (listed {})", e, at)
                }

                else -> keepPending += "$e,$at"
            }
        }
        if (drop.isNotEmpty() || promote.isNotEmpty()) {
            writeAtomic(file, lines.filter { it.trim() !in drop } + promote)
        }
        if (keepPending.size != pending.size) writeAtomic(pFile, keepPending)
        if (pending.isNotEmpty()) {
            log.info(
                "missing pending: {} checked · {} now platform-missing (booked) · {} sold (dropped) · {} expired (> {} d) · {} still pending",
                pending.size,
                promote.size,
                pSold,
                pExpired,
                PENDING_DAYS,
                keepPending.size,
            )
        }
        return Reconcile(epcs.size, drop.size, keptStolen, sold, other, (all.size + CHUNK - 1) / CHUNK, promote.size, pSold, pExpired)
    }

    companion object {
        const val CHUNK = 200
        const val PENDING_DAYS = 3L
    }
}
