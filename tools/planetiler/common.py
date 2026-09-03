"""Общие вещи для скриптов конвейера tools/planetiler/*.py.

Держит только то, что реально переиспользуется несколькими скриптами
(fetch_boundaries.py / build_pmtiles.py / cut_packs.py / build_index.py):
загрузку regions.yaml, пути каталогов конвейера, состав паков (склейка и
исключения), настройку логирования.
Общая схема конвейера — см. tools/planetiler/README.md.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

import yaml

ROOT = Path(__file__).resolve().parent
REGIONS_YAML = ROOT / "regions.yaml"
DOWNLOADS_DIR = ROOT / "downloads"  # сырые .osm.pbf с Geofabrik — кладутся вручную, не скачиваются скриптами
POLYGONS_DIR = ROOT / "polygons"  # границы субъектов (.osm), см. fetch_boundaries.py
FULL_DIR = ROOT / "full"  # сборка целиком по стране: full/<iso>.pmtiles, из неё режутся паки
OUT_DIR = ROOT / "out"  # итоговые .pmtiles + index.json


@dataclass(frozen=True)
class MergeGroup:
    """Несколько субъектов, склеенных в один пак (см. regions.yaml, `merge`)."""

    code: str
    parts: tuple[str, ...]
    note: Optional[str] = None


@dataclass(frozen=True)
class Country:
    iso: str
    name: str
    enabled: bool
    mode: str  # "whole" | "subjects"
    source: Optional[str] = None
    subject_admin_level: Optional[int] = None
    merge: tuple[MergeGroup, ...] = ()
    exclude: tuple[str, ...] = ()
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
                merge=tuple(
                    MergeGroup(
                        code=g["code"],
                        parts=tuple(g.get("parts") or ()),
                        note=g.get("note"),
                    )
                    for g in (raw.get("merge") or [])
                ),
                exclude=tuple(raw.get("exclude") or ()),
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


def pack_plan(country: Country, subject_codes: Iterable[str]) -> dict[str, list[str]]:
    """<код пака> -> коды субъектов, из которых он собран.

    Единственное место, где из списка границ получается список паков: применяет
    `exclude` и `merge` из regions.yaml. Несклеенный субъект тоже попадает в
    результат — сам себе единственная часть, чтобы у вызывающего был один список
    паков, а не три разных случая. Порядок сохраняется по `subject_codes`:
    группа встаёт на место своей первой части.

    Конфликты конфига — ValueError, а не тихое игнорирование: молча выпавшая из
    группы часть даёт формально исправный пак без половины агломерации, а
    опечатка в `exclude` — субъект, который считали убранным, а он в корпусе.
    """
    known = list(dict.fromkeys(subject_codes))
    known_set = set(known)

    dropped = set()
    for code in country.exclude:
        if code not in known_set:
            raise ValueError(
                f"{country.iso}: в exclude код {code}, а границы {code} нет — "
                f"опечатка? проверьте regions.yaml и polygons/"
            )
        dropped.add(code)

    owner: dict[str, str] = {}  # часть -> код группы
    seen_codes: set[str] = set()

    for group in country.merge:
        if not group.parts:
            raise ValueError(f"{country.iso}: группа {group.code} без parts")
        if group.code in seen_codes:
            raise ValueError(f"{country.iso}: код группы {group.code} встречается дважды")
        seen_codes.add(group.code)
        if group.code in known_set and group.code not in group.parts:
            raise ValueError(
                f"{country.iso}: код группы {group.code} занят субъектом, которого нет в её parts"
            )
        for part in group.parts:
            if part in dropped:
                raise ValueError(
                    f"{country.iso}: субъект {part} и в exclude, и в группе {group.code}"
                )
            if part not in known_set:
                raise ValueError(
                    f"{country.iso}: в группе {group.code} часть {part}, "
                    f"а границы {part} нет — проверьте regions.yaml и fetch_boundaries.py"
                )
            if part in owner:
                raise ValueError(
                    f"{country.iso}: субъект {part} указан сразу в двух группах "
                    f"({owner[part]} и {group.code})"
                )
            owner[part] = group.code

    by_code = {g.code: list(g.parts) for g in country.merge}
    plan: dict[str, list[str]] = {}
    for code in known:
        if code in dropped:
            continue
        group_code = owner.get(code)
        if group_code is None:
            plan[code] = [code]
        elif group_code not in plan:
            plan[group_code] = by_code[group_code]
    return plan


def setup_logging(verbose: bool = False) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
