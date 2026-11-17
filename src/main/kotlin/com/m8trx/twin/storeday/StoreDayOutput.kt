package com.m8trx.twin.storeday

import com.m8trx.twin.behavior.Visit
import com.m8trx.twin.connect.model.ConnectMappers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.TreeMap

/** Everything one store day writes: the wire plan and the truth ledger (never sent anywhere). */
object StoreDayOutput {
    class HourRow {
        var impressions = 0
        var dwellMs = 0L
        var pickups = 0
        var putBacks = 0
        var tryOns = 0
        var units = 0
        var valueUsd = 0.0
        var stockOuts = 0
        var lostSales = 0
        var lostValueUsd = 0.0
    }

    /** The per-fixture-hour truth, also what the self-test reads. */
    fun fixtureHours(sim: StoreDaySim, r: DayResult): TreeMap<Pair<Int, String>, HourRow> {
        val zone = ZoneId.of(sim.model.timezone)
        fun hour(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).hour
        val rows = TreeMap<Pair<Int, String>, HourRow>(compareBy({ it.first }, { it.second }))
        fun row(ms: Long, fixture: String) = rows.getOrPut(hour(ms) to fixture) { HourRow() }
        r.records.forEach { rec ->
            rec.visits.forEach { v ->
                if (v.impression) {
                    row(v.startMs, v.fixture).also {
                        it.impressions++
                        it.dwellMs += v.dwellMs
                    }
                }
                if (v.kind == Visit.Kind.TRY_ON) {
                    v.triedCell?.let { row(v.startMs, it.fixture).tryOns++ }
                } else {
                    row(v.startMs, v.fixture).also {
                        it.pickups += v.pickups.size
                        it.putBacks += v.pickups.count { p -> !p.bought }
                    }
                }
            }
            rec.lines.forEach { l ->
                row(rec.exitMs, l.cell.fixture).also {
                    it.units++
                    it.valueUsd += l.cell.priceUsd
                }
            }
            rec.stockOuts.forEach { o ->
                row(o.atMs, o.cell.fixture).also {
                    it.stockOuts++
                    if (o.lost) {
                        it.lostSales++
                        it.lostValueUsd += o.cell.priceUsd
                    }
                }
            }
        }
        return rows
    }

