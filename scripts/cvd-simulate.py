#!/usr/bin/env python3
"""Simulate color-vision deficiency on screenshots (Machado, Oliveira & Fernandes 2009, severity 1.0).

    python3 scripts/cvd-simulate.py deutan docs/spikes/map-render/topo-android-z15.png ...
    -> writes <name>-deutan.png next to each input (also: protan, tritan)

Matrices are applied in linear RGB (sRGB decoded), then re-encoded. Needs numpy + Pillow.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

MACHADO_1_0 = {
    "protan": [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
    "deutan": [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.011820, 0.042940, 0.968881]],
    "tritan": [[1.255528, -0.076749, -0.178779], [-0.078411, 0.930809, 0.147602], [0.004733, 0.691367, 0.303900]],
}


def to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * c ** (1 / 2.4) - 0.055)


def simulate(path: Path, kind: str) -> Path:
    im = Image.open(path).convert("RGB")
    rgb = to_linear(np.asarray(im, dtype=np.float64) / 255.0)
    out = to_srgb(rgb @ np.array(MACHADO_1_0[kind]).T)
    dst = path.with_name(f"{path.stem}-{kind}.png")
    Image.fromarray((out * 255 + 0.5).astype(np.uint8)).save(dst, optimize=True)
    return dst


if __name__ == "__main__":
    kind, *files = sys.argv[1:]
    for f in files:
        print(simulate(Path(f), kind))
