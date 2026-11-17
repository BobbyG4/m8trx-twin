package com.m8trx.twin.storeops

import java.nio.file.Files
import java.nio.file.Path

/**
 * `./gradlew storeDayGapCheck` — the gap ledger's READ-ONLY design (Phase 3 brief § 3, plan § 5.1). In this slice it makes no
 * network call and compares nothing: it lists each signal the store-day truth ledger records, the platform read surface the twin
 * would use to check it (or "no surface"), and writes the planned check list and an empty GAP-LEDGER template.
 *
 * Classification, per signal and per day: **captured** (the platform shows what the truth says) · **captured wrong** (it shows
 * something else: the gap is a number) · **not captured** (a read exists and the platform has nothing) · **no surface** (nothing an
 * outside integrator can read). The first edition goes to the core as soon as it exists (B/W § 11).
 */
object StoreDayGapCheck {
    class Signal(val key: String, val truthFile: String, val truth: String, val surface: String, val note: String)

    /** The surface column names what exists TODAY from outside; "no surface" is a finding, not a failure of the check. */
    val SIGNALS: List<Signal> = listOf(
        Signal(
            "sales_per_fixture",
            "truth/fixture_product_day.csv",
            "units and value sold per fixture and EAN per day",
            "Hasura read of sale_line.zone_id (the zone the item stood in at ingest, A1) grouped by fixture; Connect: none",
            "the zone is stamped by the server from thing_location at ingest, so it is only right if the item's location was right",
        ),
        Signal(
            "sales_per_item_on_fixture",
            "truth/fixture_product_day.csv",
            "units per (fixture, EAN)",
            "same read, grouped by (zone, product); the commerce_projection read-back (TWIN-REQ-003, satisfied)",
            "per-product rollup exists; per-fixture-per-product needs the sale_line read",
        ),
        Signal(
            "on_hand_vs_plan",
            "truth/onhand.csv",
            "plan, open and close quantity per fixture and EAN",
            "Connect /compliance/state (compliance_target rows for the period directive) and thing_location counts per zone",
            "needs the period directive's stop date proven first (A3)",
        ),
        Signal(
            "impressions",
            "truth/fixture_hour.csv",
            "impressions per fixture-hour",
            "Connect impression read (TWIN-REQ-004, satisfied) /visionai impressions query",
            "depends on the people source carrying gaze; see the LiveDrive sidecar note",
        ),
        Signal(
            "dwell",
            "truth/fixture_hour.csv",
            "dwell seconds per fixture-hour",
            "impression duration from the same read",
            "dwell is the impression's duration, not a separate series",
        ),
        Signal(
            "interactions",
            "truth/fixture_hour.csv",
            "pickups, put-backs, try-ons per fixture-hour",
            "no surface",
            "A8: no producer and no input event for an interaction with an item at a fixture",
        ),
        Signal(
            "stock_outs_lost_sales",
            "truth/fixture_hour.csv",
            "stock-out events and lost sales per fixture-hour",
            "no surface",
            "the platform has no lost-sale concept; only the on-hand series shows a stock-out",
        ),
        Signal(
            "flows",
            "truth/flows.csv",
            "zone-to-zone shoppers per hour",
            "Connect person-session read (vision_ai:view) for sessions; zone transitions have no read",
            "partially: sessions yes, transitions no",
        ),
        Signal(
            "traffic_visits",
            "truth/summary.json",
            "visitors per day",
            "commerce_projection / traffic rollup read-back (visit_count)",
            "Denver's entry zone was retyped entry_exit -> region on 2026-11-11 (A12): compare before and after",
        ),
        Signal(
            "deliveries_moves",
            "wire-plan.jsonl",
            "receipts and moves sent",
            "Connect /inventory/items/details for a sample of EPCs; task and custody reads",
            "the twin's own oracle: every planned event = one on the wire = one row",
        ),
        Signal(
            "counts",
            "truth/summary.json (counts)",
            "weekly count fixtures and reads",
            "Connect tasks query (task:read) and the count's stocktake_result via outbound",
            "count steps are user-JWT calls, not Connect",
        ),
        Signal(
            "queue_engagement_staff",
            "(none)",
            "queue length, engagement, staff movement",
            "no surface",
            "A8: the rollup never writes them live; every row is the January to October 4 backfill",
        ),
    )

    fun checklist(slug: String): String = buildString {
        appendLine("# storeDayGapCheck planned check list for $slug (read-only design; no network, nothing compared in this slice)")
        appendLine()
        appendLine("| signal | truth source | platform read it would use | note |")
        appendLine("|---|---|---|---|")
        SIGNALS.forEach { appendLine("| ${it.key} | ${it.truthFile} | ${it.surface} | ${it.note} |") }
        appendLine()
        appendLine("Signals with no surface today: ${SIGNALS.filter { it.surface.startsWith("no surface") }.joinToString { it.key }}")
    }

    val TEMPLATE: String = buildString {
        appendLine("# Gap ledger (plan § 5.1, Phase 3 brief § 3)")
        appendLine()
        appendLine("One edition per measured live day. Classification: **captured** · **captured wrong** · **not captured** · **no surface**.")
        appendLine("The first edition goes to the core as soon as it exists (B/W § 11). Empty until the first full live day has been measured.")
        appendLine()
        appendLine("## Edition: (date) (store)")
        appendLine()
        appendLine("Truth ledger: `out/day/<slug>/<date>/truth/`  ·  Platform read at: (time)  ·  Reads used: (list)")
        appendLine()
        appendLine("| signal | truth value | platform value | classification | gap (abs, %) | read surface used | owner of the fix | filed as |")
        appendLine("|---|---|---|---|---|---|---|---|")
        SIGNALS.forEach { appendLine("| ${it.key} |  |  |  |  | ${it.surface.substringBefore(';').substringBefore(',')} |  |  |") }
    }

    fun write(out: Path, slug: String) {
        Files.createDirectories(out)
        Files.writeString(out.resolve("CHECKLIST.md"), checklist(slug))
        Files.writeString(out.resolve("GAP-LEDGER.template.md"), TEMPLATE)
    }
}

fun main() {
    val slug = System.getenv("M8TRX_GAP_SITE")?.takeIf { it.isNotBlank() } ?: "dec-us-denver"
    val out = Path.of(System.getenv("M8TRX_GAP_OUT")?.takeIf { it.isNotBlank() } ?: "out/gap-check").resolve(slug)
    StoreDayGapCheck.write(out, slug)
    println(StoreDayGapCheck.checklist(slug))
    println("wrote $out/CHECKLIST.md and GAP-LEDGER.template.md (the committed skeleton is status/GAP-LEDGER.md)")
}
