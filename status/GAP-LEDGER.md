# Gap ledger (plan § 5.1, Phase 3 brief § 3)

One edition per measured live day. Classification: **captured** · **captured wrong** · **not captured** · **no surface**.
The first edition goes to the core as soon as it exists (B/W § 11). Empty until the first full live day has been measured.

## Edition: (date) (store)

Truth ledger: `out/day/<slug>/<date>/truth/`  ·  Platform read at: (time)  ·  Reads used: (list)

| signal | truth value | platform value | classification | gap (abs, %) | read surface used | owner of the fix | filed as |
|---|---|---|---|---|---|---|---|
| sales_per_fixture |  |  |  |  | Hasura read of sale_line.zone_id (the zone the item stood in at ingest |  |  |
| sales_per_item_on_fixture |  |  |  |  | same read |  |  |
| on_hand_vs_plan |  |  |  |  | Connect /compliance/state (compliance_target rows for the period directive) and thing_location counts per zone |  |  |
| impressions |  |  |  |  | Connect impression read (TWIN-REQ-004 |  |  |
| dwell |  |  |  |  | impression duration from the same read |  |  |
| interactions |  |  |  |  | no surface |  |  |
| stock_outs_lost_sales |  |  |  |  | no surface |  |  |
| flows |  |  |  |  | Connect person-session read (vision_ai:view) for sessions |  |  |
| traffic_visits |  |  |  |  | commerce_projection / traffic rollup read-back (visit_count) |  |  |
| deliveries_moves |  |  |  |  | Connect /inventory/items/details for a sample of EPCs |  |  |
| counts |  |  |  |  | Connect tasks query (task:read) and the count's stocktake_result via outbound |  |  |
| queue_engagement_staff |  |  |  |  | no surface |  |  |
