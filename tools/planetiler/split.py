#!/usr/bin/env python3
"""Режет скачанные .osm.pbf на extracts/*.osm.pbf.

mode: subjects — по одному вызову `osmium extract --strategy=smart -p <полигон>`
                 на каждый субъект из конфига, собранного build_extract_config.py
                 (конфиг читается как список экстрактов, а не передаётся в -c —
                 почему именно так, см. докстринг split_subjects).
mode: whole     — нарезка не нужна вообще, скачанный файл копируется как есть
                 в extracts/<iso>.osm.pbf — Geofabrik уже обрезал его по стране.

Ожидает исходные .osm.pbf в downloads/<iso>-latest.osm.pbf — они не
скачиваются автоматически (см. README.md: `source` в regions.yaml — просто
ссылка, куда сходить руками, а не URL для авто-загрузки).

На Python, а не shell — чтобы конвейер не зависел от bash/zsh и одинаково
работал под Windows/macOS/Linux (см. tools/planetiler/README.md).
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


# Ширина bbox, при которой считаем, что полигон/экстракт пересекает 180-й меридиан.
# Мир в градусах долготы — 360; граница субъекта, которая тянется почти во всю
# ширину (max_lon - min_lon > 350), на самом деле «заворачивается» через ±180
# (например, Чукотка: 157°..180° и -180°..-169°). Обычный субъект РФ шире ~120°
# не бывает, так что порог 350 надёжно отделяет антимеридиан от «просто широкого».
ANTIMERIDIAN_WIDTH = 350.0


def get_bbox(osmium_bin: str, path: Path) -> tuple[float, float, float, float]:
    """bbox файла через `osmium fileinfo -e -g data.bbox` -> (min_lon, min_lat, max_lon, max_lat)."""
    out = subprocess.run(
        [osmium_bin, "fileinfo", "-e", "-g", "data.bbox", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    # формат вывода: "(-180,59.3614294,180,77.112826)"
    nums = out.strip("()").split(",")
    return (float(nums[0]), float(nums[1]), float(nums[2]), float(nums[3]))


def crosses_antimeridian(bbox: tuple[float, float, float, float]) -> bool:
    return (bbox[2] - bbox[0]) > ANTIMERIDIAN_WIDTH


def split_subjects(country: common.Country, extracts_dir: Path, osmium_bin: str, strategy: str) -> None:
    """Нарезает на субъекты по одному вызову `osmium extract -p` на каждый.

    НЕ одним `osmium extract -c` со всеми экстрактами сразу: память osmium extract
    растёт как `число_экстрактов × (макс_ид_ноды / 8)` (bitset по глобальным OSM-ид),
    и на России (83 субъекта, иды нод до ~12 млрд) это ~120+ ГБ — OOM на любой
    обычной машине (см. MEMORY USAGE в `osmium help extract`). По одному экстракту
    на вызов — это `1 × (макс_ид/8) ≈ 1.5 ГБ`, но исходник перечитывается N раз
    (медленнее, зато работает). Возобновляемо: уже готовые `<output>` пропускаются.

    Полигон, пересекающий 180-й меридиан (Чукотка), режется на две части по 0°
    долготы — `<code>.osm.pbf` (запад) и `<code>-east.osm.pbf` (восток), иначе
    planetiler увидел бы bbox шириной во весь мир и сгенерировал бы океанские тайлы
    через все 360° долготы.
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

        # Полигон, пересекающий 180-й меридиан (Чукотка), разрезается на две части:
        # `<code>.osm.pbf` (запад, lon >= 0) и `<code>-east.osm.pbf` (восток, lon <= 0).
        # Без разреза planetiler увидел бы bbox шириной во весь мир (-180..180) и
        # сгенерировал бы тайлы через все 360° долготы — в основном пустой океан.
        crosses = crosses_antimeridian(get_bbox(osmium_bin, polygon))
        base = output.name.removesuffix(".osm.pbf")  # не Path.stem: тот снимет только .pbf и даст "ru-chu.osm"
        east_output = output.with_name(f"{base}-east.osm.pbf")

        expected = [output] + ([east_output] if crosses else [])
        if all(p.exists() and p.stat().st_size > 0 for p in expected):
            names = " + ".join(p.name for p in expected)
            log.info("[%d/%d] %s уже существует, пропускаю", i, total, names)
            skipped += 1
            continue

        if not crosses:
            log.info("[%d/%d] %s <- %s", i, total, output.name, polygon.name)
            cmd = [
                osmium_bin, "extract", f"--strategy={strategy}", "--progress", "--overwrite",
                "-p", str(polygon), "-o", str(output), str(source),
            ]
            subprocess.run(cmd, check=True)
        else:
            log.info(
                "[%d/%d] %s <- %s (пересекает 180-й меридиан, разрезаю на запад/восток)",
                i, total, output.name, polygon.name,
            )
            tmp = output.with_name(f"{output.stem}.full{output.suffix}")
            subprocess.run([
                osmium_bin, "extract", f"--strategy={strategy}", "--progress", "--overwrite",
                "-p", str(polygon), "-o", str(tmp), str(source),
            ], check=True)
            # Разрез по Гринвичу (0°): у антимеридианного субъекта данные только у ±180,
            # поэтому 0° всегда лежит в «пустом» зазоре между западной и восточной частями.
            # complete_ways, а не smart: smart дотащил бы целиком реляции, пересекающие
            # антимеридиан, и снова раздул бы bbox до полной ширины мира.
            for split_lon_range, dest in (("0,-90,180,90", output), ("-180,-90,0,90", east_output)):
                subprocess.run([
                    osmium_bin, "extract", "--strategy=complete_ways", "--overwrite",
                    "-b", split_lon_range, "-o", str(dest), str(tmp),
                ], check=True)
            tmp.unlink(missing_ok=True)
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
