package com.m8trx.twin.history

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.DatedStream.EpcRow
import com.m8trx.twin.connect.DatedStream.PlannedSale
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.connect.csvSplit
import com.m8trx.twin.connect.readPriorSold
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Stock-depth file (2026-10-12): the NEW stockroom units a numbered migration loads so `RestockMoves` can refill every wall
 * that sells Oct 5 -> Oct 16. OFFLINE, writes only under `out/stock-depth-2026-10/`; `.twin-state` is read, never written.
 *
 * Per (store, ean): demand = units sold FROM A WALL (a non-`BR-*` fixture, the rule `RestockMoves.plan` applies).
 * Oct 5..12 come from the persisted plans (ALL planned sales, sent or not: the unsent ones sell later today and are owed a
 * restock; the sent-only count is reported beside it). Oct 13..16 are generated the way `DatedStream` does a dry run (same
 * seed, `RecentDay` + `DatedStream.allocate`), each day excluding the sold log, every persisted plan and the earlier dry days.
 * Supply = `BR-*` units in epcs.csv not sold, not reserved by a persisted plan, not placed; then less the stockroom units the
 * Oct 13..16 plans sell (stockroom sells first), since those are gone before the restock runs. New = max(0, demand - supply) + 6.
 * EPCs: `Sgtin96` from the receiving block, `SHA-256(seed, "stock-depth", slug, ean, index, attempt)`, unique (asserted).
 *
 * Env: `M8TRX_STOCK_SEED` (20261016) · `M8TRX_STOCK_FROM` / `M8TRX_STOCK_TO` (2026-10-05 / 2026-10-16) · `M8TRX_STOCK_PERSISTED_TO`
 * (2026-10-12) · `M8TRX_STOCK_MARGIN` (6) · `M8TRX_STOCK_OUT` · `M8TRX_STREAM_SOLD_LOG` · `M8TRX_CHAIN_DIR`.
 */
object StockDepthReport {
    private const val OUT = "out/stock-depth-2026-10"
    private val ymd = DateTimeFormatter.BASIC_ISO_DATE

