#!/usr/bin/env python3
"""Prepares the dash map styles, glyphs and sprites as Android assets.

Upstream Positron and Dark Matter cannot be used as they ship: they point at
MapTiler for glyphs (the very dependency offline maps exist to remove), at
GitHub Pages for sprites, and they style layers our tile corpus does not carry.
This script downloads them and applies exactly those six fixes, so the result
can be regenerated when upstream moves instead of being hand-patched once.

Output lands in the engine module's Android assets — *not* Flutter's `assets:`.
MapLibre resolves `asset://` through the Android AssetManager, and Flutter
assets live under a different prefix inside the APK.

Run: python3 tools/styles/build_styles.py
Read alongside: spec/drawing_from_local_tiles.md, "Стиль: берём готовый и правим".
"""

from __future__ import annotations

import io
import json
import pathlib
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "packages/opendash_dash_engine/android/src/main/assets"

# osm-bright вместо positron с 2026-09-07: Positron задуман бледной подложкой
# под данные, а на панели 526×300 при солнце его контраста не хватает. У
# osm-bright цветные дороги и нормальная читаемость, та же схема OpenMapTiles и
# та же открытая лицензия, а фонтстеки у него уже одиночные — правок transform()
# не потребовалось. Цена: 121 слой против 46, стиль тяжелее и снапшот дороже.
STYLES = {
    "osm-bright": "https://raw.githubusercontent.com/openmaptiles/osm-bright-gl-style/master/style.json",
    "dark-matter": "https://raw.githubusercontent.com/openmaptiles/dark-matter-gl-style/master/style.json",
}
SPRITE_BASE = "https://openmaptiles.github.io/{style}-gl-style/{file}"
# Both ratios, and both halves of each: a sheet without its JSON is not a
# partial sprite, it is a style resource that fails to load. The dash renders at
# pixelRatio 1 today, so only the plain pair is actually read — but the @2x png
# was already being shipped, and shipping half of a pair is how the first change
# of ratio turns into missing icons.
SPRITE_FILES = ["sprite.json", "sprite.png", "sprite@2x.json", "sprite@2x.png"]

FONTS_ZIP = "https://github.com/openmaptiles/fonts/releases/download/v2.0/noto-sans.zip"
# One block of 256 code points each. Latin, Latin Extended-A (transliteration
# diacritics), Latin Extended-B + IPA + spacing modifiers, Cyrillic, and the
# punctuation block that carries dashes and "№".
#
# `512-767` is here because of the 2026-09-05 ride: a label somewhere around
# Верхние Осельки carries a character out of that block (U+02BC and friends turn
# up in transliterated names), and without the file the map froze for the rest of
# the session — see GLYPH_RANGE_COUNT for why one missing file could do that.
#
# That particular route into the block is gone now that labels show `name` alone
# (see LOCAL_NAME_FIELD), but the Latin blocks stay: `name` in OSM is whatever
# the mapper typed, Latin included, and 220 KiB is not worth guessing about.
GLYPH_RANGES = ["0-255", "256-511", "512-767", "1024-1279", "8192-8447"]
GLYPH_FONTS = ["Noto Sans Regular", "Noto Sans Bold"]

# Every block MapLibre can ask for: the BMP in 256-code-point steps. Anything not
# in GLYPH_RANGES is shipped as a valid but empty glyph set rather than left out.
#
# **A glyph range that is missing from the assets is not a missing label, it is a
# dead map.** MapLibre answers `Could not read asset`, the snapshotter never
# finishes that render, and MapSnapshotProvider's rebuild lands on the same style
# and the same absent file — a loop it cannot leave, one abandoned bitmap per five
# seconds, no map until the rider disconnects. Field logs of 2026-09-05: two
# sessions frozen, one of them for its whole 11 minutes.
#
# An empty stack costs ~30 bytes and turns that into what it should have been all
# along — a character that does not draw. The bet this makes explicit: we cannot
# predict the code points in the corpus (OpenMapTiles `name` is whatever OSM
# holds), so the safety net has to cover everything we did not think of.
GLYPH_RANGE_COUNT = 256

