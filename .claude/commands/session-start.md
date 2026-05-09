---
description: Start a session in m8trx-twin (digital-twin sister project; segregated from core)
---

Read these files to get oriented:

1. **`/Users/bob/IdeaProjects/m8trx-twin/CLAUDE.md`** — project context, system-integrator posture, layered generator architecture (5 layers), tenant model, off-limits, requirement-flow protocol.

2. **`/Users/bob/IdeaProjects/m8trx-twin/status/STATUS.md`** — the **`## ⚠ NEXT SESSION PRIORITIES`** section pinned at the top is the AUTHORITATIVE source for "Today's options". Do not invent options outside of it.

3. **`/Users/bob/IdeaProjects/m8trx-twin/status/SESSION-LOG.md`** — the most recent Session NN entry is the AUTHORITATIVE source for "Where we left off". (May not exist during bootstrap; if absent, state "Bootstrap — no prior sessions yet.")

Optional, only if relevant to today's task:
- `/Users/bob/IdeaProjects/m8trx-twin/reference/architecture/LAYER4-CONFIG-SCHEMA.md` — scenario config schema strawman, the architectural commitment of this project
- `/Users/bob/IdeaProjects/m8trx-shared/twin/SISTER-PROJECT.md` — twin↔core relationship, brief-filing protocol (only load when actually filing a brief or absorbing one)

**Do NOT read m8trx-shared core files** (`m8trx-shared/CLAUDE.md`, `m8trx-shared/status/STATUS.md`, `m8trx-shared/status/SESSION-LOG.md`, `m8trx-shared/status/CLEANUP-TASKS.md`, anything under `m8trx-shared/status/sprint/`, etc.). Twin sessions stay segregated from core context. The only m8trx-shared path twin sessions ever touch is `m8trx-shared/twin/`.

---

Summarize in this exact format:

## Session Start — [today's date]

**Where we left off:**
[2-3 sentences pulled from the latest SESSION-LOG.md entry. If SESSION-LOG.md doesn't exist yet, write: "Bootstrap — no prior sessions. See STATUS.md NEXT SESSION PRIORITIES for entry order."]

**Active blockers:**
[bullet list — only items currently blocking twin progress, drawn from STATUS.md. If none, write "None — bootstrap state."]

**Active requirements filed back to core:**
[bullet list of items from STATUS.md "Active Requirements Filed Back to Core" section, with status. Only show those NOT in `ABSORBED` state.]

**Today's options:**
[3-5 concrete next actions ranked by priority, drawn from STATUS.md `## ⚠ NEXT SESSION PRIORITIES`. Surface the recommended entry order if STATUS.md has one explicitly noted.]

Keep it tight — no more than 25 lines total. Bob picks a task and starts immediately.

**If sources disagree, STATUS.md wins.** It's curated at session-end; everything else is reference.