    class Tally {
        var wallSent = 0
        var wallAll = 0
        var wallDry = 0
        var bohDry = 0
    }

    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    private fun sha256(f: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)).joinToString("") { "%02x".format(it) }

    private fun git(vararg a: String) = runCatching {
        ProcessBuilder("git", *a).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().trim().also { p.waitFor() } }
    }.getOrDefault("?")

    @JvmStatic
    fun main(args: Array<String>) {
        val seed = env("M8TRX_STOCK_SEED")?.toLong() ?: 20261016L
        val from = LocalDate.parse(env("M8TRX_STOCK_FROM") ?: "2026-10-05")
        val to = LocalDate.parse(env("M8TRX_STOCK_TO") ?: "2026-10-16")
        val persistedTo = LocalDate.parse(env("M8TRX_STOCK_PERSISTED_TO") ?: "2026-10-12")
        val margin = env("M8TRX_STOCK_MARGIN")?.toInt() ?: 6
        val out = Path.of(env("M8TRX_STOCK_OUT") ?: OUT)
        val chain = Path.of(env("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
        val soldLog = Path.of(env("M8TRX_STREAM_SOLD_LOG") ?: ".twin-state/sold-epcs.txt")
        val streamDir = soldLog.parent.resolve("stream")
        val sold = readPriorSold(soldLog)
        val placements = RestockMoves.readPlacements(RestockMoves.movesDir(soldLog).resolve("placements.csv"))
        val recent = RecentDay(seed, chain)
        val slugs = recent.stores.map { it.slug }
        val rows = slugs.associateWith { DatedStream.loadEpcRows(chain.resolve("stores/$it/epcs.csv")) }
        val catalogs = slugs.associateWith { DatedStream.loadCatalog(chain.resolve("stores/$it/assortment.csv")) }
        val itemCd = slugs.associateWith { s -> csvRows(chain.resolve("stores/$s/assortment.csv")).associate { it["ean"]!! to it["item_cd"]!! } }
        val names = slugs.associateWith { s -> csvRows(chain.resolve("stores/$s/assortment.csv")).associate { it["ean"]!! to it["name_en"]!! } }
        val brCodes = slugs.associateWith { layoutBr(chain.resolve("stores/$it/layout.json")) }

        // persisted plans (Oct 5..persistedTo) -> sales + sent ids; reserved = every EPC any persisted plan holds
        val reserved = HashSet<String>()
        val demand = HashMap<Pair<String, String>, Tally>()
        fun count(s: PlannedSale, sent: Boolean?, dry: Boolean) {
            val byEpc = byEpcOf[s.store]!!
            for (epc in s.epcs) {
                val r = byEpc[epc] ?: continue
                val boh = (placements[epc] ?: r.fixture).startsWith("BR-")
                val t = demand.getOrPut(s.store to r.ean) { Tally() }
                when {
                    dry && boh -> t.bohDry++

                    boh -> Unit

                    dry -> t.wallDry++

                    else -> {
                        t.wallAll++
                        if (sent == true) t.wallSent++
                    }
                }
            }
        }
        var d = from
        while (!d.isAfter(persistedTo)) {
            val pf = RestockMoves.planFile(streamDir, d, seed)
            check(Files.exists(pf)) { "no persisted plan for $d at $pf" }
            val sentIds = RestockMoves.readLedger(pf.resolveSibling(pf.fileName.toString().removeSuffix(".plan.csv") + ".sent"))
            DatedStream.readPlan(pf).forEach { s ->
                reserved += s.epcs
                count(s, s.saleId in sentIds, false)
            }
            d = d.plusDays(1)
        }
        // dry-generated days: the stream's own allocation, excluding sold + persisted + earlier dry days
        val exclude = HashSet<String>(sold).apply { addAll(reserved) }
        d = persistedTo.plusDays(1)
        while (!d.isAfter(to)) {
            val runId = "twin-day-${d.format(ymd)}-$seed"
            for (slug in slugs) {
                val day = recent.run(slug, d) ?: continue
                val plan = DatedStream.allocate(day, rows[slug]!!, exclude, runId, catalogs[slug]!!)
                plan.sales.forEach { s ->
                    exclude += s.epcs
                    count(s, null, true)
                }
            }
            d = d.plusDays(1)
        }

        // supply and the new units
        val taken = HashSet<String>(sold).apply { rows.values.forEach { r -> r.forEach { add(it.epc) } } }
        val minted = HashSet<String>()
        val newRows = ArrayList<List<String>>()
        val perStore = LinkedHashMap<String, IntArray>() // demand, demandSent, supplyToday, supplyNet, new, eans
        val perEan = HashMap<String, Int>()
        val unresolved = ArrayList<String>()
        for (slug in slugs) {
            val r = rows[slug]!!
            val unknown = r.filter { it.backOfHouse }.map { it.fixture }.toSet() - brCodes[slug]!!
            check(unknown.isEmpty()) { "$slug epcs.csv BR fixtures not in layout.json: $unknown" }
            val free = r.filter { it.backOfHouse && it.epc !in sold && it.epc !in reserved && it.epc !in placements }
            val supply = free.groupingBy { it.ean }.eachCount()
            val allBr = (r.filter { it.backOfHouse }.map { it.fixture } + brCodes[slug]!!).distinct().sorted()
            val tot = IntArray(6)
            for (ean in demand.keys.filter { it.first == slug }.map { it.second }.sorted()) {
                val t = demand[slug to ean]!!
                val dem = t.wallAll + t.wallDry
                val net = (supply[ean] ?: 0) - t.bohDry
                val n = maxOf(0, dem - net) + margin
                val code = itemCd[slug]!![ean]
                if (code == null) {
                    unresolved += "$slug $ean"
                    continue
                }
                val fixture = r.filter { it.backOfHouse && it.ean == ean }.minOfOrNull { it.fixture } ?: allBr.first()
                check(fixture in brCodes[slug]!!) { "$slug $ean fixture $fixture not a stockroom zone" }
                for (index in 0 until n) {
                    var attempt = 0
                    var epc: String
                    do {
                        val serial = ReceiveStock.SERIAL_BASE +
                            ((HistoryCsv.derive(seed, "stock-depth", slug, ean, index, attempt++) ushr 1) % ReceiveStock.SERIAL_SPAN)
                        epc = Sgtin96.encode(ean, serial)
                    } while (epc in taken || epc in minted)
                    check(Sgtin96.decodeEan(epc) == ean) { "decode mismatch $epc" }
                    minted += epc
                    newRows += listOf(slug, ean, epc, fixture, "", code)
                }
                tot[0] += dem
                tot[1] += t.wallSent + t.wallDry
                tot[2] += supply[ean] ?: 0
                tot[3] += net
                tot[4] += n
                tot[5]++
                perEan.merge(ean, n, Int::plus)
            }
            perStore[slug] = tot
        }
        check(newRows.map { it[2] }.toSet().size == newRows.size) { "duplicate minted EPC" }

        Files.createDirectories(out)
        val csv = out.resolve("stock_depth.csv")
        Files.write(csv, listOf("site_slug,ean,epc,fixture,zone_id,product_item_code") + newRows.map { it.joinToString(",") })
        val fx = out.resolve("stockroom_fixtures.csv")
        Files.write(fx, listOf("site_slug,fixture_code") + slugs.flatMap { s -> brCodes[s]!!.sorted().map { "$s,$it" } })
        val now = Instant.now().toString()
        val sha = git("rev-parse", "HEAD")
        val dirty = git("status", "--porcelain", "--", "src").isNotEmpty()
        Files.write(
            out.resolve("MANIFEST.csv"),
            listOf("file,rows,sha256,seed,generated_at,git_sha") +
                listOf(csv, fx).map {
                    "${it.fileName},${Files.readAllLines(it).size - 1},${sha256(it)},$seed,$now,$sha${if (dirty) "+dirty" else ""}"
                },
        )
        val serials = newRows.map { Sgtin96.serial(it[2]) }
        writeSummary(out, perStore, perEan, names, serials, unresolved, margin, from, to, persistedTo)
        println(
            "stock-depth: ${newRows.size} units, ${perStore.size} stores, serials ${serials.minOrNull()}..${serials.maxOrNull()}, unresolved ${unresolved.size}",
        )
    }

    private val byEpcOf: Map<String, Map<String, EpcRow>> by lazy {
        val chain = Path.of(env("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
        Files.list(chain.resolve("stores")).use { s -> s.toList() }.associate {
            it.fileName.toString() to DatedStream.loadEpcRows(it.resolve("epcs.csv")).distinctBy { r -> r.epc }.associateBy { r -> r.epc }
        }
    }

    private fun csvRows(f: Path): List<Map<String, String>> {
        val lines = Files.readAllLines(f)
        val h = csvSplit(lines.first())
        return lines.drop(1).filter { it.isNotBlank() }.map { l -> csvSplit(l).let { c -> h.indices.associate { h[it] to c.getOrElse(it) { "" } } } }
    }

    /** BR-* rack codes of the store's stockroom space (layout.json is keyed by code; no zone ids live in the repo). */
    private fun layoutBr(f: Path): Set<String> =
        Regex("\"code\":\\s*\"(BR-\\d+)\"").findAll(Files.readString(f)).map { it.groupValues[1] }.toSortedSet()

    private fun writeSummary(
        out: Path,
        perStore: Map<String, IntArray>,
        perEan: Map<String, Int>,
        names: Map<String, Map<String, String>>,
        serials: List<Long>,
        unresolved: List<String>,
        margin: Int,
        from: LocalDate,
        to: LocalDate,
        persistedTo: LocalDate,
    ) {
        val sb =
            StringBuilder(
                "# Stock depth, $from to $to\n\nWall demand from the persisted plans ($from..$persistedTo, all planned sales) and dry-generated plans ",
            )
        sb.append(
            "(${persistedTo.plusDays(
                1,
            )}..$to). New = max(0, demand - net supply) + $margin. Net supply = free stockroom units less the stockroom units ",
        )
        sb.append("the dry days sell. Sent-only demand = sent persisted sales + dry days.\n\n")
        sb.append(
            "| store | demand units | of which sent/dry | supply today | net supply | new units | distinct EANs |\n|---|---:|---:|---:|---:|---:|---:|\n",
        )
        perStore.forEach { (s, t) -> sb.append("| $s | ${t[0]} | ${t[1]} | ${t[2]} | ${t[3]} | ${t[4]} | ${t[5]} |\n") }
        val sum = (0..5).map { i -> perStore.values.sumOf { it[i] } }
        sb.append("| **chain** | ${sum[0]} | ${sum[1]} | ${sum[2]} | ${sum[3]} | ${sum[4]} | ${sum[5]} |\n\n")
        sb.append("Serial range used: ${serials.minOrNull()}..${serials.maxOrNull()} (receiving block ${ReceiveStock.SERIAL_BASE}..")
        sb.append("${ReceiveStock.SERIAL_BASE + ReceiveStock.SERIAL_SPAN}). EANs with no item code: ${unresolved.size}.\n\n")
        sb.append("## Top 10 EANs by new units (chain)\n\n| EAN | name | new units |\n|---|---|---:|\n")
        perEan.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(10).forEach { (e, n) ->
            sb.append("| $e | ${names.values.firstNotNullOfOrNull { it[e] }?.replace("|", "/")} | $n |\n")
        }
        sb.append("\n## Notes\n\n- Margin units: ${perStore.values.sumOf { it[5] } * margin} of ${sum[4]} ($margin per store and EAN).\n")
        sb.append(
            "- `zone_id` is BLANK: neither keys-2026-10-11.csv nor fixture_ids.csv carries stockroom (BR-*) zones, and layout.json has codes only. ",
        )
        sb.append("The loader joins (site_slug, fixture) to the stockroom zone; `stockroom_fixtures.csv` lists the 62 racks.\n")
        Files.writeString(out.resolve("summary.md"), sb.toString())
    }
}
