#!/usr/bin/env python3
"""Проверка собранных .pmtiles: не потерял ли пак часть субъекта.

Конвейер умеет собрать формально исправный, но фактически дырявый пак. Если
Overpass отдаёт обрезанную границу (HTTP 200 + неполные `elements` + поле
`remark`), osmium режет по рваному полигону, planetiler честно печёт тайлы из
того, что ему дали, а index.json показывает валидный sha256 файла, в котором
нет половины области. Так из сборки 2026-08-31 выпал Курган — город целиком,
вместе со слоями building/housenumber/place. Снаружи это выглядит просто как
«пак подозрительно маленький», и то если знать, каким он должен быть.

Проверяется:

  1. **Структура.** PMTiles v3, тип тайлов MVT, maxzoom совпадает с
     planetiler.yaml (иначе рендер будет оверзумить не оттуда, откуда ждёт).
  2. **Слои.** Набор слоёв OpenMapTiles, который есть у любого субъекта РФ
     (REQUIRED_LAYERS), минус явно отключённое через exclude_layers. Это и
     ловит потерю городов: без застройки не бывает building/housenumber.
  3. **Комплектность.** Каждой границе polygons/<код>.osm соответствует пак.
     Ловит регион, молча выпавший из сборки: fetch_boundaries.py пропускает
     субъект, если Overpass так и не отдал целый ответ.

Читает и локальный out/, и уже опубликованный бакет (--base-url) — во втором
случае через HTTP range-запросы, паки целиком не качаются.

    python3 validate_packs.py                      # локальный out/
    python3 validate_packs.py --only ru --verbose
    python3 validate_packs.py --base-url https://storage.yandexcloud.net/snatch-dash-maps/

Ненулевой код возврата = хотя бы один пак не прошёл, можно вешать в CI после
сборки. См. tools/planetiler/README.md.

**Что пробовали и что не работает** (чтобы не изобретать заново): доля случайных
точек внутри границы, для которых есть тайл на maxzoom — не различает сломанный
пак и разреженный. Замер по сборке 2026-08-31: сломанная Курганская область 9%,
здоровая соседняя Тюменская 8% — planetiler не печёт тайл там, где в OSM просто
ничего нет, и в тайге таких тайлов большинство. Отношение числа тайлов z14 к
числу z10 (плотность, не зависящая от площади) тоже не разделяет: 28 у
Курганской против 37 у худшей здоровой — разрыв слишком мал для порога.
"""
from __future__ import annotations

import argparse
import gzip
import json
import logging
import struct
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

import common
import yaml

log = logging.getLogger("validate_packs")

PLANETILER_YAML = common.ROOT / "planetiler.yaml"

# Слои, которые в схеме OpenMapTiles есть у любого субъекта РФ. Проверено по
# сборке 2026-08-31: набор целиком присутствует во всех 78 здоровых паках и
# отсутствует ровно у шести испорченных. Сюда намеренно НЕ входят park,
# mountain_peak, aeroway, aerodrome_label и water_name — они законно бывают
# пустыми (нет гор/аэродромов/именованных водоёмов), и требовать их значило бы
# ловить ложные срабатывания.
REQUIRED_LAYERS = frozenset({
    "boundary", "building", "housenumber", "landcover", "landuse", "place",
    "poi", "transportation", "transportation_name", "water", "waterway",
})


# ── Чтение PMTiles v3 ────────────────────────────────────────────────────────
# Формат: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
# Свой минимальный ридер, а не зависимость: нужны только заголовок и метаданные,
# тела тайлов не читаются вовсе.

