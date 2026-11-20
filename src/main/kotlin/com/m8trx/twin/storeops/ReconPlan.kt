package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.csvSplit
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.SpatialIdentityResponse
import com.m8trx.twin.connect.model.webhook.InventoryMovement
import com.m8trx.twin.connect.model.webhook.MovementItem
import com.m8trx.twin.connect.model.webhook.SaleEvent
import java.nio.file.Files
import java.nio.file.Path

/**
 * The reconcile sender's PLAN (pure: files in, [SendItem]s out; no socket). The twin's ledger was compared unit by unit with the platform; the
 * differences file lists where they disagree, and the owner agreed to fix the platform through the product (Connect), never server-side:
 *
 *  - **SALES** - class `c`, twin `sold`, platform `in_stock`: the twin counts the unit sold, the platform still holds it. Re-sent as a `sale_event`
 *    carrying the ORIGINAL `occurred_at`, found in the stream state ([ReconSaleIndex]).
 *  - **MOVES** - class `e`, the twin's location is a floor fixture or a back-room rack and the platform's differs (the platform's own
 *    `BACKROOM:(none)` rows are the racks kind).
 *  - **RACKS** - class `e`, twin `BACKROOM:<rack>`, platform `BACKROOM:(none)`: the put-away the platform never recorded.
 *
 * Every move goes by `to_zone_id` ONLY (the server resolves a fixture code only by exact zone name and refuses silently). A location that does not
 * resolve to a platform zone id is REFUSED and listed, never guessed.
 */
object Recon {
    const val BATCH_MAX = 25

    /** 16 a minute. */
    const val SPACING_MS = 3_750L
    const val NONE_LOCATION = "BACKROOM:(none)"
    const val BACKROOM = "BACKROOM:"

    enum class Kind(val tag: String, val route: String) {
        SALES("sale", "sale"),
        MOVES("mv", "move"),
        RACKS("rk", "move"),
        ;

        companion object {
            fun parse(s: String?): Kind = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("M8TRX_RECON_KIND must be sales, moves or racks, got '$s'")
        }
    }

    data class Row(
        val epc: String,
        val site: String,
        val twinState: String,
        val twinLocation: String,
        val platformState: String,
        val platformLocation: String,
        val cls: String,
    )

    /** The differences CSV (`epc,twin_site,twin_state,twin_location,twin_source,platform_site,platform_state,platform_location,...,class`). */
    fun readDiff(file: Path): List<Row> {
        val lines = Files.readAllLines(file)
        val h = csvSplit(lines.first()).map { it.trim() }
        fun ix(n: String) = h.indexOf(n).also { require(it >= 0) { "$file has no '$n' column" } }
        val iEpc = ix("epc")
        val iSite = ix("twin_site")
        val iTs = ix("twin_state")
        val iTl = ix("twin_location")
        val iPs = ix("platform_state")
        val iPl = ix("platform_location")
        val iC = ix("class")
        return lines.drop(1).filter { it.isNotBlank() }.map { l ->
            val c = csvSplit(l)
            Row(c[iEpc].trim(), c[iSite].trim(), c[iTs].trim(), c[iTl].trim(), c[iPs].trim(), c[iPl].trim(), c[iC].trim())
        }
    }

    /** The rows a [kind] acts on (before site filtering is applied by the caller's [sites]; empty [sites] = all). */
    fun select(kind: Kind, rows: List<Row>, sites: Set<String> = emptySet()): List<Row> = rows.filter { r ->
        (sites.isEmpty() || r.site in sites) &&
            when (kind) {
                Kind.SALES -> r.cls == "c" && r.twinState == "sold" && r.platformState == "in_stock"

                Kind.MOVES -> r.cls == "e" && r.twinLocation.isNotBlank() && r.twinLocation != NONE_LOCATION &&
                    r.platformLocation != NONE_LOCATION && r.platformLocation != r.twinLocation

                Kind.RACKS -> r.cls == "e" && r.twinLocation.startsWith(BACKROOM) && r.twinLocation != NONE_LOCATION &&
                    r.platformLocation == NONE_LOCATION
            }
    }

