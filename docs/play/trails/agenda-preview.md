# Play agenda preview — pack 2026.11.2 (Trails edition v1.1)

Generated with `node scripts/build-play-content.mjs --agenda 2026-10-29` and `--agenda 2026-11-12` (two 14-day
windows = the next 4 weeks from the pack epoch). Island time; daylight = civil dawn–dusk. For the PR body.
Roads-only rows (`kraken-roads`, `treasure-weekly-roads`) are unchanged from 2026.11.1 and listed for completeness.
The ferry sprite (ambient, every day 05:00–23:00 on each Bainbridge departure) is elided from the cells.

The new trails items are all `every: "always"` (permanent), so they appear in the "Always on" list, never in the dated
rows. Nothing dated was added: no new windows, no new hero, no change to the kraken rotation or `ch-sweep`.

## Week 1–2: 2026-10-29 → 2026-11-11

| date | daylight | starts |
|---|---|---|
| 2026-10-29 thu | 07:17–18:28 | kraken-roads 06:00 → 11-01 20:00 → coast-k5 · kraken-trails 06:00 → 11-01 20:00 → coast-t4 · ch-krakenweek 06:00 → 11-01 20:00 · lore-kraken 06:00 → 11-01 20:00 |
| 2026-10-30 fri | 07:19–18:27 | — |
| 2026-10-31 sat | 07:20–18:25 | ch-sweep 00:00 → 11-01 24:00 |
| 2026-11-01 sun | 06:22–17:24 | — |
| 2026-11-02 mon | 06:23–17:22 | treasure-weekly-trails 05:00 → 11-08 24:00 · treasure-weekly-roads 05:00 → 11-08 24:00 |
| 2026-11-03 tue | 06:25–17:21 | — |
| 2026-11-04 wed | 06:26–17:20 | — |
| 2026-11-05 thu | 06:27–17:18 | kraken-roads 06:00 → 11-08 20:00 → coast-k2 · kraken-trails 06:00 → 11-08 20:00 → coast-t2 · ch-krakenweek 06:00 → 11-08 20:00 · lore-kraken 06:00 → 11-08 20:00 |
| 2026-11-06 fri | 06:29–17:17 | — |
| 2026-11-07 sat | 06:30–17:16 | ch-sweep 00:00 → 11-08 24:00 |
| 2026-11-08 sun | 06:32–17:14 | — |
| 2026-11-09 mon | 06:33–17:13 | treasure-weekly-trails 05:00 → 11-15 24:00 · treasure-weekly-roads 05:00 → 11-15 24:00 |
| 2026-11-10 tue | 06:34–17:12 | — |
| 2026-11-11 wed | 06:36–17:11 | — |

## Week 3–4: 2026-11-12 → 2026-11-25

| date | daylight | starts |
|---|---|---|
| 2026-11-12 thu | 06:37–17:10 | kraken-roads 06:00 → 11-15 20:00 → coast-k6 · kraken-trails 06:00 → 11-15 20:00 → coast-t3 · ch-krakenweek 06:00 → 11-15 20:00 · lore-kraken 06:00 → 11-15 20:00 |
| 2026-11-13 fri | 06:39–17:09 | — |
| 2026-11-14 sat | 06:40–17:07 | ch-sweep 00:00 → 11-15 24:00 |
| 2026-11-15 sun | 06:41–17:06 | — |
| 2026-11-16 mon | 06:43–17:05 | treasure-weekly-trails 05:00 → 11-22 24:00 · treasure-weekly-roads 05:00 → 11-22 24:00 |
| 2026-11-17 tue | 06:44–17:04 | — |
| 2026-11-18 wed | 06:46–17:04 | — |
| 2026-11-19 thu | 06:47–17:03 | kraken-roads 06:00 → 11-22 20:00 → coast-k4 · kraken-trails 06:00 → 11-22 20:00 → coast-t4 · ch-krakenweek 06:00 → 11-22 20:00 · lore-kraken 06:00 → 11-22 20:00 |
| 2026-11-20 fri | 06:48–17:02 | — |
| 2026-11-21 sat | 06:50–17:01 | ch-sweep 00:00 → 11-22 24:00 |
| 2026-11-22 sun | 06:51–17:00 | — |
| 2026-11-23 mon | 06:52–17:00 | treasure-weekly-trails 05:00 → 11-29 24:00 · treasure-weekly-roads 05:00 → 11-29 24:00 |
| 2026-11-24 tue | 06:53–16:59 | — |
| 2026-11-25 wed | 06:55–16:58 | — |

