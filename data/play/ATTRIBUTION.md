# Play data attribution

## Tide predictions — NOAA CO-OPS (not for navigation)

`data/play/tides/9445882-<year>.json` are high/low tide **predictions** for Eagle Harbor, Bainbridge Island
(NOAA station 9445882, a subordinate station referenced to Seattle 9447130), heights in centimetres above MLLW,
times in UTC. Source: NOAA Center for Operational Oceanographic Products and Services (CO-OPS) data API,
<https://api.tidesandcurrents.noaa.gov/api/prod/datagetter> (`product=predictions&interval=hilo&datum=MLLW`),
fetched by `scripts/build-tides.mjs` (date in each file's `fetched` field).

- Information on NOAA government servers is public domain unless annotated otherwise. NOAA does not endorse this
  app, and these are not official NOAA products once reformatted.
- **Not for navigation.** Predictions may differ from observed water levels (weather, river flow) and NOAA updates
  them; the app shows tide times as "approx." and uses them for one game rule only (kraken stretches rest above
  2.44 m MLLW, docs/play/product.md T-2).
- In-app credit: "Tide predictions: NOAA CO-OPS (not for navigation)".
- Yearly chore (about October, after NOAA publishes the next tables): `node scripts/build-tides.mjs --to <year+3>`,
  commit, deploy; `node scripts/build-tides.mjs --check` validates offline.

## Ferry timetable — Washington State Ferries

`data/play/ferry-timetable.json` holds Seattle–Bainbridge departures for the ambient ferry sprite (visual only).
The committed values are the WSF **Fall 2026** Seattle/Bainbridge Island sailing schedule (Sep 20 – Dec 26, 2026),
copied 2026-10-21 from <https://wsdot.wa.gov/ferries/schedule/scheduledetailbyroute.aspx?route=sea-bi> and
cross-checked against the WSF Best Times to Travel PDF
(<https://wsdot.wa.gov/sites/default/files/2026-09/WSF-BTTT-SEA-BI-Fall2026.pdf>); `source` in the file names both.
The owner copies each new season (winter 2026–27 starts Dec 27) into the file and the pack's `ferry` block and bumps
`validUntil`. Times are approximate for gameplay; the app never tells anyone to catch a ferry.
