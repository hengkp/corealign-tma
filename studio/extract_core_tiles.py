#!/usr/bin/env python3
"""Cut one grayscale tile per core per channel out of a CoreAlign slide.

This is the prototype of the data contract the Arrange screen reads. The page tints and
composites these in the browser, which is the only way channel and colour can be changed
without going back to the 30 GB slide for every click.

The tile is cut wider than the core by ROTATION_SUPPORT so the page can rotate a core to
its approved angle without the corners coming off, which is the same reason
orientation.rotationSupportScale exists in the run config.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
from pathlib import Path

import numpy as np
import tifffile
from PIL import Image

TILE_PX = 256
ROTATION_SUPPORT = 1.45          # matches orientation.rotationSupportScale
# The floor is the background level, not the darkest pixel. Cores cover well under half a
# TMA slide, so the median of a plane IS its background: measured on this slide the median
# sits at 38% of a 0.5-to-99.8 percentile range for MMP1 and 11% for CPDs, which is exactly
# the grey haze that turned every tile into a washed rectangle instead of tissue on black.
LOW_PERCENTILE = 50.0
HIGH_PERCENTILE = 99.8

# A channel whose name says what it is gets a sensible colour without anybody picking one.
# Everything else falls through to grey, which is honest rather than decorative.
DEFAULT_COLOURS = {
    "nuclear": "#3D7BFF",
    "marker": "#FFFFFF",
    "autofluorescence": "#8A8A8A",
}
MARKER_COLOUR_CYCLE = ["#FF4D4D", "#3DDC84", "#FFD166", "#F78C6B", "#B980F0", "#00D0D0"]


def channel_kind(name: str) -> str:
    lowered = name.lower()
    if re.match(r"^(dapi|hoechst|nuclei)", lowered):
        return "nuclear"
    if re.match(r"^af\d*$", lowered) or "autofluor" in lowered:
        return "autofluorescence"
    return "marker"


def read_grid(csv_path: Path) -> list[dict]:
    cores = []
    with csv_path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            cores.append({
                "index": int(row["index"]),
                "row": int(row["row"]),
                "col": int(row["column"]),
                "core": row["core"],
                "centerX": float(row["center_x_px"]),
                "centerY": float(row["center_y_px"]),
                "diameter": float(row["diameter_px"]),
                "missing": row["missing"].strip().lower() == "true",
            })
    return cores


def ome_channel_names(handle: tifffile.TiffFile) -> list[str]:
    """Channel names out of the OME-XML, falling back to positional names."""
    count = handle.series[0].shape[0]
    xml = handle.ome_metadata or ""
    names = re.findall(r'<Channel[^>]*?\bName="([^"]*)"', xml)
    if len(names) == count:
        return names
    return [f"Channel {i + 1}" for i in range(count)]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slide", required=True, type=Path)
    parser.add_argument("--grid", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--level", type=int, default=3, help="pyramid level to cut from")
    parser.add_argument("--channels", default="", help="comma separated indices, blank means all")
    parser.add_argument("--orientation", type=Path, default=None,
                        help="qc/02-orientation/run_report.json, for the approved angles")
    args = parser.parse_args()

    started = time.time()
    handle = tifffile.TiffFile(args.slide)
    series = handle.series[0]
    if args.level >= len(series.levels):
        print(f"slide has {len(series.levels)} levels", file=sys.stderr)
        return 2
    level = series.levels[args.level]
    full_height, full_width = series.levels[0].shape[1:]
    height, width = level.shape[1:]
    # The pyramid halves each time but never lands on an exact ratio, so measure it rather
    # than assuming 2 ** level: a half-pixel error here walks the crop off the core.
    scale = full_width / width

    names = ome_channel_names(handle)
    wanted = ([int(v) for v in args.channels.split(",") if v.strip() != ""]
              if args.channels.strip() else list(range(len(names))))

    # A core is only worth looking at standing up. The angle lives in the orientation
    # report, not in the grid, so read it when the run has got that far and leave it at zero
    # when it has not: the page rotates by whatever this says.
    rotation: dict[str, float] = {}
    if args.orientation and args.orientation.is_file():
        report = json.loads(args.orientation.read_text("utf-8"))
        for entry in report.get("cores") or []:
            name = str(entry.get("core") or "")
            base = entry.get("rotateToTopDeg")
            if not isinstance(base, (int, float)):
                continue
            adjust = entry.get("webRotationAdjustmentDeg")
            rotation[name] = float(base) + (float(adjust) if isinstance(adjust, (int, float)) else 0.0)

    cores = read_grid(args.grid)
    present = [core for core in cores if not core["missing"]]
    tiles_dir = args.out / "tiles"
    tiles_dir.mkdir(parents=True, exist_ok=True)

    marker_index = 0
    channels_out = []
    for position, channel in enumerate(wanted):
        name = names[channel]
        kind = channel_kind(name)
        colour = DEFAULT_COLOURS[kind]
        if kind == "marker":
            colour = MARKER_COLOUR_CYCLE[marker_index % len(MARKER_COLOUR_CYCLE)]
            marker_index += 1

        plane = level.asarray(key=channel)
        # One display range per channel taken from the whole slide, so two cores from
        # different rows can be compared. A per-core range would make every core look the
        # same and hide exactly the difference the figure is being built to show.
        low, high = np.percentile(plane, (LOW_PERCENTILE, HIGH_PERCENTILE))
        span = max(float(high) - float(low), 1.0)

        for core in present:
            half = int(round(core["diameter"] * ROTATION_SUPPORT / 2 / scale))
            cx = int(round(core["centerX"] / scale))
            cy = int(round(core["centerY"] / scale))
            x0, y0 = max(cx - half, 0), max(cy - half, 0)
            x1, y1 = min(cx + half, width), min(cy + half, height)
            crop = plane[y0:y1, x0:x1]
            if crop.size == 0:
                continue
            if crop.shape[0] != crop.shape[1]:
                # A core at the slide edge comes back short on one side. Pad rather than
                # stretch, or the tile no longer lines up with the circle drawn over it.
                square = np.zeros((2 * half, 2 * half), dtype=crop.dtype)
                square[:crop.shape[0], :crop.shape[1]] = crop
                crop = square
            scaled = np.clip((crop.astype(np.float32) - float(low)) / span * 255.0, 0, 255)
            image = Image.fromarray(scaled.astype(np.uint8), mode="L")
            image = image.resize((TILE_PX, TILE_PX), Image.LANCZOS)
            core_dir = tiles_dir / core["core"]
            core_dir.mkdir(exist_ok=True)
            image.save(core_dir / f"{channel:02d}.png", optimize=True)

        channels_out.append({
            "index": channel,
            "name": name,
            "kind": kind,
            "colour": colour,
            "displayLow": round(float(low), 3),
            "displayHigh": round(float(high), 3),
        })
        print(f"  channel {channel:2d} {name:<10} {time.time() - started:6.1f} s", flush=True)

    manifest = {
        "schemaVersion": 1,
        "image": args.slide.name,
        "gridRows": max(core["row"] for core in cores),
        "gridCols": max(core["col"] for core in cores),
        "tilePx": TILE_PX,
        "rotationSupport": ROTATION_SUPPORT,
        "sourceLevel": args.level,
        "sourceDownsample": round(scale, 4),
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "channels": channels_out,
        "cores": [{
            "index": core["index"], "core": core["core"],
            "row": core["row"], "col": core["col"],
            "missing": core["missing"],
            # The page rotates the tile by this, which is why the tile is cut wider than the
            # core. Zero means the run has not been oriented yet, not that the core is upright.
            "rotationDeg": round(rotation.get(core["core"], 0.0), 3),
        } for core in cores],
    }
    (args.out / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", "utf-8")
    print(f"{len(present)} cores x {len(channels_out)} channels in {time.time() - started:.1f} s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
