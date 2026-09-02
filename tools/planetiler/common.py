"""Общие вещи для скриптов конвейера tools/planetiler/*.py.

Держит только то, что реально переиспользуется несколькими скриптами
(fetch_boundaries.py / build_pmtiles.py / cut_packs.py / build_index.py):
загрузку regions.yaml, пути каталогов конвейера, настройку логирования.
Общая схема конвейера — см. tools/planetiler/README.md.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import yaml

ROOT = Path(__file__).resolve().parent
REGIONS_YAML = ROOT / "regions.yaml"
DOWNLOADS_DIR = ROOT / "downloads"  # сырые .osm.pbf с Geofabrik — кладутся вручную, не скачиваются скриптами
POLYGONS_DIR = ROOT / "polygons"  # границы субъектов (.osm), см. fetch_boundaries.py
FULL_DIR = ROOT / "full"  # сборка целиком по стране: full/<iso>.pmtiles, из неё режутся паки
OUT_DIR = ROOT / "out"  # итоговые .pmtiles + index.json


@dataclass(frozen=True)
class Country:
    iso: str
    name: str
    enabled: bool
    mode: str  # "whole" | "subjects"
    source: Optional[str] = None
    subject_admin_level: Optional[int] = None
    note: Optional[str] = None


def load_countries(path: Path = REGIONS_YAML) -> list[Country]:
    """Читает regions.yaml целиком, включая enabled: false заготовки."""
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    result: list[Country] = []
    for raw in data.get("countries", []):
        result.append(
            Country(
                iso=raw["iso"],
                name=raw.get("name", raw["iso"]),
                enabled=bool(raw.get("enabled", False)),
                mode=raw.get("mode", "whole"),
                source=raw.get("source") or None,
                subject_admin_level=raw.get("subject_admin_level"),
                note=raw.get("note"),
            )
        )
    return result


def enabled_countries(path: Path = REGIONS_YAML) -> list[Country]:
    """Только страны с enabled: true — единственные, которые скрипты реально трогают."""
    return [c for c in load_countries(path) if c.enabled]


def filter_by_iso(countries: list[Country], only: Optional[str]) -> list[Country]:
    """--only ru,by -> оставить только перечисленные iso (для ручного прогона по одной стране)."""
    if not only:
        return countries
    wanted = {i.strip().lower() for i in only.split(",") if i.strip()}
    return [c for c in countries if c.iso in wanted]


def setup_logging(verbose: bool = False) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
