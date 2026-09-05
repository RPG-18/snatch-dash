#!/usr/bin/env python3
"""Prepares the dash map styles, glyphs and sprites as Android assets.

Upstream Positron and Dark Matter cannot be used as they ship: they point at
MapTiler for glyphs (the very dependency offline maps exist to remove), at
GitHub Pages for sprites, and they style layers our tile corpus does not carry.
This script downloads them and applies exactly those three fixes, so the result
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

STYLES = {
    "positron": "https://raw.githubusercontent.com/openmaptiles/positron-gl-style/master/style.json",
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


def single_font(fonts: list[str]) -> list[str]:
    """Maps a paired upstream stack onto one of our two faces."""
    joined = " ".join(fonts).lower()
    bold = "bold" in joined or "medium" in joined or "semibold" in joined
    return [FONT_BOLD if bold else FONT_REGULAR]


def transform(style: dict, name: str) -> tuple[dict, list[str]]:
    style["glyphs"] = "asset://glyphs/{fontstack}/{range}.pbf"
    style["sprite"] = f"asset://sprites/{name}"

    kept, dropped = [], []
    for layer in style["layers"]:
        source_layer = layer.get("source-layer")
        # Background has no source at all and must survive: it is the only layer
        # that paints when no pack covers the viewport.
        if source_layer is not None and source_layer not in AVAILABLE_SOURCE_LAYERS:
            dropped.append(f"{layer['id']} ({source_layer})")
            continue
        layout = layer.get("layout")
        if layout and "text-font" in layout:
            layout["text-font"] = single_font(layout["text-font"])
        kept.append(layer)

    style["layers"] = kept
    # Keep exactly one source under the placeholder id; the assembler fans it out.
    style["sources"] = {SOURCE_ID: {"type": "vector", "url": "pmtiles://placeholder"}}
    return style, dropped


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
