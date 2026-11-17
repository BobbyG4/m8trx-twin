package com.m8trx.twin.connect

import com.m8trx.twin.connect.DatedStream.EpcRow
import com.m8trx.twin.connect.DatedStream.PlannedSale
import com.m8trx.twin.connect.model.webhook.ShipmentLine
import com.m8trx.twin.history.HistoryCsv
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Decathlon SGTIN-96 (EPC-ENCODING-DECATHLON.md; filter 1, partition 6, indicator 0): the same layout `scripts/build_chain.py`
 * encodes the chain's EPCs with, so a platform that resolves the chain's tags resolves a minted tag to the same product.
 *
 * `header 8 | filter 3 | partition 3 | company 20 (EAN digits 0-5) | indicator 4 | itemref 20 (EAN digits 6-11) | serial 38`.
 */
object Sgtin96 {
    private const val FILTER = 1L
    private const val PARTITION = 6L
    const val SERIAL_BITS = 38

    fun encode(ean: String, serial: Long): String {
        require(ean.length == 13 && ean.all { it.isDigit() }) { "not an EAN-13: $ean" }
        require(serial in 0 until (1L shl SERIAL_BITS)) { "serial out of 38 bits: $serial" }
        val company = ean.substring(0, 6).toLong()
        val itemRef = ean.substring(6, 12).toLong()
        var v = BigInteger.valueOf(0x30)
        fun push(value: Long, bits: Int) {
            v = v.shiftLeft(bits).or(BigInteger.valueOf(value))
        }
        push(FILTER, 3)
        push(PARTITION, 3)
        push(company, 20)
        push(0, 4)
        push(itemRef, 20)
        push(serial, SERIAL_BITS)
        return v.toString(16).uppercase().padStart(24, '0')
    }

    private fun bits(epc: String): String {
        require(epc.length == 24) { "not a 96-bit EPC: $epc" }
        return BigInteger(epc, 16).toString(2).padStart(96, '0')
    }

    /** EPC → EAN-13 (the check digit is recomputed; it is not carried in the tag). */
    fun decodeEan(epc: String): String {
        val b = bits(epc)
        val company = b.substring(14, 34).toInt(2).toString().padStart(6, '0')
        val itemRef = b.substring(38, 58).toInt(2).toString().padStart(6, '0')
        val d = (company + itemRef).map { it - '0' }
        val sum = d.withIndex().sumOf { (i, x) -> if (i % 2 == 0) x else 3 * x }
        return company + itemRef + ((10 - sum % 10) % 10)
    }

    fun serial(epc: String): Long = bits(epc).substring(58).toLong(2)
}

/**
 * Receiving (2026-10-12): the unit-creating half of the restock loop. The `shipment_manifest` webhook only records EXPECTED
 * quantities; `POST /api/v2/inventory/items/receive` creates the units (core `InventoryActionService.receiveItems`,
 * FR-INV-07 auto-creates unknown EPCs). So: manifest (expected) -> receive (new units land in the stockroom) -> restock
 * moves (stockroom -> walls, [RestockMoves]).
 *
 * Per store and date, from the date's persisted sales plan and its sent ledger:
 *  1. the day's restock manifest lines, by [DatedStream.manifestLines] (the stream's own function): units sold per item
 *     code, rounded up to cases of 6. Stockroom sales count too (same rule as the manifest).
 *  2. each line is split over the EANs sold under that item code (a style code can carry two sizes): every EAN gets what it
 *     sold, the case-rounding surplus goes to the best-selling EAN, so the receipt totals exactly the manifest line.
 *  3. NEW EPCs are minted for those EANs: SGTIN-96 as the chain's own, with a serial from the RECEIVING BLOCK
 *     `[SERIAL_BASE, SERIAL_BASE + SERIAL_SPAN)` = [1,000,000,000 .. 11,000,000,000). The chain data's serials all lie in
 *     1,000..9,999,999, so the block cannot collide with it by construction; the draw is `SHA-256(seed, slug, date, ean,
 *     index, attempt)` (replay-stable), and a hit on any known EPC (epcs.csv, the sold log, earlier receipts, this run)
 *     re-draws with the next attempt. Index `k` of an EAN is its k-th unit that date: a re-run mints the same EPCs and a
 *     later run with more sales mints only the indices past what the ledger already holds.
 *
 * State (`.twin-state/receive/`, gitignored, written only after an accepted live send):
 *  - `receive-<yyyymmdd>-<seed>.sent`: the EPCs received for that date.
 *  - `received-stock.csv` (`epc,ean,slug,fixture,date`): every received unit, with the BR-* fixture it is booked to on the
 *    twin side. [RestockMoves] reads it as stockroom stock (lowest EPC first still applies); a placement overrides the
 *    fixture once a unit has moved to a wall.
 *
 * LIMITS of the request shape: `items/receive` carries `{siteId, spaceId, items:[{epc}]}` only. No zone (the unit lands in
 * the space; the BR-* fixture is the twin's bookkeeping), no `external_shipment_id` (receipts reconcile against the
 * manifest by product, not by id), and no product field (the platform resolves the product from the EPC).
 */
