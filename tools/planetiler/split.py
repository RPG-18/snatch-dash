#!/usr/bin/env python3
"""Режет скачанные .osm.pbf на extracts/*.osm.pbf.

mode: subjects — запускает `osmium extract --strategy=smart -c <iso>-extracts.json`
                 по конфигу, собранному build_extract_config.py.
mode: whole     — нарезка не нужна вообще, скачанный файл копируется как есть
                 в extracts/<iso>.osm.pbf — Geofabrik уже обрезал его по стране.

Ожидает исходные .osm.pbf в downloads/<iso>-latest.osm.pbf — они не
скачиваются автоматически (см. plan.md: `source` в regions.yaml — просто
ссылка, куда сходить руками, а не URL для авто-загрузки).

На Python, а не shell — чтобы конвейер не зависел от bash/zsh и одинаково
работал под Windows/macOS/Linux (см. tools/planetiler/plan.md).
"""
from __future__ import annotations

import argparse
import logging
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Optional

import common

log = logging.getLogger("split")


def require_osmium() -> str:
    path = shutil.which("osmium")
    if not path:
        log.error("Бинарник `osmium` не найден в PATH — установите osmium-tool.")
        sys.exit(1)
    return path


def split_subjects(country: common.Country, extracts_dir: Path, osmium_bin: str, strategy: str) -> None:
    config_path = extracts_dir / f"{country.iso}-extracts.json"
    if not config_path.exists():
        log.error("%s: нет %s — сначала запустить build_extract_config.py", country.iso, config_path)
        return
    source = country.download_path
    if not source.exists():
        log.error(
            "%s: нет исходного файла %s — скачайте его вручную (%s)", country.iso, source, country.source,
        )
        return

    cmd = [osmium_bin, "extract", f"--strategy={strategy}", "-c", str(config_path), str(source)]
    log.info("%s: %s", country.iso, " ".join(cmd))
    subprocess.run(cmd, check=True)


def split_whole(country: common.Country, extracts_dir: Path) -> None:
    source = country.download_path
    if not source.exists():
        log.error(
            "%s: нет исходного файла %s — скачайте его вручную (%s)", country.iso, source, country.source,
        )
        return
    dest = extracts_dir / f"{country.iso}.osm.pbf"
    log.info("%s: копирую %s -> %s (mode: whole, нарезка не нужна)", country.iso, source, dest)
    shutil.copy2(source, dest)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--regions", type=Path, default=common.REGIONS_YAML)
    parser.add_argument("--extracts-dir", type=Path, default=common.EXTRACTS_DIR)
    parser.add_argument("--strategy", default="smart", help="стратегия osmium extract (по умолчанию smart)")
    parser.add_argument("--only", help="ограничиться странами по iso, через запятую")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    common.setup_logging(args.verbose)

    countries = common.filter_by_iso(common.enabled_countries(args.regions), args.only)
    if not countries:
        log.info("Нет enabled-стран — делать нечего.")
        return 0

    osmium_bin: Optional[str] = None
    if any(c.mode == "subjects" for c in countries):
        osmium_bin = require_osmium()

    args.extracts_dir.mkdir(parents=True, exist_ok=True)

    for country in countries:
        if country.mode == "subjects":
            split_subjects(country, args.extracts_dir, osmium_bin, args.strategy)
        elif country.mode == "whole":
            split_whole(country, args.extracts_dir)
        else:
            log.warning("%s: неизвестный mode %r, пропускаю", country.iso, country.mode)

    return 0


if __name__ == "__main__":
    sys.exit(main())