## Always on (33)

egg-ladybug, egg-madrone, egg-station-s, **egg-strawberry, egg-fivemasts, egg-toejam, egg-hilltop, egg-coastline,
egg-chillyhilly**, riddle-frog, riddle-bond, riddle-fairy, riddle-madrone, riddle-monroe, **riddle-roof, riddle-steep,
riddle-close, riddle-manzanita, riddle-gift, riddle-dock**, lore-frog, lore-ferry, lore-mill, lore-memorial, lore-yama,
lore-fortward, lore-eagleharbor, lore-bloedel, **lore-gazzam, lore-fortward-navy, lore-hallbrothers, lore-moritani,
lore-battlepoint** (bold = new in 2026.11.2).

## Disabled (8)

egg-fairydoor, egg-helix, egg-marquee, lore-helix, lore-lynwood, lore-strawberry, lore-fay (unchanged drafts),
**lore-sakai** (new; facts TBC, see `content.md`).

## Device-side placements (from `app/play/src/jvmTest/resources/golden/agenda-trails.tsv`, regenerated)

| item | placement (seeded point, chart square) |
|---|---|
| egg-strawberry | trail-hayano-trail/seg-005083 47.62557,-122.52806 [7:6] |
| egg-fivemasts | trail-fort-ward-to-blakely-harbor-trail/seg-004516 47.59321,-122.52411 [4:6] |
| egg-toejam | trail-nutes-trail/seg-000719 47.58398,-122.51018 [3:7] |
| egg-hilltop | trail-hilltop-trail/seg-006866 47.65541,-122.55316 [10:4] |
| egg-coastline | trail-hawley-cove-trail/seg-000327 47.62694,-122.50379 [7:7] |
| egg-chillyhilly | trail-sunny-hill-trail/seg-001142 47.58947,-122.51236 [3:7] |
| riddle-roof | trail-nutes-pond-trails/seg-000628 47.58328,-122.50956 [3:7] |
| riddle-steep | trail-peters-trail/seg-006869 47.60947,-122.55589 [5:4] |
| riddle-close | trail-close-trail/seg-007363 47.62502,-122.57198 [7:3] |
| riddle-manzanita | park:Manzanita Park 47.68621,-122.54799 [13:4] |
| riddle-gift | park:Ted Olson Nature Preserve 47.67100,-122.51800 [12:6] |
| riddle-dock | trail-hidden-cove-loop-trail/seg-005816 47.68815,-122.53515 [13:5] |
| lore-gazzam | trail-gazzam-lake-main-trail/seg-007186 47.61051,-122.56460 [6:3] |
| lore-fortward-navy | place:fort-ward-south [2:6] |
| lore-hallbrothers | place:blakely-park-trailhead [4:6] |
| lore-moritani | view only (armchair card, no placement) |
| lore-battlepoint | trail-battle-point/seg-007627 47.66098,-122.57964 [11:2] |

Density (`maxPlacementsPerCell: 3`): the regenerated trails golden has exactly the same `density-dropped` rows as
2026.11.1 (lore-ferry 284 days, lore-eagleharbor 48 days); no new item displaces a published one and none of the new
items is ever dropped. `agenda-roads.tsv` differs only in its header's pack version.
