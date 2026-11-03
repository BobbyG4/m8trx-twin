# Shopper behavior — what the twin's shoppers do, and why (TWIN-REQ-007)

One file per site (`dec-us-denver.json`) holds every number. This page is the prose a presenter reads. Code: `src/main/kotlin/com/m8trx/twin/behavior/`. Proof: `./gradlew behaviorSelfTest`.

## A shopper's visit

1. **Arrives** on the operating model's hourly curve, scaled by the day of the week and a slow 6-week wave, so Saturday is not Tuesday and no two weeks are the same.
2. **Walks in through the door**, past the entrance table. Most walk on; some stop.
3. **Chooses where to stop from what is on the floor.** Each fixture has a chance set by its zone, its contents (an empty fixture is rarely visited; one holding a hero style pulls people), where it stands, and its color story.
4. **Looks and dwells** at each stop. The twin sends the shopper's positions five times a second; the platform's own impression rule turns that into the impression and dwell rows, exactly as it would for a camera.
5. **Picks things up** (engagement). Which items depends on facings and price; whether they pick one up depends on its shelf level and the face's color story.
6. **Tries on** footwear at the bench and apparel in the fitting room, sometimes.
7. **Buys or not**, item by item, from what is in hand. A broken size run or a complement beside a hero changes this.
8. **Pays at a counter** if they bought anything, sometimes adding an impulse item, and **leaves through the door**.

Every impression, pickup, try-on and sale line of a shopper comes from that one shopper, so "engagement rose, so sales rose" is something that really happened in the data.

## Personas

| Persona | Share | Behavior |
|---|---|---|
| browser | 73% | looks at a few fixtures, handles some things, rarely buys |
| mission | 12% | came for something in a category; stops at fixtures that carry it, usually buys |
| try_on | 12% | handles and tries before deciding |
| gift | 3% | knows what they want, grabs it in seconds and pays (the one sale with no impression behind it) |

## The six truths

Each truth multiplies exactly one measure. **Set it to 1.0 and the effect is gone; double the distance from 1.0 and the effect doubles.** `behaviorSelfTest` proves both on a seeded week.

| Truth | What to say | Number to edit | Default | Multiplies |
|---|---|---|---|---|
| **The entrance table wins** | Everyone passes it. | `entrance_table_impressions`, `entrance_table_pickup` | ×1.8, ×1.4 | impressions; pickups per impression |
| **The back corner loses** | Few shoppers walk there. | `back_corner_impressions` | ×0.5 | impressions |
| **Eye level lifts** | People reach what they see. | `eye_level_pickup`, `floor_level_pickup` | ×1.3, ×0.7 | pickups of a cell at that level |
| **A broken size run sinks** | The shopper cannot find their size. | `size_run_conversion` | ×0.5 when half the run is missing | conversion of that style there |
| **A color story lifts the whole face** | An outfit invites a second pickup. | `color_story_coherent`, `color_story_clash` | ×1.25, ×0.85 | impressions and pickups of everything on the face |
| **A complement beside a hero sells** | It completes the outfit. | `adjacency_complement` | ×1.2 | conversion of the complement |

"Make the entrance table win harder": raise `entrance_table_impressions`.

### How each one is decided

- **Entrance table**: fixtures in the entrance zone (`Z-01` in Denver: `PI-01`, `RR-02`, `PI-03`).
- **Back corner**: the farthest 20% of floor fixtures from the door, and more than 4 m sideways from the door's centre line (off the main path). In Denver that is 18 fixtures: the GPS cases, the far D-05/D-06 ends.
- **Shelf level**: the plan has no level field, so the twin derives one. A fixture's lines in `position_sequence` order fill levels 1 (top) to 4 (floor) by facings: each line takes the level its facing span's midpoint falls in. Level 2 is eye height and level 4 is the floor. A plan line carrying `display_level` keeps its own.
- **Size run**: a style's sizes on a fixture against every size the catalog carries for it. The conversion multiplier is `max(0.5, 1 − 0.5 × missing share / 0.5)`.
- **Color story**: catalog colors grouped into families (black, gray, white, blue, green, red, orange, yellow, pink, purple, brown). A face is coherent when one family holds at least 60% of all its facings (unnamed colors such as "Default Color" count in the total) with 3 or more cells of that family. It clashes when 5 or more families share it and none holds more than 35%.
- **Complement beside hero**: a cell next to a hero style's cell on the same fixture, in a category listed under the hero's category in `complements` (socks beside running shoes).

## The scripted reset (before / after)

`reset` names it. In the **before** plan the hero running shoes stand on a back-corner fixture (`GB-R1-U7`). In the **after** plan they move to the entrance table (`PI-01`) with running socks beside them. Nothing else moves. `GB-R6-U3` is the untouched control, and it stays flat. Shoppers read the plan that is on the floor when they walk in, so a reset at 15:00 changes behavior from the next arrival.

## What is not a truth

These numbers make the store look like a store. Placement does not change them: persona shares, stops per visit, dwell, base pickup and buy rates, try-on rates, and willingness to pay (`purchase.price_elasticity`). They are calibrated so a seeded week reconciles to the operating model. Visitors, transactions, units and revenue each land within the tolerances in `layer3/Reconciliation.kt`, for every day of the week.