# The twelve layers planetiler actually emits. Everything else upstream styles
# reference — landcover, water_name, housenumber, mountain_peak — is excluded
# from the corpus globally, so those layers would silently draw nothing.
AVAILABLE_SOURCE_LAYERS = {
    "water", "waterway", "transportation", "transportation_name", "boundary",
    "aeroway", "place", "poi", "building", "landuse", "park", "aerodrome_label",
}

# Layers the dash has no use for, dropped whole. Measured against the shipped styles at
# z13-15 (the ladder the dash actually uses): every one of these draws at 1.2 px or less
# there, which on a 526x300 panel read at a glance is not a feature — it is a shimmering
# hair that also costs bitrate, because fine detail is exactly what an encoder spends bits
# on. Rivers are NOT here: planetiler.yaml keeps water at any cost because it is what a
# rider navigates by.
#
#   railway *-hatching   0 px until z15, decoration on top of the rail line itself
#   ferry / cablecar     1.1-1.6 px, and neither is reachable on a motorcycle
#   *-path, *-steps      1.2 px footpaths
#   pier                 1.0 px
#   service-track        0 px at z14, 1 px casing under nothing
#   waterway-other,
#   stream-canal, tunnel 0.5-1.2 px ditches and culverts
#   taxiway              3 px of airport apron; the runway stays as a landmark
DROPPED_LAYERS = (
    "-hatching", "ferry", "cablecar", "path", "steps", "pier",
    "service-track", "waterway-other", "stream-canal", "waterway_tunnel", "taxiway",

    # Not a hairline — a texture. osm-bright draws `water-pattern` over every lake and
    # river: a `wave` sprite tiled across the whole surface. On a phone it is a nice
    # cartographic touch; on the dash it is the worst possible content for the encoder,
    # because a repeating high-contrast pattern over a large area is exactly what an
    # inter-frame codec cannot predict away, and it is spent on water — the one thing a
    # rider needs only as a silhouette. Rides around lakes were the first place we noticed.
    "water-pattern",

    # Invisible overdraw in our band. `water-offset` is the same water fill drawn shifted,
    # and the shift is `[[6, [2, 0]], [8, [0, 0]]]` — zero from z8 up, i.e. across the whole
    # dash ladder (z11-15) it is an exact copy sitting under the `water` layer that covers
    # it. A full-screen fill rendered for nothing on a lake.
    "water-offset",
)

# What survives is drawn at least this wide. The railway family sits at 0.40 px at z14 —
# a level crossing is worth knowing about, so it is thickened rather than dropped.
MIN_LINE_WIDTH_PX = 2.0

# Roads get this much wider than upstream draws them. Upstream is designed for a phone held
# still at arm's length; this is a 526x300 panel behind a bezel, glanced at while moving.
# Applied to the whole `transportation` family so casings keep their proportion to the fill
# they outline — a scaled road with an unscaled casing is a road with no outline left.
ROAD_WIDTH_SCALE = 1.5


# The zoom the widths are judged at.
#
# Left at 14 through the 2026-09-09 rebuild of the corpus to z10-15, but the two reasons
# that picked it have come apart and it should be re-decided on the panel, not here. It
# used to be both the corpus's own detail ceiling AND the middle of the dash's ladder;
# the ceiling is now 15 and the ladder is ZOOM_MIN..ZOOM_MAX = 10..15, whose middle is
# 12.5. Moving this number silently restyles every road in the corpus, so it is not a
# side effect anyone should take from a zoom-range change — measure a frame at 10, 12 and
# 15 first.
JUDGED_AT_ZOOM = 14