def read_varint(buf: bytes, i: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = buf[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


class LocalSource:
    def __init__(self, path: Path) -> None:
        self.name = str(path)
        self._f = path.open("rb")

    def read(self, offset: int, length: int) -> bytes:
        self._f.seek(offset)
        return self._f.read(length)

    def close(self) -> None:
        self._f.close()


class HttpSource:
    """Тот же интерфейс, но кусками по HTTP Range — чтобы проверять опубликованное, не скачивая."""

    # Бакет изредка рвёт TLS-хендшейк при частых новых соединениях — на прогоне
    # в 84 пака это поймаешь почти наверняка, поэтому ретраи здесь, а не
    # «перезапустите проверку».
    RETRIES = 4
    RETRY_DELAY_SECONDS = 1.5

    def __init__(self, url: str) -> None:
        self.name = url

    def read(self, offset: int, length: int) -> bytes:
        req = urllib.request.Request(self.name, headers={"Range": f"bytes={offset}-{offset + length - 1}"})
        last_error: Exception | None = None
        for attempt in range(1, self.RETRIES + 1):
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    return resp.read()
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                last_error = exc
                log.debug("range %d+%d не удался (попытка %d/%d): %s", offset, length, attempt, self.RETRIES, exc)
                if attempt < self.RETRIES:
                    time.sleep(self.RETRY_DELAY_SECONDS * attempt)
        assert last_error is not None
        raise last_error

    def close(self) -> None:
        pass


class Pmtiles:
    def __init__(self, source) -> None:
        self.src = source
        header = source.read(0, 127)
        if len(header) < 127 or header[:7] != b"PMTiles" or header[7] != 3:
            raise ValueError("не PMTiles v3 (сигнатура/версия не совпали)")
        (self.root_offset, self.root_length, self.meta_offset, self.meta_length,
         self.leaf_offset, self.leaf_length, self.data_offset, self.data_length,
         self.addressed_tiles, self.tile_entries, self.tile_contents) = struct.unpack_from("<11Q", header, 8)
        (self.clustered, self.internal_compression, self.tile_compression,
         self.tile_type, self.min_zoom, self.max_zoom) = struct.unpack_from("<6B", header, 96)

    def metadata(self) -> dict:
        raw = self.src.read(self.meta_offset, self.meta_length)
        if self.internal_compression == 2:
            raw = gzip.decompress(raw)
        return json.loads(raw)

    def layers(self) -> set[str]:
        return {layer["id"] for layer in self.metadata().get("vector_layers", [])}


# ── Проверки ─────────────────────────────────────────────────────────────────

def resolve_flags(tuning: dict, code: str) -> dict:
    """Те же правила слияния, что и в build_pmtiles.py: subjects > countries > defaults."""
    iso = code.split("-")[0]
    merged = dict(tuning.get("defaults") or {})
    merged.update((tuning.get("countries") or {}).get(iso, {}))
    merged.update((tuning.get("subjects") or {}).get(code, {}))
    return merged


def check_pack(code: str, source, tuning: dict) -> list[str]:
    """-> список проблем; пустой список = пак в порядке."""
    pack = Pmtiles(source)
    problems: list[str] = []

    if pack.tile_type != 1:
        problems.append(f"tile_type={pack.tile_type}, ожидался 1 (MVT)")

    flags = resolve_flags(tuning, code)
    want_maxzoom = flags.get("maxzoom")
    if want_maxzoom is not None and pack.max_zoom != int(want_maxzoom):
        problems.append(f"maxzoom={pack.max_zoom}, в planetiler.yaml {want_maxzoom}")

    missing = (set(REQUIRED_LAYERS) - set(flags.get("exclude_layers") or [])) - pack.layers()
    if missing:
        problems.append(f"нет слоёв: {', '.join(sorted(missing))}")

    return problems


def check_completeness(codes: set[str], polygons_dir: Path, iso: str) -> list[str]:
    """Каждой границе — свой пак. Ловит субъект, молча выпавший из сборки."""
    if not polygons_dir.exists():
        log.debug("%s: нет %s — проверка комплектности пропущена", iso, polygons_dir)
        return []
    expected = {p.name.removesuffix(".osm") for p in polygons_dir.glob(f"{iso}-*.osm")}
    if not expected:
        return []
    # Антимеридианный субъект даёт два пака из одной границы (ru-chu + ru-chu-east),
    # поэтому лишним считается только пак, у которого нет границы и после снятия
    # суффикса -east (см. split.py).
    missing = sorted(expected - codes)
    extra = sorted(c for c in codes - expected if c.removesuffix("-east") not in expected)
    out = []
    if missing:
        out.append(f"нет паков для границ: {', '.join(missing)}")
    if extra:
        out.append(f"паки без границы в polygons/: {', '.join(extra)}")
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--regions", type=Path, default=common.REGIONS_YAML)
    parser.add_argument("--tuning", type=Path, default=PLANETILER_YAML)
    parser.add_argument("--out-dir", type=Path, default=common.OUT_DIR)
    parser.add_argument("--polygons-dir", type=Path, default=common.POLYGONS_DIR)
    parser.add_argument("--base-url", help="проверять опубликованные паки по HTTP range вместо локального out/")
    parser.add_argument("--only", help="ограничиться странами по iso, через запятую")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    common.setup_logging(args.verbose)

    countries = common.filter_by_iso(common.enabled_countries(args.regions), args.only)
    if not countries:
        log.info("Нет enabled-стран — проверять нечего.")
        return 0

    tuning = {}
    if args.tuning.exists():
        tuning = yaml.safe_load(args.tuning.read_text(encoding="utf-8")) or {}

    failed = checked = 0
    for country in countries:
        entries = pack_entries(country.iso, args)
        if not entries:
            log.error("%s: нечего проверять — паков нет", country.iso)
            failed += 1
            continue

        total = len(entries)
        for i, (code, location) in enumerate(entries, start=1):
            source = HttpSource(location) if args.base_url else LocalSource(location)
            try:
                problems = check_pack(code, source, tuning)
            except Exception as exc:  # битый файл — тоже результат проверки, а не повод падать
                problems = [f"не читается: {exc}"]
            finally:
                source.close()
            checked += 1
            if problems:
                failed += 1
                log.error("[%d/%d] %-14s ПЛОХО %s", i, total, code, "; ".join(problems))
            else:
                log.info("[%d/%d] %-14s OK", i, total, code)

        for problem in check_completeness({c for c, _ in entries}, args.polygons_dir, country.iso):
            log.error("%s: %s", country.iso, problem)
            failed += 1

    if failed:
        log.error(
            "Проверено %d паков, проблем %d. Перечисленные субъекты пересобрать с шага 1: удалить их "
            "polygons/<код>.osm и extracts/<код>*.osm.pbf, затем fetch_boundaries.py --code <код> и дальше по README.",
            checked, failed,
        )
        return 1
    log.info("Проверено %d паков, все в порядке.", checked)
    return 0


def pack_entries(iso: str, args) -> list[tuple[str, object]]:
    """[(код, путь-или-URL)] — из index.json опубликованного бакета или из локального out/<iso>/."""
    if args.base_url:
        base = args.base_url.rstrip("/")
        with urllib.request.urlopen(base + "/index.json", timeout=30) as resp:
            index = json.load(resp)
        return [
            (r["code"], f"{base}/{r['path']}")
            for c in index.get("countries", []) if c["iso"] == iso
            for r in c["regions"]
        ]
    return [(p.name.removesuffix(".pmtiles"), p) for p in sorted((args.out_dir / iso).glob("*.pmtiles"))]


if __name__ == "__main__":
    sys.exit(main())