object ReceiveStock {
    /** First serial of the block reserved for receiving. Chain data tops out at 9,999,999. */
    const val SERIAL_BASE = 1_000_000_000L

    /** Width of the receiving block; base + span stays far below the 2^38 serial ceiling. */
    const val SERIAL_SPAN = 10_000_000_000L

    /**
     * Largest `items` list sent in one request. `M8TRX_RECEIVE_BATCH` (default 50): auto-creating a unit costs the
     * platform ~0.75 s, and a 200-EPC batch (saleNative's own cap) ran ~150 s — past the gateway's timeout, which
     * answered 504 while the server went on creating (measured 2026-10-12, Denver canary).
     */
    val BATCH_MAX: Int = System.getenv("M8TRX_RECEIVE_BATCH")?.toIntOrNull()?.takeIf { it in 1..200 } ?: 50

    data class ReceivedRow(val epc: String, val ean: String, val slug: String, val fixture: String, val date: LocalDate)

    data class StorePlan(
        val slug: String,
        val lines: List<ShipmentLine>,
        val targetUnits: Int,
        val alreadyReceived: Int,
        val toReceive: List<ReceivedRow>,
        val soldUnits: Int,
    ) {
        val units: Int get() = toReceive.size
    }

    /** The id the dated stream gives the store-day's `shipment_manifest` (`<runId>-<slug>-restock`). */
    fun manifestId(date: LocalDate, seed: Long, slug: String) = "twin-day-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}-$seed-$slug-restock"

    /**
     * Plan every store of [stores] for [date] from the persisted sales plan under [soldLog]'s state dir, in the order given,
     * each store's minted EPCs added to the taken set before the next. Empty (and says so) when the date has no plan.
     */
    fun planAll(chainDir: Path, soldLog: Path, date: LocalDate, seed: Long, stores: List<String>, log: org.slf4j.Logger): List<StorePlan> {
        val streamDir = soldLog.parent?.resolve("stream") ?: Path.of(".twin-state/stream")
        val planFile = RestockMoves.planFile(streamDir, date, seed)
        if (!Files.exists(planFile)) {
            log.info("  receive: no sales plan at {} - the sales have not been planned/sent; nothing to receive", planFile)
            return emptyList()
        }
        val sentIds = RestockMoves.readLedger(planFile.resolveSibling(planFile.fileName.toString().removeSuffix(".plan.csv") + ".sent"))
        val sales = DatedStream.readPlan(planFile)
        val dir = receiveDir(soldLog)
        val ledger = RestockMoves.readLedger(ledgerFile(dir, date, seed))
        val taken = HashSet<String>(readPriorSold(soldLog))
        val stock = readStock(stockFile(dir))
        stock.forEach { taken += it.epc }
        return stores.map { slug ->
            // the ledger is per date, not per store: this store's share is the stock rows it wrote that date
            val mine = stock.filter { it.slug == slug && it.date == date && it.epc in ledger }.map { it.epc }.toSet()
            plan(
                slug,
                date,
                seed,
                DatedStream.loadEpcRows(chainDir.resolve("stores/$slug/epcs.csv")),
                sales.filter { it.store == slug },
                sentIds,
                mine,
                taken,
            ).also { p -> p.toReceive.forEach { taken += it.epc } }
        }
    }