def width_at(value, zoom: float):
    """
    What a `line-width` evaluates to at [zoom], for the two forms upstream ships side by
    side: osm-bright is still on legacy stop functions (`{"base": 1.2, "stops": [[13, 0.5],
    …]}`), dark-matter on expressions (`["interpolate", ["exponential", 1.2], ["zoom"], …]`).
    A transform that understands only one of them silently does nothing to the other style.
    """
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, dict) and "stops" in value:
        base, stops = value.get("base", 1), value["stops"]
    elif isinstance(value, list) and value and value[0] == "interpolate":
        interp = value[1]
        base = interp[1] if isinstance(interp, list) and len(interp) > 1 else 1
        flat = value[3:]
        stops = [[flat[i], flat[i + 1]] for i in range(0, len(flat) - 1, 2)]
    elif isinstance(value, list) and value and value[0] == "step":
        out = value[2]
        for i in range(3, len(value) - 1, 2):
            if zoom >= value[i]:
                out = value[i + 1]
        return float(out) if isinstance(out, (int, float)) else None
    else:
        # `case`, `match`, a data-driven width — anything this does not read cannot be
        # judged, and silently leaving it alone is how a hairline survives the transform.
        # The caller prints this; see main().
        return None
    pts = [(z, v) for z, v in stops if isinstance(z, (int, float)) and isinstance(v, (int, float))]
    if not pts:
        return None
    if zoom <= pts[0][0]:
        return float(pts[0][1])
    if zoom >= pts[-1][0]:
        return float(pts[-1][1])
    for (z0, v0), (z1, v1) in zip(pts, pts[1:]):
        if z0 <= zoom <= z1:
            t = (zoom - z0) / (z1 - z0) if base == 1 else (base ** (zoom - z0) - 1) / (base ** (z1 - z0) - 1)
            return v0 + t * (v1 - v0)
    return None


def scale_width(value, factor: float):
    """
    Multiply every number a `line-width` can produce by [factor].

    Uniformly, and that is the point: an earlier version clamped each stop to the minimum
    width instead, which quietly reshaped every curve whose LOW-zoom stops sat under the
    floor. Country boundaries came out WIDER than upstream at z14 (7.88 -> 8.52 px) — a
    layer this transform has no business touching at all — because lifting their z0 stop
    from 0.6 to 2.0 tilted the whole interpolation. Scaling keeps the shape and moves only
    what the caller asked to move.

    Zero is left alone. In these styles a width of 0 means "not drawn at this zoom yet"
    (highway-minor is 0 at z13, 2.5 at z14), and a multiplied zero is still zero — but it
    also must not be lifted by the floor logic in [line_factor], which is why that one
    reads the width at a zoom where the layer is actually drawn.
    """
    def mul(v):
        if not isinstance(v, (int, float)) or v == 0:
            return v
        return round(v * factor, 2)

    if isinstance(value, (int, float)):
        return mul(value)
    if isinstance(value, dict) and "stops" in value:
        out = dict(value)
        out["stops"] = [[z, mul(v)] for z, v in value["stops"]]
        return out
    if isinstance(value, list) and value and value[0] in ("interpolate", "step"):
        out = list(value)
        # Both forms put an output at every second element from index 4:
        #   ["interpolate", interpolation, input, z0, out0, z1, out1, …]
        #   ["step",        input, default,       z0, out0, z1, out1, …]
        # and only `step` carries a default, at index 2. Getting this wrong is silent and
        # ruinous: multiplying from index 3 scales the ZOOM thresholds instead of the widths,
        # so a step road ends up drawn at its default of 0.75 px across the whole ladder.
        if value[0] == "step" and isinstance(out[2], (int, float)):
            out[2] = mul(out[2])
        for i in range(4, len(out), 2):
            out[i] = mul(out[i])
        return out
    return value


