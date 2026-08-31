#!/usr/bin/env python3
"""extracts/*.osm.pbf -> out/<iso>/<код>.pmtiles через planetiler.

Для каждого .osm.pbf в extracts/ запускает planetiler.jar с флагами, собранными
из planetiler.yaml (приоритет на уровне флага: subjects > countries > defaults).
Структурные флаги (--osm-path, --output), --download и --threads в YAML не
выносятся — они не зависят от региона/железа, их знает только этот скрипт.

Требует переменную окружения PLANETILER_JAR — путь к planetiler.jar
(https://github.com/onthegomap/planetiler/releases). Память/потоки —
машино-зависимая настройка, задаётся окружением JAVA_TOOL_OPTIONS /
PLANETILER_THREADS (см. tools/README.md), в YAML не выносится.

На Python, а не shell — чтобы конвейер не зависел от bash/zsh и одинаково
работал под Windows/macOS/Linux, и чтобы читать planetiler.yaml (см.
tools/planetiler/README.md).
"""
from __future__ import annotations

import argparse
import logging
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Optional

import common
import yaml

log = logging.getLogger("build_pmtiles")

PLANETILER_YAML = common.ROOT / "planetiler.yaml"


def load_tuning(path: Path) -> dict:
    """Читает planetiler.yaml; отсутствующий файл/секции — пустые словари."""
    if not path.exists():
        return {"defaults": {}, "countries": {}, "subjects": {}}
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return {
        "defaults": data.get("defaults") or {},
        "countries": data.get("countries") or {},
        "subjects": data.get("subjects") or {},
    }


def resolve_flags(tuning: dict, code: str) -> dict:
    """Сливает defaults + countries[iso] + subjects[code] на уровне отдельного флага.

    iso = code.split("-")[0]; для mode: whole (by) code == iso, так что
    subjects-переопределение по iso тоже сработает. Переопределение заменяет
    значение целиком (список у субъекта не дополняет список страны, а заменяет).
    """
    iso = code.split("-")[0]
    merged = dict(tuning["defaults"])
    merged.update(tuning["countries"].get(iso, {}))
    merged.update(tuning["subjects"].get(code, {}))
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


def filter_files(files: list[Path], only: Optional[str]) -> list[Path]:
    if not only:
        return files
    wanted = {i.strip().lower() for i in only.split(",") if i.strip()}
    return [f for f in files if f.stem.split("-")[0] in wanted]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tuning", type=Path, default=PLANETILER_YAML, help="путь к planetiler.yaml")
    parser.add_argument("--extracts-dir", type=Path, default=common.EXTRACTS_DIR)
    parser.add_argument("--out-dir", type=Path, default=common.OUT_DIR)
    parser.add_argument("--only", help="ограничиться странами по iso, через запятую")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    common.setup_logging(args.verbose)

    jar = require_jar()
    tuning = load_tuning(args.tuning)

    files = filter_files(sorted(args.extracts_dir.glob("*.osm.pbf")), args.only)
    if not files:
        log.error("Нет .osm.pbf в %s — сначала запустить split.py", args.extracts_dir)
        return 1

    threads_arg = [f"--threads={os.environ['PLANETILER_THREADS']}"] if os.environ.get("PLANETILER_THREADS") else []

    total = len(files)
    for i, f in enumerate(files, start=1):
        code = f.name.removesuffix(".osm.pbf")  # ru-mow.osm.pbf -> ru-mow ; by.osm.pbf -> by (Path.stem снял бы только .pbf)
        iso = code.split("-")[0]  # ru-mow -> ru ; by -> by
        dest_dir = args.out_dir / iso
        dest_dir.mkdir(parents=True, exist_ok=True)
        output = dest_dir / f"{code}.pmtiles"
        cmd = ["java", "-jar", jar, f"--osm-path={f}", f"--output={output}", "--download", *flags_to_args(resolve_flags(tuning, code)), *threads_arg]
        log.info("[%d/%d] %s -> %s", i, total, code, output)
        subprocess.run(cmd, check=True)

    return 0


if __name__ == "__main__":
    sys.exit(main())
