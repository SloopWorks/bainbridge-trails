"""Shared geometry helpers: WGS84 <-> UTM 10N projection."""
from __future__ import annotations

from pyproj import Transformer
from shapely.geometry.base import BaseGeometry
import numpy as np
import shapely

_TO_UTM = Transformer.from_crs(4326, 32610, always_xy=True)
_TO_WGS = Transformer.from_crs(32610, 4326, always_xy=True)


def _xy(t, c):
    x, y = t.transform(c[:, 0], c[:, 1])
    return np.column_stack([x, y])


def to_utm(g: BaseGeometry) -> BaseGeometry:
    return shapely.transform(g, lambda c: _xy(_TO_UTM, c))


def to_wgs(g: BaseGeometry) -> BaseGeometry:
    return shapely.transform(g, lambda c: _xy(_TO_WGS, c))