def line_factor(layer: dict) -> float:
    """How much wider this line has to be drawn for the dash. 1.0 = leave it alone."""
    width = layer.get("paint", {}).get("line-width")
    factor = ROAD_WIDTH_SCALE if layer.get("source-layer") == "transportation" else 1.0
    at_judged = width_at(width, JUDGED_AT_ZOOM)
    # Unreadable curves get lifted to the floor rather than by the road factor: the railway
    # family draws at 0.40 px at z14, and a level crossing is worth knowing about.
    if at_judged and 0 < at_judged * factor < MIN_LINE_WIDTH_PX:
        factor = MIN_LINE_WIDTH_PX / at_judged
    return factor


# Upstream uses paired stacks (Metropolis + Noto). MapLibre joins a stack into
# one directory name with commas, and the prebuilt font release has no such
# combination — collapsing to a single family is what makes ready-made glyphs
# usable at all, not just tidier.
FONT_REGULAR = "Noto Sans Regular"
FONT_BOLD = "Noto Sans Bold"

# Placeholder source id. The runtime assembler replaces it with one source per
# installed pack and duplicates every layer accordingly (StyleAssembler.kt).
SOURCE_ID = "openmaptiles"


# Every fetch here is a plain GitHub GET; a minute is generous for the largest of
# them (the 59 MB font zip) and still finite, which the default is not — without
# it a stalled connection hangs the build with no output and no way to tell it
# from slow.
FETCH_TIMEOUT_SECONDS = 60


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=FETCH_TIMEOUT_SECONDS) as response:
        return response.read()


def _pbf_bytes(field: int, value: bytes) -> bytes:
    """One length-delimited protobuf field. Lengths here never reach a second varint byte."""
    assert len(value) < 128
    return bytes([field << 3 | 2, len(value)]) + value


def empty_glyph_pbf(font: str, rng: str) -> bytes:
    """
    A `glyphs` message holding one fontstack and no glyphs at all.

    Shaped exactly like a real range file (`fontstack { name, range }`, field 3
    repeated and empty) so MapLibre parses it on the same path instead of
    special-casing anything: `parseGlyphPBF` yields nothing, the range is marked
    parsed, and every code point in it resolves to "no glyph". The label loses
    those characters and the frame is still drawn — see [GLYPH_RANGE_COUNT].
    """
    stack = _pbf_bytes(1, font.encode()) + _pbf_bytes(2, rng.encode())
    return _pbf_bytes(1, stack)


# Upstream labels every place twice: the transliteration on one line and the
# local name on the other (`{name:latin}\n{name:nonlatin}`), and countries by
# transliteration alone. On a 526x300 dash that is a two-line label saying the
# same thing twice, in a script the rider does not read the map in — so every
# text-field collapses to plain `name`, which OpenMapTiles fills with the local
# name (Cyrillic across our corpus).
LOCAL_NAME_FIELD = ["get", "name"]


def local_name_only(layout: dict) -> None:
    """Drops the transliterated half of a label; leaves `{ref}` shields alone."""
    field = layout.get("text-field")
    if field is not None and "name:" in json.dumps(field):
        layout["text-field"] = list(LOCAL_NAME_FIELD)


def single_font(fonts: list[str]) -> list[str]:
    """Maps a paired upstream stack onto one of our two faces."""
    joined = " ".join(fonts).lower()
    bold = "bold" in joined or "medium" in joined or "semibold" in joined
    return [FONT_BOLD if bold else FONT_REGULAR]


