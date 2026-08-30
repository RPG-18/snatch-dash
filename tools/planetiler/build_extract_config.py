#!/usr/bin/env python3
"""polygons/<code>.osm -> extracts/<iso>-extracts.json (конфиг для `osmium extract`).

Работает только для enabled-стран с mode: subjects (на данный момент — только
Россия). Для mode: whole нарезка не нужна вообще — см. split.py, который
просто копирует скачанный .osm.pbf в extracts/<iso>.osm.pbf как есть.

osmium умеет резать сразу на много экстрактов за один проход по входному
файлу — поэтому один конфиг на страну, а не отдельный вызов на каждый
субъект (иначе на файле размером с russia-latest.osm.pbf это в 80+ раз дольше).

См. tools/planetiler/plan.md.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

import common

log = logging.getLogger("build_extract_config")


def build_config(country: common.Country, polygons_dir: Path, extracts_dir: Path) -> dict:
    pattern = f"{country.iso}-*.osm"
    polygon_files = sorted(polygons_dir.glob(pattern))
    if not polygon_files:
        log.warning(
            "Нет файлов границ %s в %s — сначала запустить fetch_boundaries.py", pattern, polygons_dir,
        )

    extracts = []
    for polygon_path in polygon_files:
        code = polygon_path.stem  # ru-mow.osm -> ru-mow
        extracts.append(
            {
                "output": f"{code}.osm.pbf",
                "polygon": {"file_name": str(polygon_path.resolve()), "file_type": "osm"},
            }
        )
    return {"directory": str(extracts_dir.resolve()), "extracts": extracts}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--regions", type=Path, default=common.REGIONS_YAML)
    parser.add_argument("--polygons-dir", type=Path, default=common.POLYGONS_DIR)
    parser.add_argument("--extracts-dir", type=Path, default=common.EXTRACTS_DIR)
    parser.add_argument("--only", help="ограничиться странами по iso, через запятую")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    common.setup_logging(args.verbose)

    countries = [c for c in common.filter_by_iso(common.enabled_countries(args.regions), args.only) if c.mode == "subjects"]
    if not countries:
        log.info("Нет enabled-стран с mode: subjects — писать нечего.")
        return 0

    args.extracts_dir.mkdir(parents=True, exist_ok=True)

    for country in countries:
        config = build_config(country, args.polygons_dir, args.extracts_dir)
        config_path = args.extracts_dir / f"{country.iso}-extracts.json"
        config_path.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        log.info("%s: %d экстрактов -> %s", country.iso, len(config["extracts"]), config_path)

    return 0


if __name__ == "__main__":
    sys.exit(main())
