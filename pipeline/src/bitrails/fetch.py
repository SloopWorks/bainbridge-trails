"""Fetch raw source data into a local cache (data/raw/<source_id>/)."""
from __future__ import annotations

import hashlib
import json
import math
import time
from dataclasses import dataclass, fields
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qsl

import requests
import yaml

USER_AGENT = "bitrails-pipeline/0.1 (Bainbridge trail app data pipeline; patjackson52@gmail.com)"
TIMEOUT = 60
PAGE_SIZE = 1000
FETCHABLE_KINDS = {"overpass", "arcgis_rest", "geojson"}
DEM_SOURCE_ID = "usgs_3dep_dem_island"  # the one elevation entry `fetch` downloads (tiled exportImage)
DEM_TILE_PX = 1500
DEM_NODATA = -9999.0
_EXT = {"json": "json", "geojson": "geojson", "shapefile_zip": "zip", "pdf": "pdf", "html": "html", "tiff": "tiff"}


class FetchError(RuntimeError):
    pass


@dataclass(frozen=True)
class Source:
    id: str
    name: str
    kind: str
    endpoint: str | None
    redistribution: str = "unknown"
    format: str | None = None
    query: str | None = None
    geometry_rank: int | None = None  # lower = preferred geometry source


@dataclass(frozen=True)
class RawResult:
    path: Path
    fetched_at: str
    from_cache: bool
    stale_reason: str | None = None


def load_sources(path: Path) -> list[Source]:
    names = {f.name for f in fields(Source)}
    data = yaml.safe_load(Path(path).read_text())
    return [Source(**{k: v for k, v in s.items() if k in names}) for s in data["sources"]]


def _ext(src: Source) -> str:
    if src.kind == "arcgis_rest":
        return "geojson"
    if src.kind == "overpass":
        return "json"
    return _EXT.get(src.format or "", "bin")


def _validate(src: Source, body: bytes) -> None:
    """Raise FetchError if body is not a plausible, non-empty payload for this source."""
    if src.id == DEM_SOURCE_ID:
        return  # validated per tile in _download_dem
    fmt = "zip" if src.format == "shapefile_zip" else _ext(src)
    if fmt == "zip":
        if not body.startswith(b"PK"):
            raise FetchError(f"{src.id}: response is not a zip file")
        return
    if fmt not in ("json", "geojson"):
        return
    try:
        data = json.loads(body)
    except ValueError as e:
        raise FetchError(f"{src.id}: response is not valid JSON ({e})") from e
    if not isinstance(data, dict):
        raise FetchError(f"{src.id}: unexpected JSON shape")
    if src.kind == "overpass":
        remark = str(data.get("remark", ""))
        if any(w in remark.lower() for w in ("error", "timed out")):
            raise FetchError(f"{src.id}: Overpass remark: {remark}")
        if not data.get("elements"):
            raise FetchError(f"{src.id}: Overpass returned no elements")
    elif fmt == "geojson":
        if "error" in data:
            raise FetchError(f"{src.id}: service error {data['error']}")
        if not data.get("features"):
            raise FetchError(f"{src.id}: response has no features")


def _get(url: str, **kw) -> requests.Response:
    r = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=TIMEOUT, **kw)
    r.raise_for_status()
    return r


def _download_overpass(src: Source) -> bytes:
    r = requests.post(src.endpoint, data={"data": src.query}, headers={"User-Agent": USER_AGENT}, timeout=TIMEOUT)
    r.raise_for_status()
    return r.content


def _download_arcgis(src: Source) -> bytes:
    url = src.endpoint.rstrip("/")
    if not url.endswith("/query"):
        url += "/query"
    params = dict(parse_qsl(src.query or "where=1=1&outFields=*&outSR=4326&f=geojson"))
    params.pop("resultOffset", None)
    params.pop("resultRecordCount", None)
    page_size = PAGE_SIZE
    try:
        meta = _get(url.removesuffix("/query"), params={"f": "json"}).json()
        page_size = min(int(meta.get("maxRecordCount") or PAGE_SIZE), PAGE_SIZE)
    except (requests.RequestException, ValueError, TypeError):
        pass
    features: list = []
    offset = 0
    while True:
        page = _get(url, params={**params, "resultOffset": offset, "resultRecordCount": page_size}).json()
        if "error" in page:
            raise FetchError(f"{src.id}: ArcGIS error {page['error']}")
        got = page.get("features", [])
        features += got
        more = page.get("exceededTransferLimit")
        if more is None:
            more = len(got) >= page_size
        if not got or not more:
            break
        offset += len(got)
    return json.dumps({"type": "FeatureCollection", "features": features}).encode()


def is_fetchable(src: Source) -> bool:
    return src.kind in FETCHABLE_KINDS or src.id == DEM_SOURCE_ID


