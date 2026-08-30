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
import json
import logging
import shutil
import subprocess
import sys
import time
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
    """Нарезает на субъекты по одному вызову `osmium extract -p` на каждый.

    НЕ одним `osmium extract -c` со всеми экстрактами сразу: память osmium extract
    растёт как `число_экстрактов × (макс_ид_ноды / 8)` (bitset по глобальным OSM-ид),
    и на России (83 субъекта, иды нод до ~12 млрд) это ~120+ ГБ — OOM на любой
    обычной машине (см. MEMORY USAGE в `osmium help extract`). По одному экстракту
    на вызов — это `1 × (макс_ид/8) ≈ 1.5 ГБ`, но исходник перечитывается N раз
    (медленнее, зато работает). Возобновляемо: уже готовые `<output>` пропускаются.
    """
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

    config = json.loads(config_path.read_text(encoding="utf-8"))
    extracts = config.get("extracts", [])
    directory = Path(config.get("directory", extracts_dir))

    total = len(extracts)
    done = skipped = 0
    for i, ex in enumerate(extracts, start=1):
        output = directory / ex["output"]
        polygon = Path(ex["polygon"]["file_name"])
        if output.exists() and output.stat().st_size > 0:
            log.info("[%d/%d] %s уже существует, пропускаю", i, total, output.name)
            skipped += 1
            continue
        log.info("[%d/%d] %s <- %s", i, total, output.name, polygon.name)
        cmd = [
            osmium_bin, "extract", f"--strategy={strategy}", "--progress", "--overwrite",
            "-p", str(polygon), "-o", str(output), str(source),
        ]
        subprocess.run(cmd, check=True)
        done += 1
    log.info("%s: готово — нарезано %d, пропущено (уже были) %d.", country.iso, done, skipped)


COPY_CHUNK_SIZE = 64 * 1024 * 1024  # 64 МБ — шаг для лога прогресса копирования
COPY_LOG_EVERY_SECONDS = 5.0


def split_whole(country: common.Country, extracts_dir: Path) -> None:
    source = country.download_path
    if not source.exists():
        log.error(
            "%s: нет исходного файла %s — скачайте его вручную (%s)", country.iso, source, country.source,
        )
        return
    dest = extracts_dir / f"{country.iso}.osm.pbf"
    total = source.stat().st_size
    log.info(
        "%s: копирую %s -> %s (%.1f МБ, mode: whole, нарезка не нужна)",
        country.iso, source, dest, total / 1_048_576,
    )
    copied = 0
    last_log = time.monotonic()
    with source.open("rb") as src, dest.open("wb") as dst:
        while chunk := src.read(COPY_CHUNK_SIZE):
            dst.write(chunk)
            copied += len(chunk)
            now = time.monotonic()
            if now - last_log >= COPY_LOG_EVERY_SECONDS or copied == total:
                log.info("%s: скопировано %.1f%% (%.1f / %.1f МБ)", country.iso, 100 * copied / total, copied / 1_048_576, total / 1_048_576)
                last_log = now
    shutil.copystat(source, dest)


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
