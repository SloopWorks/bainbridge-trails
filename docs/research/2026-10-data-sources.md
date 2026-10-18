# Bainbridge Island trail data sources and licenses (survey 2026-10-18)

Machine-readable form: `data/sources.yaml` (51 entries). All endpoints below were probed on 2026-10-18; status noted. "Redistribution" = may we ship derived geometry/metadata inside the app.

## Summary verdicts

| Source | What it gives | Redistribution |
|---|---|---|
| OpenStreetMap (Overpass) | Best open geometry: ~4,978 path-like ways on the island | **yes** (ODbL, attribution + share-alike) |
| BIMPRD AGOL `Trails_12062022` | Official BIMPRD trails, 268 segments, names, park, surface, uses, slope, ADA | **unknown** (see below) |
| City GIS `_rd_public_trails_all` | Merged BIMPRD+City trails, 373 segments, same schema, Z values | **unknown** |
| USGS 3DEP (EPQS, ImageServer, 1 m DEM, LAZ) | Elevation, contours, hillshade | **yes** (public domain) |
| WA DNR Lidar Portal | Same lidar, manual download | **yes** (free for any use if cited) |
| Kitsap County open data | Parcels, park points, shoreline; no trails | **yes** except no-sale; per-layer overrides (shoreline, wetlands: non-commercial) |
| BI Land Trust | Preserve pages, AGOL property points | **no** ("For internal use only.") |
| WA State Parks | Nothing: Fay Bainbridge and Fort Ward transferred to BIMPRD in 2011 | n/a |
| BIMPRD printable PDF maps (19) | Link-out maps per park | no (link only) |
| AllTrails, Trailforks, WTA | Cross-check only | no |

## Key findings

1. **Official geometry exists and is open to query.** The City runs ArcGIS Server 11.5 at `https://gis.bainbridgewa.gov/server/rest/services`. The `Landuse` folder has `_rd_public_trails_all` (FeatureServer/MapServer, layer 0, polyline, 373 features, Z-enabled, SR 6597 ftUS; supports JSON/geoJSON/PBF; count and attribute queries returned HTTP 200). Item description: "merged public trail data ... compiled from two authoritative sources: the City of Bainbridge Island and the Bainbridge Island Metropolitan Park & Recreation District (BIMPRD). The dataset includes hiking, biking, and multi-use trails open to the public." Length by MANAGING_O (ft): BIMPRD 91,605; COBI 878; PRIVATE 165; null/blank ~36,000 (~24.4 mi total, Shape__Length).
2. **Geometry download from the City server was flaky.** Attribute/count queries worked; full-geometry queries hung (>120 s), then returned HTTP 500 (Web Adaptor error) and HTTP 503 "Wait timeout for the request exceeded". Geometry from this host is therefore **unverified**. The pipeline should page (`resultOffset`/`resultRecordCount=100`), back off, and fall back to the hosted BIMPRD copy.
3. **BIMPRD hosts the same schema on ArcGIS Online** (org `bimprd.maps.arcgis.com`, services7 host `OZXcQ6GATAEhthSP`): `Trails_12062022` layer 0, 268 polylines, plus `Avg_Slope`. GeoJSON export verified: HTTP 200, 0.9 s, 501,807 bytes, 268 MultiLineString features, EPSG:4326. MANAGING_O: BIMPRD 246, COBI 4, PRIVATE 1, null 17. Surface (MATERIAL) is mostly null (Dirt 58, Gravel 57). PARK is null for 40 rows. This is the most reliable official source today.
4. **Trailheads / waypoints:** `Trail_Markers` (92 points, rescue locator waypoints, hosted by BIMPRD AGOL).
5. **OSM:** island relation **3148366** (place=island, wikidata Q803903, bbox S47.5741 N47.7213 W-122.5928 E-122.4794). Overpass area id **3603148366**. City limit admin relation is 237327 (includes water). Public Overpass returns 406 without a User-Agent and 502 when busy; mirror `maps.mail.ru` worked for tests.
6. **No state parks remain on the island.** Washington State Parks Commission approved transfer of Fay Bainbridge and Fort Ward to BIMPRD on 2010-11-19, completed spring 2011 (Bainbridge Review). Spec section 3 bullet is satisfied by BIMPRD data.
7. **Land Trust:** no trail geometry published. AGOL `Conserved_Properties` (28 rows, `Public_Access` field) is marked "For internal use only." Use only as private reference for public/private status. Also City copy `GIS_DBO_lu_BILT_ConservedJan2024` (114 polygons).
8. **Sound to Olympics:** `STO_Alignments_12132022` layer **2** (19 polylines, planned alignments, fields Preferred/AlignmentNumber). Planned, not built; classify as planned.
9. **Kitsap County** open data (78 layers via DCAT) has no trails layer. Useful: `Park_Locations` (131 points), parcels (140,073 polygons).
10. **Elevation:** USGS 1 m DEM tiles (x53y527, x53y528, x53y529 from WA_Olympic_Peninsula_1A_2017, lidar flown ~2017-11) via TNM Access API; EPQS point query verified (54.44 m at -122.5155,47.631); 3DEP ImageServer identify verified. City also offers 5 ft contours from 2015 lidar (138 MB shapefile ZIP).