    fun write(dir: Path, sim: StoreDaySim, r: DayResult) {
        val truth = dir.resolve("truth")
        Files.createDirectories(truth)
        val zone = ZoneId.of(sim.model.timezone)
        val date = r.date

        // wire plan ----------------------------------------------------------------------------------------
        Files.newBufferedWriter(dir.resolve("wire-plan.jsonl")).use { w ->
            r.wire.forEach { e ->
                val node = ConnectMappers.snake.createObjectNode()
                node.put("due_utc", Instant.ofEpochMilli(e.dueMs).toString())
                node.put("kind", e.kind)
                node.set<com.fasterxml.jackson.databind.JsonNode>("payload", ConnectMappers.snake.valueToTree(e.payload))
                node.set<com.fasterxml.jackson.databind.JsonNode>("ids", ConnectMappers.snake.valueToTree(e.ids))
                w.write(ConnectMappers.snake.writeValueAsString(node))
                w.write("\n")
            }
        }
        r.samples?.let { sink ->
            Files.newBufferedWriter(dir.resolve("samples.jsonl")).use { w ->
                r.records.forEach { rec ->
                    val s = sink.streams[rec.id] ?: return@forEach
                    val arr = s.joinToString(",", "[", "]") { "[${it.tsMs},${"%.0f".format(it.p.x)},${"%.0f".format(it.p.y)}]" }
                    w.write("{\"shopper\":\"${rec.id}\",\"samples\":$arr}\n")
                }
            }
        }

        // truth: fixture x hour ----------------------------------------------------------------------------------
        Files.newBufferedWriter(truth.resolve("fixture_hour.csv")).use { w ->
            w.write(
                "date,hour,fixture,impressions,dwell_s,pickups,put_backs,try_ons,units_sold,value_sold_usd,stock_outs,lost_sales,lost_value_usd\n",
            )
            fixtureHours(sim, r).forEach { (k, v) ->
                w.write(
                    "$date,${k.first},${k.second},${v.impressions},${v.dwellMs / 1000},${v.pickups},${v.putBacks},${v.tryOns},${v.units}," +
                        "${"%.2f".format(v.valueUsd)},${v.stockOuts},${v.lostSales},${"%.2f".format(v.lostValueUsd)}\n",
                )
            }
        }

        // truth: fixture x product x day ------------------------------------------------------------------------
        val fp = TreeMap<Pair<String, String>, Pair<Int, Double>>(compareBy({ it.first }, { it.second }))
        r.saleUnits.forEach { u -> fp.merge(u.fixture to u.ean, 1 to u.priceUsd) { a, b -> (a.first + b.first) to (a.second + b.second) } }
        Files.newBufferedWriter(truth.resolve("fixture_product_day.csv")).use { w ->
            w.write("date,fixture,ean,units_sold,value_sold_usd\n")
            fp.forEach { (k, v) -> w.write("$date,${k.first},${k.second},${v.first},${"%.2f".format(v.second)}\n") }
        }

        // truth: on hand vs plan -----------------------------------------------------------------------------------
        val slots = (sim.model.required.keys + r.openOnHand.keys + r.closeOnHand.keys).toSortedSet()
        Files.newBufferedWriter(truth.resolve("onhand.csv")).use { w ->
            w.write("date,fixture,ean,plan_qty,open_qty,close_qty\n")
            slots.forEach { s ->
                w.write("$date,${s.fixture},${s.ean},${sim.model.required[s] ?: 0},${r.openOnHand[s] ?: 0},${r.closeOnHand[s] ?: 0}\n")
            }
        }

        // truth: zone -> zone flows per hour ----------------------------------------------------------------------
        val zoneOf = sim.behavior.fixtures.fixtures.associate { it.code to (it.inAreaZone ?: it.code) }
        val flows = TreeMap<Triple<Int, String, String>, Int>(compareBy({ it.first }, { it.second }, { it.third }))
        r.records.forEach { rec ->
            var at = "DOOR"
            rec.visits.sortedBy { it.startMs }.forEach { v ->
                val z = zoneOf[v.fixture] ?: v.fixture
                if (z != at) flows.merge(Triple(Instant.ofEpochMilli(v.startMs).atZone(zone).hour, at, z), 1, Int::plus)
                at = z
            }
            flows.merge(Triple(Instant.ofEpochMilli(rec.exitMs).atZone(zone).hour, at, "DOOR"), 1, Int::plus)
        }
        Files.newBufferedWriter(truth.resolve("flows.csv")).use { w ->
            w.write("date,hour,from_zone,to_zone,shoppers\n")
            flows.forEach { (k, n) -> w.write("$date,${k.first},${k.second},${k.third},$n\n") }
        }

        // truth: summary ---------------------------------------------------------------------------------------------
        Files.writeString(
            truth.resolve("summary.json"),
            ConnectMappers.snake.writerWithDefaultPrettyPrinter().writeValueAsString(summary(sim, r)) + "\n",
        )
    }

    fun summary(sim: StoreDaySim, r: DayResult): Map<String, Any> {
        val tx = r.records.count { it.bought }
        val units = r.saleUnits.size
        val lostSales = r.records.sumOf { rec -> rec.stockOuts.count { it.lost } }
        val moveKinds = r.moves.groupBy { it.reason }.mapValues { (_, ms) -> mapOf("batches" to ms.size, "units" to ms.sumOf { it.epcs.size }) }
        return linkedMapOf(
            "store" to sim.model.slug,
            "date" to r.date.toString(),
            "expected_visitors" to Math.round(r.expectedVisitors * 10) / 10.0,
            "visitors" to r.records.size,
            "transactions" to tx,
            "conversion" to if (r.records.isEmpty()) 0.0 else Math.round(tx.toDouble() / r.records.size * 10_000) / 10_000.0,
            "units_sold" to units,
            "value_sold_usd" to Math.round(r.saleUnits.sumOf { it.priceUsd } * 100) / 100.0,
            "stock_out_events" to r.records.sumOf { it.stockOuts.size },
            "lost_sales" to lostSales,
            "lost_value_usd" to Math.round(r.records.sumOf { rec -> rec.stockOuts.filter { it.lost }.sumOf { it.cell.priceUsd } } * 100) / 100.0,
            "deliveries" to mapOf("receive_requests" to r.receiveRequests, "units_received" to r.receivedEpcs.size),
            "moves" to moveKinds,
            "misplacements" to r.misplacements,
            "units_start" to r.startUnits,
            "units_end" to r.endUnits,
            "floor_units_open" to r.openOnHand.entries.filter { it.key in sim.model.required }.sumOf { it.value },
            "floor_units_close" to r.closeOnHand.entries.filter { it.key in sim.model.required }.sumOf { it.value },
        )
    }
}