    /**
     * Pure plan for one store-day. [sales] are the date's plan rows for [slug]; [sentIds] the plan's sent ledger (only sent
     * sales count: a unit that has not sold has not left); [receivedThisDate] the EPCs this date's ledger already holds;
     * [taken] every EPC that must not be minted again (callers add each plan's minted EPCs before the next store).
     */
    fun plan(
        slug: String,
        date: LocalDate,
        seed: Long,
        rows: List<EpcRow>,
        sales: List<PlannedSale>,
        sentIds: Set<String>,
        receivedThisDate: Set<String>,
        taken: Set<String>,
        maxLines: Int = DatedStream.manifestMaxLines(),
    ): StorePlan {
        val byEpc = rows.distinctBy { it.epc }.associateBy { it.epc }
        val soldItem = HashMap<String, Int>()
        val soldEan = HashMap<String, Int>()
        val eansOfItem = HashMap<String, MutableSet<String>>()
        var soldUnits = 0
        for (s in sales) {
            if (s.store != slug || s.saleId !in sentIds) continue
            for (epc in s.epcs) {
                val r = byEpc[epc] ?: continue
                soldUnits++
                if (r.itemCd.isNotBlank()) {
                    soldItem.merge(r.itemCd, 1, Int::plus)
                    eansOfItem.getOrPut(r.itemCd) { sortedSetOf() }.add(r.ean)
                }
                soldEan.merge(r.ean, 1, Int::plus)
            }
        }
        val lines = DatedStream.manifestLines(soldItem, maxLines)
        // line -> per-EAN target: what each EAN sold, the case-rounding surplus on the best seller (ties: lowest EAN)
        val target = java.util.TreeMap<String, Int>()
        for (line in lines) {
            val eans = eansOfItem[line.sku].orEmpty().toList()
            val sold = eans.associateWith { soldEan[it] ?: 0 }
            val top = eans.sortedWith(compareBy({ -(sold[it] ?: 0) }, { it })).first()
            eans.forEach { target.merge(it, sold.getValue(it), Int::plus) }
            target.merge(top, line.expectedQuantity - sold.values.sum(), Int::plus)
        }
        val have = HashMap<String, Int>()
        receivedThisDate.forEach { epc -> have.merge(Sgtin96.decodeEan(epc), 1, Int::plus) }

        val brFixtures = rows.filter { it.backOfHouse }.map { it.fixture }.distinct().sorted().ifEmpty { listOf("BR-01") }
        val minted = HashSet<String>()
        val out = ArrayList<ReceivedRow>()
        for ((ean, want) in target) {
            val k = have[ean] ?: 0
            val fixture = brFixtures[((HistoryCsv.derive(seed, "receive-fixture", slug, ean) ushr 1) % brFixtures.size).toInt()]
            for (index in k until want) {
                var attempt = 0
                var epc: String
                do {
                    val serial = SERIAL_BASE + ((HistoryCsv.derive(seed, "receive", slug, date, ean, index, attempt++) ushr 1) % SERIAL_SPAN)
                    epc = Sgtin96.encode(ean, serial)
                } while (epc in taken || epc in minted || epc in byEpc)
                minted += epc
                out += ReceivedRow(epc, ean, slug, fixture, date)
            }
        }
        return StorePlan(
            slug = slug,
            lines = lines,
            targetUnits = lines.sumOf { it.expectedQuantity },
            alreadyReceived = receivedThisDate.size,
            toReceive = out,
            soldUnits = soldUnits,
        )
    }

    // ── state files ───────────────────────────────────────────────────────────

    fun receiveDir(soldLog: Path): Path = soldLog.parent?.resolve("receive") ?: Path.of(".twin-state/receive")

    fun ledgerFile(dir: Path, date: LocalDate, seed: Long): Path = dir.resolve("receive-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}-$seed.sent")

    fun stockFile(dir: Path): Path = dir.resolve("received-stock.csv")

    fun readStock(file: Path): List<ReceivedRow> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).drop(1).mapNotNull { l ->
            val c = l.split(",")
            if (c.size < 5 || c[0].isBlank()) null else ReceivedRow(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim(), LocalDate.parse(c[4].trim()))
        }
    }

    fun appendStock(file: Path, ledger: Path, rows: List<ReceivedRow>) {
        file.parent?.let { Files.createDirectories(it) }
        val header = if (Files.exists(file)) "" else "epc,ean,slug,fixture,date\n"
        Files.writeString(
            file,
            header + rows.joinToString("") { "${it.epc},${it.ean},${it.slug},${it.fixture},${it.date}\n" },
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        RestockMoves.appendLedger(ledger, rows.map { it.epc })
    }

    /** The received units of [slug] as stockroom rows for [RestockMoves.plan]. */
    fun stockRows(stock: List<ReceivedRow>, slug: String): List<EpcRow> = stock.filter { it.slug == slug }.map { EpcRow(it.epc, it.ean, it.fixture) }

    /** `slug,space_id` → the stockroom space each store receives into (a header line is skipped). */
    fun readSpaces(file: Path): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        return Files.readAllLines(file).filter { it.isNotBlank() && !it.startsWith("slug,") }.mapNotNull { l ->
            l.split(",").takeIf { it.size >= 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { it[0].trim() to it[1].trim() }
        }.toMap()
    }
}
