package com.m8trx.twin.storeops

import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.storeday.StoreState
import com.m8trx.twin.storeday.buildSim
import java.nio.file.Files
import java.nio.file.Path

/**
 * `./gradlew storeDayReceiveProbe` — DESIGN ONLY, NOT RUN, and it cannot send: it prints the exact calls the one-unit probe would make
 * and what it would check (B/W A16, known gap F-9: a received item gets NO product and NO location on the platform). The reset's live
 * flag refuses to run unless a recorded probe result says the probe passed ([refusal]).
 *
 * The probe, on Denver, one EPC (the last serial of the store's delivery block, a real Denver EAN):
 *  1. `POST /api/v2/inventory/items/receive` {siteId, spaceId, items:[{epc}]}, key `M8TRX_TWIN_SERVICE_BEARER`. Expect 2xx.
 *  2. read it back: `POST /api/v2/inventory/items/details` {epcs:[epc]} (inventory:read) and, as the store manager, one Hasura read of its
 *     `thing_location`. CHECK: the item exists and is in stock; its PRODUCT resolves to the EAN's product (F-9: expected to fail today);
 *     its LOCATION is stated (F-9: expected none).
 *  3. move it: webhook `inventory_movement`, `to_zone_id` = the sales-floor fixture zone of the EAN's first plan cell. Expect accepted.
 *  4. read it back again. CHECK: the location is that zone.
 *  5. sell it: webhook `sale_event` by EPC with an external id of the probe's. Expect accepted.
 *  6. read the sale line (Hasura `sale_line` by the sale's external id). CHECK: the product is resolved, the price is the catalog's, and
 *     `zone_id` is the zone it stood in at ingest (A1: stamped from thing_location).
 * Any failed check = the reset cannot run through receive until the core fixes F-9; tell the Backend/Web lane (it moves to the top of
 * their list). The result is recorded in [resultFile] as JSON by whoever runs the probe: `{"passed":true,"at":"<iso>","epc":"...","checks":{...}}`.
 */
object ReceiveProbe {
    class Check(val step: Int, val call: String, val checks: String)

    fun resultFile(stateDir: Path, slug: String): Path = stateDir.resolve("reset/probe-result-$slug.json")

    /** The probe's one EPC: the last serial of the store's delivery block, so it can never meet a minted or chain EPC. */
    fun probeEpc(slug: String, ean: String): String = Sgtin96.encode(ean, StoreState.deliveryEnd(slug) - 1)

    fun plan(slug: String): List<Check> {
        val sim = buildSim(slug)
        val ean = sim.model.demand.keys.sorted().first { e -> sim.model.lines.any { it.ean == e } }
        val cell = sim.model.lines.first { it.ean == ean }
        val epc = probeEpc(slug, ean)
        return listOf(
            Check(
                1,
                "POST /api/v2/inventory/items/receive  {siteId, spaceId(stockroom), items:[{epc:$epc}]}  (M8TRX_TWIN_SERVICE_BEARER)",
                "2xx; received=1 created=1",
            ),
            Check(
                2,
                "POST /api/v2/inventory/items/details  {epcs:[$epc]}  + Hasura thing_location for the item (store manager, read-only)",
                "item in stock; product resolves to EAN $ean (F-9: expected to FAIL today); location stated (F-9: expected none)",
            ),
            Check(
                3,
                "webhook inventory_movement  {external_movement_id:probe-move, to_zone_id:<zone of ${cell.fixture}>, items:[{epc:$epc}]}",
                "accepted",
            ),
            Check(4, "POST /items/details + thing_location again", "location = the zone of ${cell.fixture}"),
            Check(5, "webhook sale_event  {external_sale_id:probe-sale, occurred_at:<now>, epc:$epc}", "accepted"),
            Check(
                6,
                "Hasura sale_line by external sale id",
                "product resolved; price = the catalog's; zone_id = the zone it stood in at ingest (A1)",
            ),
        )
    }

    /** Null when [file] records a passed probe; otherwise why the live reset must refuse. */
    fun refusal(file: Path): String? {
        if (!Files.exists(
                file,
            )
        ) {
            return "no receive-probe result at $file: run the one-unit probe (storeDayReceiveProbe lists it) and record it first (F-9)"
        }
        val n = runCatching { ConnectMappers.snake.readTree(Files.readAllBytes(file)) }.getOrNull() ?: return "receive-probe result $file is not JSON"
        if (!n.path("passed").asBoolean(false)) return "the recorded receive probe at $file did not pass (F-9): the reset cannot run through receive"
        if (n.path("checks").fields().asSequence().any { !it.value.asBoolean(false) }) return "the receive probe at $file records a failed check"
        return null
    }
}

fun main() {
    val slug = System.getenv("M8TRX_PROBE_SITE")?.takeIf { it.isNotBlank() } ?: "dec-us-denver"
    println("storeDayReceiveProbe for $slug: DESIGN ONLY. Nothing is sent. These are the calls it WOULD make:")
    ReceiveProbe.plan(slug).forEach { println("  ${it.step}. ${it.call}\n     check: ${it.checks}") }
    println("record the outcome in ${ReceiveProbe.resultFile(Path.of(".twin-state"), slug)}; the reset's live flag refuses without a passing result")
}
