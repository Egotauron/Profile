#!/usr/bin/env python3
"""Builds the app's night map style from OpenFreeMap's "dark" style.

OpenFreeMap's dark style draws roads almost black on a black background (#181818 on #0c0c0c),
so streets off your route are hard to see while navigating. This repaints roads, paths,
railways and street names in readable greys (brighter for bigger roads) and leaves everything
else as it is. Tiles, fonts and icons still come from OpenFreeMap.

    python3 tools/night_style.py   # writes app/src/main/assets/styles/night.json
"""
import json
import pathlib
import urllib.request

SOURCE = "https://tiles.openfreemap.org/styles/dark"
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/styles/night.json"

# layer id -> paint changes. Background is rgb(12,12,12), buildings rgb(10,10,10).
PAINT = {
    "highway_path": {"line-color": "#5a5f66", "line-opacity": 1},
    "highway_minor": {"line-color": "#41464d", "line-opacity": 1},
    "highway_major_casing": {"line-color": "rgba(110,116,124,0.9)"},
    "highway_major_inner": {"line-color": "#4a5058"},
    "highway_major_subtle": {"line-color": "#4a4f56"},
    "highway_motorway_casing": {"line-color": "rgba(130,136,144,0.9)"},
    "highway_motorway_subtle": {"line-color": "#4a4f56"},
    "road_pier": {"line-color": "#33373c"},
    "aeroway-taxiway": {"line-color": "#33373c"},
    "railway": {"line-color": "#3d4146"},
    "railway_minor": {"line-color": "#3d4146"},
    "railway_transit": {"line-color": "#3d4146"},
    "highway_name_other": {"text-color": "#a3a9b0", "text-halo-color": "rgba(0,0,0,0.9)", "text-halo-width": 1.2},
    "highway_name_motorway": {"text-color": "#b4bac1"},
}


def build(style: dict) -> dict:
    found = set()
    for layer in style["layers"]:
        changes = PAINT.get(layer["id"])
        if changes:
            layer.setdefault("paint", {}).update(changes)
            found.add(layer["id"])
        # Motorways: keep the low-zoom fade, but end on grey instead of black.
        if layer["id"] == "highway_motorway_inner":
            layer["paint"]["line-color"] = ["interpolate", ["linear"], ["zoom"], 5.8, "hsla(0,0%,85%,0.53)", 6, "#565c64"]
            found.add(layer["id"])
    missing = set(PAINT) - found
    if missing:
        raise SystemExit(f"OpenFreeMap's dark style no longer has layers {sorted(missing)}; update PAINT.")
    style["name"] = "Waypoint night (OpenFreeMap dark, readable roads)"
    return style


if __name__ == "__main__":
    request = urllib.request.Request(SOURCE, headers={"User-Agent": "waypoint-style-builder"})
    with urllib.request.urlopen(request, timeout=30) as r:
        style = json.load(r)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(build(style), separators=(",", ":")))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
