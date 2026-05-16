We are wrapping up this twin session. Do the following in order.

**Active doc surfaces (write to these):**
- `status/STATUS.md` — update `## ⚠ NEXT SESSION PRIORITIES`
- `status/SESSION-LOG.md` — add row to Session Index + update Rolling Summary
- `status/tracks/TRACK-TWIN.md` — update current state, open work, blocked items

**Brief/active/archive lifecycle:**
- `status/briefs/` — unfired task briefs. Move to `active/` when fired.
- `status/active/` — in-flight work. Move to `archive/sprint/` when complete.
- `status/archive/sprint/` — completed work. Move here at session close.

**Do NOT touch core repos** — m8trx-services / m8trx-web / m8trx-android / m8trx-api / m8trx-edge / m8trx-shared (except `m8trx-shared/twin/`).

---

## 1. Create session-notes file

Write `status/session-notes/YYYY-MM-DD-session-NN-short-slug.md` with:
- What was attempted (including failed approaches — the "don't repeat this" record)
- What shipped (commit refs)
- Key discoveries (gaps in core's public API, integration friction, etc.)
- Decisions made
- Branch/deploy state at close

## 2. Update SESSION-LOG.md

Add one row to the `## Session Index` table:
```
| NN | YYYY-MM-DD | One-line summary | [→](session-notes/filename.md) |
```

Update `## Rolling Summary` — add new session at top, keep last 3-5 sessions.

## 3. Update `status/tracks/TRACK-TWIN.md`

- Last session + link to new session-notes file
- Current branch/deploy state
- Open work reprioritized
- Active requirements filed to core (update status if any absorbed)

## 4. Update `status/STATUS.md`

Update `## ⚠ NEXT SESSION PRIORITIES` to reflect post-session reality. Update `**Last Updated**` line.

## 5. File any new core requirements

If this session discovered a gap in M8TRX's public API:
1. Write `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-NNN-<slug>.md` using the brief format in `m8trx-shared/twin/SISTER-PROJECT.md`
2. Add to TRACK-TWIN.md "Active Requirements Filed to Core" table with status `FILED`
3. Note in STATUS.md "Blocked on Core" section

## 6. Commit and push

```bash
cd /Users/bob/IdeaProjects/m8trx-twin
git add -A
git diff --cached --quiet || git commit -m "docs: session sync $(date +%Y-%m-%d-%H%M)"
git push

# Also commit m8trx-shared/twin/ if any briefs or insights were filed
cd /Users/bob/IdeaProjects/m8trx-shared
git add twin/
git diff --cached --quiet || git commit -m "docs(twin): session NN brief/insight $(date +%Y-%m-%d)"
git push
```

## 7. Confirm when done

One sentence: what changed, what's queued for next session.