## Per-source detail

### OpenStreetMap / Overpass
- Endpoint: `POST https://overpass-api.de/api/interpreter` (form field `data`, set User-Agent). Count query (area form) returned 4,978 ways for `highway=path|footway|track|bridleway|cycleway|steps`; bbox form returned 5,143.
- License: https://www.openstreetmap.org/copyright : "You are free to copy, distribute, transmit and adapt our data, as long as you credit OpenStreetMap and its contributors." Must "Make clear that the data is available under the Open Database License." Share-alike applies to derived databases; keep OSM-derived layer separable.
- Quality: includes sidewalks and driveways; classify by tags (`footway=sidewalk`, `access`, `foot`, `surface`, `name`, `operator`).

### City of Bainbridge Island GIS
- Directory: `https://gis.bainbridgewa.gov/server/rest/services?f=json` HTTP 200. Folders include Landuse, Roads, Hosted, shoreline.
- Trail-relevant services (Landuse): `_rd_public_trails_all`, `lu_bimprd_trails2019_update` (metadata queries timed out; unverified), `lu_BIMPRD_Parks` (polygon, fields NAME/TYPE/OWNERSHIP/AGENCY/ACREAGE; 2009-era), `lu_CityParks2`, `COBI_OpenSpace` (10), `lu_Easements` (polyline), `lu_COBI_Boundary` (layer 2), `GIS_DBO_lu_BILT_Props2021` (MapServer).
- Fields on trails layer: TRAILNAME, PARK, TRAIL_SYST, REGION, MATERIAL, CONDITION, MANAGING_O, HIKING_ALL, BICYCLES_A, HORSES_ALL, WATER_TRAI, MOTOR_TRAI, ER_ACCESSI, ADA_ACCESS, NATIONAL_T/REGIONAL_T/LOCAL_TRAI, Class, DATA_SOURC, MERGE_SRC, Trail_lengthFT (mostly null), Shape__Length.
- License text (iteminfo licenseInfo): "This data is for general planning and recreational reference. Not for legal boundary or navigation purposes. Users are responsible for verifying accuracy before use in decision-making." No reuse grant. Verdict: **unknown**. The city also publishes shapefile downloads (Data Downloads page, https://www.bainbridgewa.gov/1414/Data-Downloads) without license text.
- Washington public records law arguably makes these public, but a records right is not an app-redistribution license; request written confirmation (not on critical path).

### BIMPRD (biparks.org and AGOL)
- biparks.org blocks automated clients (HTTP 403); data read from the Internet Archive snapshot of https://www.biparks.org/parks-trails/printable-maps (2026-06-19).
- The feature service item `Trails_12062022` (998b79a1fc0f40a5b19fa5cfc4214a82) has EMPTY licenseInfo. The separate web app item `2c030bc5...` ("Trails by Slope") says: "All data is owned and managed by the Bainbridge Island Parks & Recreation District. Map is open to public for viewing only." Trail Markers app: "This map is a production of the Bainbridge Island Metro Parks & Recreation District." Verdict: **unknown, treat as not granted** until BIMPRD confirms in writing. The services appear editable by anonymous users, so the pipeline must snapshot, diff against the prior snapshot and review changes.
- Legacy: `BIMPRD_Trails2015` (153 polylines, TRAIL_NAME/OWNER/LINK) and `JohnNelsonTrail` (1) on the city's AGOL account; copyright "Bainbridge Island Metropolitan Parks and Recreation District".
- PDF maps (kind pdf_map): Fold-Out All Trail Map, Battle Point, Blakely Hills, Cross Island, Downtown Corridor, Eagledale to Fort Ward, Fay Bainbridge, Fort Ward, Gazzam Lake, Grand Forest, Hawley Cove, Hidden Cove, John Nelson, Manzanita, Moritani, Pritchard, Strawberry Hill Bike Park, Sakai, Ted Olson. URLs are `https://www.biparks.org/home/showpublisheddocument/<id>/<timestamp>`. Not fetchable from here (403); verify in browser. Trails Vision Plan appendix (planned trails): https://biparks.org/wp-content/uploads/2026/03/Appendix-A.-Trail-Vision-Plan-.1.pdf.

### Bainbridge Island Land Trust
- Preserve pages: https://www.bi-landtrust.org/protected-space/<slug>/ (Cloudflare 403 to scripts; readable in browser/archive). Property map embeds AGOL Minimalist app `f16b11cfba3745cf837d5658b36c4d5a`.
- Public per layer: Gazzam Lake, Peters Tree Farm, Ted Olson addition, Meigs Park, Hilltop, Agate Passage Shoreline, Grand Forest N/W/E, Wildlife Corridor, Pritchard, Blakely Harbor, Rockaway Beach, Hawley Cove, Jablonko, Cougar Creek. Private: Fletcher Bay lots, Fort Ward lot, Heart of the Forest, Miller-Kirkman, Springbrook Creek. Quitslund Preserve (page): "open to the public from dawn to dusk ... no parking".
- Land Trust-owned preserves with public trails (Agate Passage, Quitslund, Cougar Creek, Jablonko) have no official GIS trails: gap, fill from OSM and field walks.
- License: AGOL item "For internal use only." Verdict: **no**. "Thirty Walks on Bainbridge" PDF is copyrighted: reference only.

### Kitsap County GIS
- Hub: https://kitsap-od-kitcowa.hub.arcgis.com/ ; catalog `.../api/feed/dcat-us/1.1.json` (HTTP 200, 78 datasets); service host `https://services6.arcgis.com/qt3UCV9x5kB4CwRA/arcgis/rest/services/`.
- Terms of Use (https://www.arcgis.com/sharing/rest/content/items/194717e70d694549a9e9deb1a6dc3710/data): "you are hereby permitted by Kitsap County to copy, distribute, and otherwise use the information with one exception. No one is permitted to sell this information except in accordance with a written agreement with Kitsap County." Information is provided "AS IS". Per-layer overrides: `Kitsap County Shoreline` and `Kitsap County Wetlands` say "Non-commercial use only". `Park_Locations` (item 6ef25e67029a4d2fb4853a963c05b402) has empty licenseInfo, so no override. Verdict: **yes** (no-sale clause); shoreline/wetlands **no**. A concrete layer entry `kitsap_park_locations` (FeatureServer/0, 131 points) was added.

### USGS 3DEP
- "USGS-authored or produced data and information are considered to be in the U.S. Public Domain." (https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits). National Map FAQ: "Data from The National Map is free and in the public domain. There are no restrictions on downloaded data" ; requested credit "Data available from U.S. Geological Survey, National Geospatial Program."
- Verdict: **yes**.

### WA DNR Lidar Portal
- https://lidarportal.dnr.wa.gov/ (HTTP 200, interactive Leaflet UI). https://dnr.wa.gov/node/118: "All of the materials we produce (publications, digital content, posters, videos) are free for you to use in any way you choose, for both commercial and non-commercial purposes, including modifying or adapting anything, as long as you cite us." The hillshade service licenseInfo is a separate warranty disclaimer ("provided on an 'as is', 'as available', and 'with all faults' basis"). Verdict: **yes**, cite the dataset (project name/year). Pipeline still sources the same lidar from USGS 3DEP.

### AllTrails / Trailforks / WTA (reference only)
- AllTrails Terms (https://www.alltrails.com/terms) s3.5(a) bars using "software or automated agents or scripts ... (to strip, scrape, or mine data from) the Products"; s5.1(c): materials "may not be copied or distributed, or republished, or transmitted in any way, without our prior written consent."
- Trailforks terms page could not be fetched (403); only secondary search summary. Treat as no.

## Gaps and risks
- City server geometry download unverified (outage/overload); `lu_bimprd_trails2019_update`, `lu_BIMPRD_Parks`, `lu_Easements`, `lu_COBI_Boundary` counts unobtained.
- No explicit open license from BIMPRD or the City: ask in writing (non-blocking per owner); until then OSM, USGS, WA DNR and (most) Kitsap layers are cleared for redistribution, official data is used for reconciliation/classification.
- biparks.org and bi-landtrust.org block scripts; PDF URLs unverified.
- Road shoulders/sidewalk connectors, street ends: OSM + City Road Ends map; no GIS layer found.

- BIMPRD PDF map URLs remain unverified (403 to scripts): owner should open each in a browser once.
