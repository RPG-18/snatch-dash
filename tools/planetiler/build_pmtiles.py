#!/usr/bin/env python3
"""downloads/<iso>-latest.osm.pbf -> full/<iso>.pmtiles через planetiler.

Одна сборка на страну целиком, без предварительной нарезки OSM. Паки субъектов
вырезаются из этого архива дальше — cut_packs.py.

Почему так, а не 84 отдельных сборки (замеры 2026-09-02, Россия, M3 Pro/18 ГБ):

  - быстрее в шесть раз: 8 мин 52 с против 55 (35 мин на 84 прогона planetiler
    плюс 20 мин `osmium extract`, которого теперь нет вовсе);
  - исчезает режим отказа, стоивший разбирательства 2026-08-31: кривая граница
    из Overpass теперь влияет только на то, какие тайлы скопированы в пак, а не
    на то, существуют ли данные вообще — ошибка видна сразу, а не запекается;
  - память не мешает: planetiler по умолчанию держит данные в mmap-файлах
    (`storage=mmap`, `nodemap_type=sparsearray`), в RAM у него 300 МБ индекса и
    на России, и на одном субъекте. Ограничение дисковое: ~20 ГБ временных
    плюс ~5 ГБ выхода на Россию, planetiler печатает свою оценку в начале лога.

Чем платим: флаги planetiler теперь общие на страну, задать их отдельному
субъекту нельзя — см. planetiler.yaml.

Требует переменную окружения PLANETILER_JAR — путь к planetiler.jar
(https://github.com/onthegomap/planetiler/releases). Память/потоки —
машино-зависимая настройка, задаётся окружением JAVA_TOOL_OPTIONS /
PLANETILER_THREADS (см. tools/README.md), в YAML не выносится.

На Python, а не shell — чтобы конвейер не зависел от bash/zsh и одинаково
работал под Windows/macOS/Linux (см. tools/planetiler/README.md).
"""
from __future__ import annotations

import argparse
import logging
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import common
import yaml

log = logging.getLogger("build_pmtiles")

PLANETILER_YAML = common.ROOT / "planetiler.yaml"


def load_tuning(path: Path) -> dict:
    """Читает planetiler.yaml; отсутствующий файл/секции — пустые словари."""
    if not path.exists():
        return {"defaults": {}, "countries": {}}
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return {
        "defaults": data.get("defaults") or {},
        "countries": data.get("countries") or {},
    }


def resolve_flags(tuning: dict, iso: str) -> dict:
    """Сливает defaults + countries[iso] на уровне отдельного флага.

    Переопределение заменяет значение целиком (список у страны не дополняет
    список defaults, а заменяет). Уровня субъекта здесь больше нет: сборка одна
    на страну, и разные флаги у соседних субъектов физически невыразимы.
    """
    merged = dict(tuning["defaults"])
    merged.update(tuning["countries"].get(iso, {}))
    return merged


def flag_to_arg(name: str, value: Any) -> str:
    """Значение из YAML -> флаг planetiler.

    Скаляр (int/float/str) -> --name=value;
    список -> --name=a,b,c;
    bool True -> --name (bare), False -> флаг не передаётся.
    """
    if isinstance(value, bool):
        return f"--{name}" if value else ""
    if isinstance(value, (list, tuple)):
        return f"--{name}=" + ",".join(str(v) for v in value)
    return f"--{name}={value}"


def flags_to_args(flags: dict) -> list[str]:
    args = []
    for name, value in flags.items():
        arg = flag_to_arg(name, value)
        if arg:
            args.append(arg)
    return args


def require_jar() -> str:
    jar = os.environ.get("PLANETILER_JAR")
    if not jar:
        log.error("PLANETILER_JAR не задан — укажите путь к planetiler.jar")
        log.error("  (скачать: https://github.com/onthegomap/planetiler/releases)")
        sys.exit(1)
    if not Path(jar).is_file():
        log.error("PLANETILER_JAR указывает на несуществующий файл: %s", jar)
        sys.exit(1)
    return jar


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tuning", type=Path, default=PLANETILER_YAML, help="путь к planetiler.yaml")
    parser.add_argument("--downloads-dir", type=Path, default=common.DOWNLOADS_DIR)
    parser.add_argument("--full-dir", type=Path, default=common.FULL_DIR, help="куда класть сборку по стране")
    parser.add_argument("--only", help="ограничиться странами по iso, через запятую")
    parser.add_argument("--dry-run", action="store_true", help="только напечатать команды planetiler")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    common.setup_logging(args.verbose)

    countries = common.filter_by_iso(common.enabled_countries(), args.only)
    if not countries:
        log.error("Нет стран с enabled: true (после фильтра --only) в regions.yaml")
        return 1

    jar = require_jar() if not args.dry_run else "<PLANETILER_JAR>"
    tuning = load_tuning(args.tuning)
    threads_arg = [f"--threads={os.environ['PLANETILER_THREADS']}"] if os.environ.get("PLANETILER_THREADS") else []

    args.full_dir.mkdir(parents=True, exist_ok=True)
    total = len(countries)
    for i, country in enumerate(countries, start=1):
        source = args.downloads_dir / f"{country.iso}-latest.osm.pbf"
        if not source.is_file():
            log.error(
                "%s: нет %s — скачать вручную по ссылке `source` из regions.yaml (см. README.md, шаг 0)",
                country.iso, source,
            )
            return 1
        output = args.full_dir / f"{country.iso}.pmtiles"
        cmd = [
            "java", "-jar", jar,
            f"--osm-path={source}",
            f"--output={output}",
            "--download",
            *flags_to_args(resolve_flags(tuning, country.iso)),
            *threads_arg,
        ]
        log.info("[%d/%d] %s: %s -> %s", i, total, country.iso, source.name, output)
        if args.dry_run:
            print(" ".join(cmd))
            continue
        subprocess.run(cmd, check=True)

    log.info("Готово: %d стран(ы) в %s. Дальше — cut_packs.py", total, args.full_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