def _download_dem(src: Source) -> bytes:
    """Tiled ImageServer exportImage (service gateway times out on big requests) -> one deflated float32
    GeoTIFF in EPSG:32610. src.query = `bbox=minx,miny,maxx,maxy&res=<m>` in UTM metres."""
    import numpy as np
    import rasterio
    from rasterio.io import MemoryFile
    from rasterio.transform import from_origin

    q = dict(parse_qsl(src.query or ""))
    minx, miny, maxx, maxy = (float(v) for v in q["bbox"].split(","))
    res = float(q["res"])
    width, height = math.ceil((maxx - minx) / res), math.ceil((maxy - miny) / res)
    maxy = miny + height * res
    out = np.full((height, width), DEM_NODATA, dtype="float32")
    for r0 in range(0, height, DEM_TILE_PX):
        for c0 in range(0, width, DEM_TILE_PX):
            w, h = min(DEM_TILE_PX, width - c0), min(DEM_TILE_PX, height - r0)
            tx0, ty1 = minx + c0 * res, maxy - r0 * res
            params = {"bbox": f"{tx0},{ty1 - h * res},{tx0 + w * res},{ty1}", "bboxSR": 32610, "imageSR": 32610,
                      "size": f"{w},{h}", "format": "tiff", "pixelType": "F32", "noData": int(DEM_NODATA),
                      "f": "image"}
            body = b""
            for attempt in range(4):  # first call to a cold service often 502/504s
                try:
                    body = _get(src.endpoint, params=params).content
                    if body[:4] in (b"II*\x00", b"MM\x00*"):
                        break
                    raise FetchError(f"{src.id}: tile r{r0} c{c0} is not a TIFF")
                except (requests.RequestException, FetchError):
                    if attempt == 3:
                        raise
                    time.sleep(3 * (attempt + 1))
            with MemoryFile(body) as mf, mf.open() as ds:
                if ds.shape != (h, w):
                    raise FetchError(f"{src.id}: tile r{r0} c{c0} has shape {ds.shape}, wanted {(h, w)}")
                out[r0:r0 + h, c0:c0 + w] = ds.read(1)
    if not np.any(out != DEM_NODATA):
        raise FetchError(f"{src.id}: DEM is all nodata")
    with MemoryFile() as mf:
        with mf.open(driver="GTiff", height=height, width=width, count=1, dtype="float32", crs="EPSG:32610",
                     transform=from_origin(minx, maxy, res, res), nodata=DEM_NODATA, compress="deflate",
                     predictor=3, tiled=True) as dst:
            dst.write(out, 1)
        return mf.read()


def _download(src: Source) -> bytes:
    if src.id == DEM_SOURCE_ID:
        return _download_dem(src)
    if src.kind == "overpass":
        return _download_overpass(src)
    if src.kind == "arcgis_rest":
        return _download_arcgis(src)
    url = src.endpoint
    return _get(url, params=dict(parse_qsl(src.query)) if src.query else None).content


def _retryable(e: Exception) -> bool:
    if isinstance(e, requests.HTTPError) and e.response is not None:
        return e.response.status_code == 429 or e.response.status_code >= 500
    return True


def _atomic_write(path: Path, data: bytes) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_bytes(data)
    tmp.replace(path)


def _from_cache(src: Source, d: Path, why: str, stale: bool = False) -> RawResult:
    mf = d / "manifest.json"
    if mf.exists():
        man = json.loads(mf.read_text())
        path = d / man["file"]
        if path.exists():
            return RawResult(path, man["fetched_at"], True, why if stale else None)
    raise FetchError(f"{src.id}: {why}; no cached copy in {d}")


def fetch_source(src: Source, cache_dir: Path, offline: bool = False, retries: int = 2) -> RawResult:
    d = Path(cache_dir) / src.id
    if offline:
        return _from_cache(src, d, "offline")
    if not src.endpoint:
        raise FetchError(f"{src.id}: no endpoint")
    err: Exception | None = None
    body = None
    for attempt in range(retries):
        try:
            body = _download(src)
            _validate(src, body)
            break
        except (requests.RequestException, ValueError, FetchError) as e:
            body, err = None, e
            if not _retryable(e):
                break
            if attempt + 1 < retries:
                time.sleep(2 * (attempt + 1))
    if body is None:
        return _from_cache(src, d, str(err), stale=True)
    d.mkdir(parents=True, exist_ok=True)
    name = f"latest.{_ext(src)}"
    sha = hashlib.sha256(body).hexdigest()
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    mf = d / "manifest.json"
    history = json.loads(mf.read_text()).get("history", []) if mf.exists() else []
    history = (history + [{"fetched_at": now, "sha256": sha}])[-50:]
    _atomic_write(d / name, body)
    _atomic_write(mf, json.dumps({"id": src.id, "file": name, "fetched_at": now, "sha256": sha,
                                  "bytes": len(body), "history": history}, indent=2).encode())
    return RawResult(d / name, now, False)
