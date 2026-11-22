# Twin cleanup list

Code and state the Twin keeps but does not run, with what would bite whoever starts it. Newest first.

## 2026-11-22 — three entry points exclude only the sold log

Since PR #63 a theft-alarm unit is on `stock/stolen-epcs.txt` and `stock/missing-epcs.txt`, no longer in `sold-epcs.txt`. The live sale
path (`DatedStream`, run by the hourly pass via `day.sh --steps stream`) excludes sold + missing, so a stolen unit is never sold.
These three older `main()`s read **only** the sold log and would sell a stolen unit:

- `connect/SaleStream.kt:59` (`readPriorSold`, plus `M8TRX_STREAM_EXCLUDE`)
- `connect/ChainActivityStream.kt:123` (`readPriorSold`)
- `behavior/BehaviorDrive.kt:280` (`EpcPool.load`, sold log only)

Nothing on the box runs them (not the hourly pass, `twin-live`, the digest or a timer). Before anyone starts one: add the missing + stolen
exclusion, or delete the entry point. Not fixed now by Bob's call (kept PR #63 narrow).