def transform(style: dict, name: str) -> tuple[dict, list[str]]:
    style["glyphs"] = "asset://glyphs/{fontstack}/{range}.pbf"
    style["sprite"] = f"asset://sprites/{name}"

    kept, dropped = [], []
    # Reported by main(): a width form this script cannot read is a line it cannot judge.
    unreadable, widened = [], []
    for layer in style["layers"]:
        source_layer = layer.get("source-layer")
        # Background has no source at all and must survive: it is the only layer
        # that paints when no pack covers the viewport.
        if source_layer is not None and source_layer not in AVAILABLE_SOURCE_LAYERS:
            dropped.append(f"{layer['id']} ({source_layer})")
            continue
        # Matched on the id whatever the layer type is: dropping `highway-path` while
        # keeping `highway-name-path` leaves footpath labels drawn along a line that is no
        # longer there, and `road_area_pier` is the fill under a pier whose line is gone.
        if any(d in layer["id"] for d in DROPPED_LAYERS):
            dropped.append(f"{layer['id']} (dash simplification)")
            continue
        layout = layer.get("layout")
        if layout:
            if "text-font" in layout:
                layout["text-font"] = single_font(layout["text-font"])
            local_name_only(layout)
        if layer.get("type") == "line":
            paint = layer.setdefault("paint", {})
            if "line-width" not in paint:
                # MapLibre's default is 1 px, i.e. exactly the hairline this transform
                # exists to remove — and it is invisible in the style file, which is how
                # dark-matter's `waterway` layer (every river, stream and ditch in one) kept
                # drawing at 1 px while the light theme had its ditches taken out.
                paint["line-width"] = MIN_LINE_WIDTH_PX
                widened.append(f"{layer['id']} (1px default -> {MIN_LINE_WIDTH_PX})")
            else:
                factor = line_factor(layer)
                if factor != 1.0:
                    paint["line-width"] = scale_width(paint["line-width"], factor)
                elif width_at(paint["line-width"], JUDGED_AT_ZOOM) is None:
                    unreadable.append(layer["id"])
        kept.append(layer)

    style["layers"] = kept
    # Keep exactly one source under the placeholder id; the assembler fans it out.
    style["sources"] = {SOURCE_ID: {"type": "vector", "url": "pmtiles://placeholder"}}
    return style, dropped + widened + [f"{i} (width unreadable — NOT scaled)" for i in unreadable]


def main() -> int:
    (ASSETS / "styles").mkdir(parents=True, exist_ok=True)
    (ASSETS / "sprites").mkdir(parents=True, exist_ok=True)
    (ASSETS / "glyphs").mkdir(parents=True, exist_ok=True)

    for name, url in STYLES.items():
        style = json.loads(fetch(url))
        before = len(style["layers"])
        style, dropped = transform(style, name)
        out = ASSETS / "styles" / f"{name}.json"
        out.write_text(json.dumps(style, ensure_ascii=False, separators=(",", ":")))
        print(f"{name}: {before} -> {len(style['layers'])} layers, {out.stat().st_size // 1024} KiB")
        for layer in dropped:
            print(f"    dropped {layer}")

        for file in SPRITE_FILES:
            data = fetch(SPRITE_BASE.format(style=name, file=file))
            (ASSETS / "sprites" / f"{name}{file[len('sprite'):]}").write_bytes(data)

    print(f"glyphs: downloading the prebuilt release (59 MB, {len(GLYPH_RANGES)} ranges kept)")
    archive = zipfile.ZipFile(io.BytesIO(fetch(FONTS_ZIP)))
    real = empty = 0
    for font in GLYPH_FONTS:
        target = ASSETS / "glyphs" / font
        target.mkdir(parents=True, exist_ok=True)
        # Rewritten wholesale rather than merged into what is already there: a
        # range dropped from GLYPH_RANGES has to come back as a placeholder, not
        # linger as a stale real file.
        for existing in target.glob("*.pbf"):
            existing.unlink()
        for block in range(GLYPH_RANGE_COUNT):
            rng = f"{block * 256}-{block * 256 + 255}"
            if rng in GLYPH_RANGES:
                data = archive.read(f"{font}/{rng}.pbf")
                real += len(data)
            else:
                data = empty_glyph_pbf(font, rng)
                empty += len(data)
            (target / f"{rng}.pbf").write_bytes(data)
    kept = len(GLYPH_FONTS) * len(GLYPH_RANGES)
    placeholders = len(GLYPH_FONTS) * GLYPH_RANGE_COUNT - kept
    print(
        f"glyphs: {kept} files with glyphs ({real // 1024} KiB), "
        f"{placeholders} empty placeholders ({empty // 1024} KiB)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