    /** Sites smallest first (by item count, then slug): "one store at a time, smallest first". */
    fun siteOrder(counts: Map<String, Int>): List<String> = counts.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }

    // ── zones ────────────────────────────────────────────────────────────────

    /** What a twin location name resolved to: the twin fixture [code] and the platform [zoneId]. */
    data class Zone(val code: String, val zoneId: String)

    /**
     * One store's name -> platform zone id. A twin location NAME is mapped to the twin fixture CODE by the store's `layout.json` (per space type),
     * then to the platform's zone by `/spatial/identity` (code first; the old rack rule for the stockroom: zone code `BR-nn`, else the name
     * "Backroom Fixture n"; floor fixtures by code else exact name). Anything else is unresolved.
     */
    class ZoneResolver(
        private val floorCodeByName: Map<String, String>,
        private val stockCodeByName: Map<String, String>,
        private val floorZones: List<Pair<String?, Pair<String?, String>>>,
        private val stockZones: List<Pair<String?, Pair<String?, String>>>,
    ) {
        private val floorByCode = floorZones.filter { !it.first.isNullOrBlank() }.associate { it.first!! to it.second.second }
        private val floorByName = floorZones.filter { !it.second.first.isNullOrBlank() }.associate { it.second.first!! to it.second.second }
        private val stockByCode = stockZones.filter { !it.first.isNullOrBlank() }.associate { it.first!! to it.second.second }
        private val stockByName = stockZones.filter { !it.second.first.isNullOrBlank() }.associate { it.second.first!! to it.second.second }

        /** The zone for a diff location string (`Gondola R4 Back U2` or `BACKROOM:Backroom Fixture 3`), or null. */
        fun resolve(location: String): Zone? {
            if (location.startsWith(BACKROOM)) {
                val name = location.removePrefix(BACKROOM)
                val code = stockCodeByName[name] ?: RACK_NAME.matchEntire(name)?.let { "BR-%02d".format(it.groupValues[1].toInt()) } ?: return null
                val id =
                    stockByCode[code] ?: stockByName[name]
                        ?: RACK_NAME.matchEntire(name)?.let { stockByName["Backroom Fixture ${it.groupValues[1]}"] }
                return id?.let { Zone(code, it) }
            }
            val code = floorCodeByName[location] ?: return null
            val id = floorByCode[code] ?: floorByName[location]
            return id?.let { Zone(code, it) }
        }

        companion object {
            private val RACK_NAME = Regex("(?i)^back\\s?room\\s+fixture\\s+(\\d+)$")

            /** [layout] is the store's `layout.json`, [identity] the `/spatial/identity` answer for the site (null or truncated refuses at the caller). */
            fun of(layout: JsonNode, identity: SpatialIdentityResponse): ZoneResolver {
                fun names(type: String): Map<String, String> = layout.path("spaces").filter { it.path("space_type").asText() == type }
                    .flatMap { it.path("zones").toList() }.associate { it.path("name").asText() to it.path("code").asText() }
                fun zones(type: String) = identity.sites.flatMap { it.spaces }.filter { it.spaceType == type }
                    .flatMap { it.zones }.map { it.code to (it.name to it.zoneId) }
                return ZoneResolver(names("sales_floor"), names("stockroom"), zones("sales_floor"), zones("stockroom"))
            }
        }
    }

    // ── moves ────────────────────────────────────────────────────────────────

    /** One planned item with what the frozen plan and the report need beyond the [SendItem]. */
    class Planned(val site: String, val item: SendItem, val destName: String, val destCode: String?, val occurredAt: String? = null)

    class MovePlan(val planned: List<Planned>, val unresolved: Map<String, Int>)

    /** Deterministic external id: a hash of the destination zone and the batch's EPCs (same input, same id, so the sent-ledger and the platform agree). */
    fun moveId(kind: Kind, slug: String, zoneId: String, epcs: List<String>) =
        "recon-${kind.tag}-$slug-${SendItem.hash(zoneId, epcs.joinToString(","))}"

    /**
     * Batches of at most [BATCH_MAX] EPCs, each to ONE destination, by `to_zone_id` only. Destinations in name order, EPCs sorted. A location that
     * does not resolve is collected in [MovePlan.unresolved] (location to unit count) and its rows are in no batch.
     */
    fun planMoves(kind: Kind, slug: String, rows: List<Row>, resolver: ZoneResolver, batchMax: Int = BATCH_MAX): MovePlan {
        require(batchMax in 1..BATCH_MAX) { "a move batch carries 1..$BATCH_MAX tags, got $batchMax" }
        val unresolved = LinkedHashMap<String, Int>()
        val out = ArrayList<Planned>()
        var seq = 0
        rows.groupBy { it.twinLocation }.toSortedMap().forEach { (loc, rs) ->
            val z = resolver.resolve(loc)
            if (z == null) {
                unresolved[loc] = (unresolved[loc] ?: 0) + rs.size
                return@forEach
            }
            rs.map { it.epc }.distinct().sorted().chunked(batchMax).forEach { epcs ->
                val id = moveId(kind, slug, z.zoneId, epcs)
                val mv =
                    InventoryMovement(externalMovementId = id, toFixtureCode = null, toZoneId = z.zoneId, items = epcs.map { MovementItem(epc = it) })
                out += Planned(slug, SendItem(seq++, 0, 1, "move", id, ConnectMappers.snake.valueToTree(mv), epcs), loc, z.code)
            }
        }
        return MovePlan(out, unresolved)
    }

    // ── sales ────────────────────────────────────────────────────────────────

    /** Why a unit the twin sold is still in stock on the platform: what the twin's own stream state says about its sale. */
    enum class Cause(val text: String) {
        STREAM_NOT_IN_LEDGER("in a stream plan, its sale id (or any part) is NOT in the sent-ledger: never acked"),
        STREAM_PART_ONLY("in a stream plan, the ledger holds only price-split part ids (`#n`) of its receipt, not a whole-receipt ack"),
        STREAM_ACKED("in a stream plan, the whole receipt id is in the sent-ledger (acked 2xx) yet the unit did not land"),
        ALARM_UNIT("an alarm (theft) unit: the sold-log was written, no sale_event was ever sent, there is no original sale time"),
        NO_RECORD("only the sold-log knows it: no stream plan, no alarm record, no original sale time"),
    }

    class Unsourced(val epc: String, val site: String, val cause: Cause)

    class SalePlan(val planned: List<Planned>, val unsourced: List<Unsourced>, val causes: Map<Cause, Int>)

    /** `<orig>-r1-<hash of the EPC set>` (like [moveId]): a resend of unlanded units gets a NEW external id (the platform dedupes a webhook receipt on `external_event_id`). */
    fun resendId(origSaleId: String, epcs: List<String>) = "$origSaleId-r1-${SendItem.hash(epcs.sorted().joinToString(","))}"

    fun derivedSaleId(slug: String, epcs: List<String>) = "recon-sale-$slug-${SendItem.hash(slug, epcs.sorted().joinToString(","))}-r1"

    /**
     * The sale resends. [index] says where each EPC's original sale is; [times] (`epc,occurred_at`, optional operator file) wins over everything and is
     * the ONLY way an alarm/no-record unit gets a time. Units of one original receipt go back as ONE event (`epc` or `epc_list`) under `<orig>-r1`,
     * carrying only the EPCs that did not land and the receipt's own `occurred_at`; a unit with no source is listed in [SalePlan.unsourced].
     */
    fun planSales(rows: List<Row>, index: ReconSaleIndex, times: Map<String, String> = emptyMap()): SalePlan {
        val causes = java.util.EnumMap<Cause, Int>(Cause::class.java)
        val unsourced = ArrayList<Unsourced>()
        val byReceipt = java.util.TreeMap<String, MutableList<Row>>()
        val receiptAt = HashMap<String, String>()
        val receiptStore = HashMap<String, String>()
        val single = ArrayList<Pair<Row, String>>()
        rows.sortedBy { it.epc }.forEach { r ->
            val o = index.originOf(r.epc)
            causes.merge(o.cause, 1, Int::plus)
            val override = times[r.epc]
            when {
                override != null -> single += r to override

                o.saleId != null && o.occurredAt != null -> {
                    byReceipt.getOrPut(o.saleId) { ArrayList() } += r
                    receiptAt[o.saleId] = o.occurredAt
                    receiptStore[o.saleId] = r.site
                }

                else -> unsourced += Unsourced(r.epc, r.site, o.cause)
            }
        }
        val out = ArrayList<Planned>()
        var seq = 0
        fun item(id: String, at: String, epcs: List<String>, slug: String): Planned {
            val ev = if (epcs.size == 1) SaleEvent.byEpc(id, at, epcs.single()) else SaleEvent.byEpcList(id, at, epcs)
            return Planned(slug, SendItem(seq++, 0, 2, "sale", id, ConnectMappers.snake.valueToTree(ev), epcs), "", null, at)
        }
        byReceipt.forEach { (sid, rs) ->
            out +=
                item(resendId(sid, rs.map { it.epc }), receiptAt.getValue(sid), rs.map { it.epc }.sorted(), receiptStore.getValue(sid))
        }
        single.forEach { (r, at) -> out += item(derivedSaleId(r.site, listOf(r.epc)), at, listOf(r.epc), r.site) }
        return SalePlan(
            out.sortedWith(compareBy({ it.site }, { it.item.extId })).mapIndexed { i, p ->
                Planned(p.site, SendItem(i, 0, 2, "sale", p.item.extId, p.item.payload, p.item.epcs), p.destName, p.destCode, p.occurredAt)
            },
            unsourced,
            causes,
        )
    }

    /** `epc,occurred_at` lines (comments and a header allowed). */
    fun readTimes(file: Path?): Map<String, String> = if (file == null || !Files.exists(file)) {
        emptyMap()
    } else {
        Files.readAllLines(file).map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() && !it.startsWith("epc") }.associate {
            val c = it.split(",").map(String::trim)
            require(c.size == 2) { "sale time line must be 'epc,occurred_at': '$it'" }
            c[0] to c[1]
        }
    }

    // ── frozen plan ──────────────────────────────────────────────────────────

    /** SHA-256 over the differences file's bytes, the kind and the sorted sites: what a frozen plan was built from. */
    fun planHash(diff: Path, kind: Kind, sites: Set<String>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(Files.readAllBytes(diff))
        md.update("|${kind.name}|${sites.sorted().joinToString(",")}".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The hash in the plan file's header line, or null when there is none. */
    fun readPlanHash(file: Path): String? = Files.readAllLines(file).firstOrNull()?.takeIf { it.isNotBlank() }
        ?.let { ConnectMappers.snake.readTree(it) }?.takeIf { it.path("header").asBoolean(false) }?.path("plan_hash")?.asText()

    fun writePlan(file: Path, planned: List<Planned>, hash: String = "") {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val lines = planned.map { p ->
            ConnectMappers.snake.createObjectNode().also {
                it.put("site", p.site)
                it.put("kind", p.item.kind)
                it.put("ext_id", p.item.extId)
                it.put("dest_name", p.destName)
                it.put("dest_code", p.destCode)
                it.put("occurred_at", p.occurredAt)
                it.set<JsonNode>("payload", p.item.payload)
            }.toString()
        }
        val header = ConnectMappers.snake.createObjectNode().also {
            it.put("header", true)
            it.put("plan_hash", hash)
        }.toString()
        Files.write(file, listOf(header) + lines)
    }

    fun readPlan(file: Path): List<Planned> = Files.readAllLines(file).filter {
        it.isNotBlank() && !it.startsWith("{\"header\"")
    }.mapIndexed { i, l ->
        val n = ConnectMappers.snake.readTree(l)
        val kind = n.path("kind").asText()
        val payload = n.path("payload")
        Planned(
            n.path("site").asText(),
            SendItem(i, 0, if (kind == "sale") 2 else 1, kind, n.path("ext_id").asText(), payload, SendItem.epcsOf(kind, payload)),
            n.path("dest_name").asText(),
            n.path("dest_code").takeIf { !it.isNull }?.asText(),
            n.path("occurred_at").takeIf { !it.isNull }?.asText(),
        )
    }
}

/**
 * Where the twin's own stream state says each EPC was sold: `<stream>/<run>.plan.csv` (`sale_id,store,occurred_at,epcs;..`) with the ledger
 * `<run>.sent` (ids of accepted sends: the receipt id, or `<id>#n` per price part), and the alarm plans the alarm dir's `plan.csv` files (`id,store,at,gate,epc,..`).
 * Read-only.
 */
class ReconSaleIndex private constructor(private val plans: Map<String, List<Receipt>>, private val alarmEpcs: Set<String>) {
    class Receipt(val run: String, val saleId: String, val occurredAt: String, val wholeAcked: Boolean, val partsAcked: Boolean)

    class Origin(val cause: Recon.Cause, val saleId: String?, val occurredAt: String?)

    /** The receipt of [epc]: the one the ledger holds if any (else the latest plan). */
    fun originOf(epc: String): Origin {
        val rs = plans[epc]
        if (rs.isNullOrEmpty()) {
            return Origin(if (epc in alarmEpcs) Recon.Cause.ALARM_UNIT else Recon.Cause.NO_RECORD, null, null)
        }
        val r = rs.firstOrNull { it.wholeAcked } ?: rs.firstOrNull { it.partsAcked } ?: rs.last()
        val cause = when {
            r.wholeAcked -> Recon.Cause.STREAM_ACKED
            r.partsAcked -> Recon.Cause.STREAM_PART_ONLY
            else -> Recon.Cause.STREAM_NOT_IN_LEDGER
        }
        return Origin(cause, r.saleId, r.occurredAt)
    }

    companion object {
        fun load(streamDir: Path, alarmDir: Path?): ReconSaleIndex {
            val plans = HashMap<String, MutableList<Receipt>>()
            if (Files.isDirectory(streamDir)) {
                Files.list(streamDir).use { s -> s.filter { it.fileName.toString().endsWith(".plan.csv") }.sorted().toList() }.forEach { f ->
                    val run = f.fileName.toString().removeSuffix(".plan.csv")
                    val sentFile = f.resolveSibling("$run.sent")
                    val sent = if (Files.exists(sentFile)) {
                        Files.readAllLines(sentFile).map {
                            it.trim()
                        }.filter { it.isNotEmpty() }.toSet()
                    } else {
                        emptySet()
                    }
                    Files.readAllLines(f).drop(1).filter { it.isNotBlank() }.forEach { l ->
                        val c = l.split(",")
                        if (c.size < 4) return@forEach
                        val id = c[0]
                        val rc = Receipt(run, id, c[2], id in sent, sent.any { it.startsWith("$id#") })
                        c[3].split(";").filter { it.isNotBlank() }.forEach { e -> plans.getOrPut(e) { ArrayList() } += rc }
                    }
                }
            }
            val alarms = HashSet<String>()
            if (alarmDir != null && Files.isDirectory(alarmDir)) {
                Files.list(alarmDir).use { s -> s.filter { it.fileName.toString().endsWith(".plan.csv") }.toList() }.forEach { f ->
                    Files.readAllLines(f).drop(1).forEach { l -> l.split(",").getOrNull(4)?.takeIf { it.isNotBlank() }?.let(alarms::add) }
                }
            }
            return ReconSaleIndex(plans, alarms)
        }

        /** For tests: an index from explicit parts. */
        fun of(receipts: Map<String, List<Receipt>>, alarmEpcs: Set<String> = emptySet()) = ReconSaleIndex(receipts, alarmEpcs)
    }
}

/**
 * Reads a webhook 2xx body. The twin-pos webhook answers 200 and processes ASYNC: a destination it cannot resolve is NOT reported in the body
 * (`destination_unresolved` still answers 200), so no body can prove a move applied. "delivered" is all a 2xx means; verification is the re-run
 * comparison against the platform, which the operator does after each site. What this does: [refusal] flags a body that SAYS something was not
 * taken (the lane halts), and [isPlain] says whether a body is a plain success (anything else is logged in full).
 */
object WebhookAnswer {
    private val NON_EMPTY_KEYS = listOf(
        "errors", "error", "refused", "rejected", "unresolved", "quarantined", "failed", "refused_items", "refusedItemIds", "refused_item_ids",
        "skipped", "ignored", "unknown_epcs", "not_found",
    )
    private val BAD_STATUS = setOf("error", "rejected", "quarantined", "refused", "failed", "unresolved", "partial")
    private val BENIGN_KEYS =
        setOf("status", "success", "ok", "received", "accepted", "queued", "id", "event_id", "external_event_id", "message", "duplicate")

    private fun parse(rawBody: String?): JsonNode? = try {
        if (rawBody.isNullOrBlank()) null else ConnectMappers.snake.readTree(rawBody)
    } catch (e: java.io.IOException) {
        null
    }

    /** Null when the body shows nothing refused (a blank or non-JSON body is a bare ack). An array body, `success:false`, `ok:false`, `accepted:0`, a non-empty skipped/ignored/refused list: refused. */
    fun refusal(rawBody: String?): String? {
        if (rawBody.isNullOrBlank()) return null
        val n = parse(rawBody)
        if (n == null) return if (rawBody.contains("unresolved", ignoreCase = true)) rawBody.take(200) else null
        if (n.isArray) return "array body: ${n.toString().take(200)}"
        return if (n.isObject) scan(n, 0) else null
    }

    /** True for a blank body or an object with only benign keys and no refusal marker. Anything else is logged in full by the sender. */
    fun isPlain(rawBody: String?): Boolean {
        if (rawBody.isNullOrBlank()) return true
        val n = parse(rawBody) ?: return false
        return n.isObject && refusal(rawBody) == null && n.fieldNames().asSequence().all { it in BENIGN_KEYS }
    }

    private fun scan(n: JsonNode, depth: Int): String? {
        if (depth > 3) return null
        for (k in NON_EMPTY_KEYS) {
            val v = n.path(k)
            if ((v.isArray || v.isObject) && v.size() > 0) return "$k: ${v.toString().take(200)}"
            if (v.isNumber && v.asInt() > 0) return "$k=${v.asInt()}"
        }
        for (k in listOf("success", "ok")) if (n.path(k).isBoolean && !n.path(k).asBoolean()) return "$k=false ${n.toString().take(160)}"
        if (n.path("accepted").isNumber && n.path("accepted").asInt() == 0) return "accepted=0 ${n.toString().take(160)}"
        val st = n.path("status").asText("").lowercase()
        if (st in BAD_STATUS) return "status=$st ${n.toString().take(160)}"
        val code = n.path("code").asText("").lowercase()
        if (code.contains("unresolved") || code.contains("refused")) return "code=$code"
        for (c in listOf("data", "result", "answer")) if (n.path(c).isObject) scan(n.path(c), depth + 1)?.let { return it }
        return null
    }
}
