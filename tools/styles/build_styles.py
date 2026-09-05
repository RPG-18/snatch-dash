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
# diacritics), Cyrillic, and the punctuation block that carries dashes and "№".
GLYPH_RANGES = ["0-255", "256-511", "1024-1279", "8192-8447"]
GLYPH_FONTS = ["Noto Sans Regular", "Noto Sans Bold"]

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

    print("glyphs: downloading the prebuilt release (59 MB, 8 files kept)")
    archive = zipfile.ZipFile(io.BytesIO(fetch(FONTS_ZIP)))
    total = 0
    for font in GLYPH_FONTS:
        target = ASSETS / "glyphs" / font
        target.mkdir(parents=True, exist_ok=True)
        for rng in GLYPH_RANGES:
            data = archive.read(f"{font}/{rng}.pbf")
            (target / f"{rng}.pbf").write_bytes(data)
            total += len(data)
    print(f"glyphs: {len(GLYPH_FONTS) * len(GLYPH_RANGES)} files, {total // 1024} KiB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
