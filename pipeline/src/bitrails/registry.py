"""Stable segment ID registry (data/registry/segments.json).

Entries for non-redistributable geometry (``private: true``) carry no coordinates in
segments.json; their WKT lives in the gitignored sidecar ``private/geometry.json`` next
to it. With the sidecar they match like any entry; without it (fresh clone) they match
by exact fingerprint only, and the sidecar is rewritten on save. Private entries store
``fingerprint_sha256`` (sha256 of the fingerprint string) instead of the fingerprint
itself, because endpoints + length of a short segment are effectively its geometry.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import shapely
from shapely.geometry import LineString
from shapely.strtree import STRtree

from .geo import to_utm

DEFAULT_REGISTRY = Path(__file__).resolve().parents[3] / "data" / "registry" / "segments.json"

COORD_ROUND_DEG = 1e-5     # endpoint rounding in fingerprint
LEN_ROUND_M = 5            # length rounding in fingerprint
HAUSDORFF_M = 8.0
LEN_TOL = 0.05
OVERLAP_BUFFER_M = 8.0
OVERLAP_FRAC = 0.5


def fingerprint(geom: LineString) -> str:
    """Direction-independent: sorted endpoints at 1e-5 deg + length rounded to 5 m."""
    c = list(geom.coords)
    ends = sorted(f"{round(x, 5) + 0.0:.5f},{round(y, 5) + 0.0:.5f}" for x, y in (c[0][:2], c[-1][:2]))
    length = round(to_utm(geom).length / LEN_ROUND_M) * LEN_ROUND_M
    return f"{ends[0]}|{ends[1]}|{length}"


def fp_hash(fp: str) -> str:
    return hashlib.sha256(fp.encode()).hexdigest()


def _entry_fp_hash(e: dict) -> str:
    # private entries written before hashing still carry a plain fingerprint (migrated on save)
    return e["fingerprint_sha256"] if "fingerprint_sha256" in e else fp_hash(e["fingerprint"])


def _canon_key(geom: LineString) -> tuple:
    c = [tuple(p[:2]) for p in geom.coords]
    return (fingerprint(geom), min(c, c[::-1]))


class Registry:
    def __init__(self, path: Path, entries: dict[str, dict], next_id: int,
                 private_geom: dict[str, str] | None = None):
        self.path = Path(path)
        self.entries = entries
        self.private_geom = dict(private_geom or {})  # id -> WKT for private entries
        self.next_id = next_id
        self._utm: dict[str, LineString] = {}
        self._seen: set[str] = set()
        self._new: set[str] = set()
        self._retired_now: set[str] = set()
        self._finalized = False

    @classmethod
    def load(cls, path: Path | None = None) -> "Registry":
        path = Path(path or DEFAULT_REGISTRY)
        entries: dict[str, dict] = {}
        next_id = 1
        if path.exists():
            data = json.loads(path.read_text())
            entries = {e["id"]: e for e in data["segments"]}
            next_id = data["next_id"]
        side = cls.sidecar_path(path)
        private_geom = json.loads(side.read_text()) if side.exists() else {}
        issued = max((int(i.split("-")[1]) for i in entries), default=0)
        return cls(path, entries, max(next_id, issued + 1), private_geom)

    @staticmethod
    def sidecar_path(path: Path) -> Path:
        return Path(path).parent / "private" / "geometry.json"

    def render(self) -> dict[Path, str]:
        """{path: file text} for registry.json (+ the private sidecar when there is one), nothing written."""
        data = {"next_id": self.next_id, "segments": [self.entries[i] for i in sorted(self.entries)]}
        files = {self.path: json.dumps(data, sort_keys=True, indent=2) + "\n"}
        side = self.sidecar_path(self.path)
        geoms = {i: self.private_geom[i] for i in sorted(self.private_geom) if self.entries.get(i, {}).get("private")}
        if geoms or side.exists():
            files[side] = json.dumps(geoms, sort_keys=True, indent=2) + "\n"
        return files

    def save(self) -> None:
        for path, text in self.render().items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

    # -- internals
    def _wkt(self, id_: str) -> str | None:
        e = self.entries[id_]
        return self.private_geom.get(id_) if e.get("private") else e.get("geometry")

    def _has_geom(self, id_: str) -> bool:
        return bool(self._wkt(id_))

    def _geom_utm(self, id_: str) -> LineString:
        if id_ not in self._utm:
            self._utm[id_] = to_utm(shapely.from_wkt(self._wkt(id_)))
        return self._utm[id_]

    def _store(self, id_: str, geom: LineString, private: bool = False) -> None:
        e = self.entries[id_]
        fp = fingerprint(geom)
        wkt = shapely.to_wkt(geom, rounding_precision=6)
        if private:
            e["private"] = True
            e["fingerprint_sha256"] = fp_hash(fp)
            e.pop("fingerprint", None)
            e.pop("geometry", None)
            self.private_geom[id_] = wkt
        else:
            e.pop("private", None)
            e.pop("fingerprint_sha256", None)
            e["fingerprint"] = fp
            e["geometry"] = wkt
            self.private_geom.pop(id_, None)
        self._utm.pop(id_, None)

    # -- public
    def assign_many(self, geoms: list[LineString], private: list[bool] | None = None) -> list[str]:
        """IDs for geoms: exact fingerprint, then fuzzy match, then new. Order-independent.

        private[k] marks geoms[k] as non-redistributable (geometry goes to the sidecar)."""
        private = list(private) if private is not None else [False] * len(geoms)
        if self._finalized:
            raise RuntimeError("Registry already finalized this session; reload before assigning again")
        order = sorted(range(len(geoms)), key=lambda i: _canon_key(geoms[i]))
        result: dict[int, str] = {}
        claimed = set(self._seen)
        active = sorted(i for i, e in self.entries.items() if not e["retired"] and i not in claimed)

        by_fp: dict[str, list[str]] = {}
        for i in active:
            by_fp.setdefault(_entry_fp_hash(self.entries[i]), []).append(i)
        for k in order:
            for cand in by_fp.get(fp_hash(fingerprint(geoms[k])), []):
                if cand not in claimed:
                    claimed.add(cand)
                    result[k] = cand
                    break

        todo = [k for k in order if k not in result]
        fuzzy = [i for i in active if self._has_geom(i)]  # private entries w/o sidecar: fingerprint only
        if todo and fuzzy:
            active = fuzzy
            tree = STRtree([self._geom_utm(i) for i in active])
            for k in todo:
                g = to_utm(geoms[k])
                best = None
                for j in tree.query(g.buffer(HAUSDORFF_M)):
                    cand = active[j]
                    if cand in claimed:
                        continue
                    old = self._geom_utm(cand)
                    if abs(old.length - g.length) > LEN_TOL * max(old.length, g.length):
                        continue
                    h = shapely.hausdorff_distance(old, g, densify=0.1)
                    if h < HAUSDORFF_M and (best is None or (h, cand) < best):
                        best = (h, cand)
                if best:
                    claimed.add(best[1])
                    result[k] = best[1]

        for k in order:
            if k not in result:
                id_ = f"seg-{self.next_id:06d}"
                self.next_id += 1
                self.entries[id_] = {"id": id_, "fingerprint": "", "geometry": "", "supersedes": [], "retired": False}
                self._new.add(id_)
                result[k] = id_
            self._store(result[k], geoms[k], private[k])
            self._seen.add(result[k])
        return [result[i] for i in range(len(geoms))]

    def assign(self, geom: LineString) -> str:
        return self.assign_many([geom])[0]

    def retire(self, id_: str) -> None:
        self.entries[id_]["retired"] = True
        self._retired_now.add(id_)

    def finalize_run(self) -> None:
        """Retire active entries not seen this run; record supersedes for new IDs.

        Exactly once per load/session, after the full canonical set was assigned.
        """
        if self._finalized:
            raise RuntimeError("Registry.finalize_run() already called this session")
        self._finalized = True
        for id_ in sorted(self.entries):
            if not self.entries[id_]["retired"] and id_ not in self._seen:
                self.retire(id_)
        for new in sorted(self._new):
            buf = self._geom_utm(new).buffer(OVERLAP_BUFFER_M)
            sup = set(self.entries[new]["supersedes"])
            for old in sorted(i for i in self._retired_now if self._has_geom(i)):
                o = self._geom_utm(old)
                if o.length > 0 and o.intersection(buf).length / o.length > OVERLAP_FRAC:
                    sup.add(old)
            self.entries[new]["supersedes"] = sorted(sup)
        self._seen, self._new, self._retired_now = set(), set(), set()
