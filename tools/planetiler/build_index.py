#!/usr/bin/env python3
"""Обходит out/, считает sha256+size по каждому .pmtiles и пишет out/index.json.

Формат index.json и его назначение (проверка целостности на телефоне после
закачки + дешёвая проверка `generated_at` перед этим) — см.
tools/planetiler/README.md, раздел "index.json".

Пишет атомарно: во временный файл рядом, затем os.replace — чтобы клиент не
мог скачать наполовину записанный index.json во время генерации.

Осиротевшие каталоги в out/ (страна пропала/переименовалась в regions.yaml)
намеренно не чистятся здесь — конвейер целиком пересобирается с нуля раз в
неделю (out/ удаляется перед пересборкой), отдельная логика чистки не нужна.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import common

log = logging.getLogger("build_index")

SCHEMA_VERSION = 1
CHUNK_SIZE = 1 << 20  # 1 МБ


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(CHUNK_SIZE), b""):
            h.update(chunk)
    return h.hexdigest()


def build_country_entry(country: common.Country, out_dir: Path) -> Optional[dict]:
    country_dir = out_dir / country.iso
    files = sorted(country_dir.glob("*.pmtiles")) if country_dir.exists() else []
    if not files:
        log.warning("%s: нет .pmtiles в %s — пропускаю в индексе (ещё не собран?)", country.iso, country_dir)
        return None

    regions = []
    total = len(files)
    for i, path in enumerate(files, start=1):
        size = path.stat().st_size
        log.info("%s: [%d/%d] sha256 %s (%.1f МБ)...", country.iso, i, total, path.name, size / 1_048_576)
        regions.append(
            {
                "code": path.stem,
                "path": f"{country.iso}/{path.name}",
                "size": size,
                "sha256": sha256_of(path),
            }
        )
    return {"iso": country.iso, "mode": country.mode, "regions": regions}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--regions", type=Path, default=common.REGIONS_YAML)
    parser.add_argument("--out-dir", type=Path, default=common.OUT_DIR)
    parser.add_argument("--only", help="ограничиться странами по iso, через запятую")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    common.setup_logging(args.verbose)

    countries = common.filter_by_iso(common.enabled_countries(args.regions), args.only)
    if not countries:
        log.info("Нет enabled-стран — index.json писать не из чего.")
        return 0

    country_entries = []
    for country in countries:
        entry = build_country_entry(country, args.out_dir)
        if entry:
            country_entries.append(entry)

    known_iso = {c.iso for c in common.load_countries(args.regions)}
    if args.out_dir.exists():
        for child in args.out_dir.iterdir():
            if child.is_dir() and child.name not in known_iso:
                log.warning("%s есть в out/, но отсутствует в regions.yaml (просто предупреждение)", child)

    file_count = sum(len(c["regions"]) for c in country_entries)
    total_size = sum(r["size"] for c in country_entries for r in c["regions"])

    index = {
        "version": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "file_count": file_count,
        "total_size": total_size,
        "countries": country_entries,
    }

    args.out_dir.mkdir(parents=True, exist_ok=True)
    final_path = args.out_dir / "index.json"
    tmp_path = final_path.with_name(final_path.name + ".tmp")
    tmp_path.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(tmp_path, final_path)  # атомарная замена — см. докстринг

    log.info("Готово: %s (%d файлов, %.2f ГБ)", final_path, file_count, total_size / 1_073_741_824)
    return 0


if __name__ == "__main__":
    sys.exit(main())
