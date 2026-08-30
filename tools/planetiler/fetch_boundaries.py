#!/usr/bin/env python3
"""Overpass API -> tools/planetiler/polygons/<code>.osm

Для каждой enabled-страны с mode: subjects тянет через Overpass релации
admin_level = subject_admin_level внутри страны и сохраняет для каждой полную
рекурсивную границу (узлы + пути + релации) как отдельный .osm-файл, названный
по тегу ISO3166-2 в нижнем регистре (RU-MOW -> polygons/ru-mow.osm).

Формат "osm" (не geojson/poly) выбран специально: `osmium extract` сам умеет
собирать (мульти)полигон границы из релации в таком файле — не нужно
самостоятельно собирать геометрию outer/inner колец на стороне Python,
достаточно скопировать closure релации как есть.

Тег ISO3166-2 на релации границы — не универсальная гарантия (у части
субъектов его может не быть или он неполный); там, где тега нет, скрипт
пропускает релацию с предупреждением — сопоставление вручную в эту версию
скрипта не входит.

См. tools/planetiler/plan.md за общей схемой конвейера.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

import common

log = logging.getLogger("fetch_boundaries")

DEFAULT_OVERPASS_URL = "https://overpass-api.de/api/interpreter"
REQUEST_RETRIES = 3
RETRY_DELAY_SECONDS = 5.0
POLITE_DELAY_SECONDS = 1.0  # пауза между запросами по отдельным релациям — не долбить публичный Overpass


def query_overpass(query: str, url: str, timeout: int) -> dict[str, Any]:
    data = urllib.parse.urlencode({"data": query}).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "snatch-dash-planetiler/1.0"})
    last_error: Exception | None = None
    for attempt in range(1, REQUEST_RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.load(resp)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            last_error = exc
            log.warning("Overpass запрос неудачен (попытка %d/%d): %s", attempt, REQUEST_RETRIES, exc)
            if attempt < REQUEST_RETRIES:
                time.sleep(RETRY_DELAY_SECONDS * attempt)
    assert last_error is not None
    raise last_error


def list_subject_relations(country: common.Country, overpass_url: str) -> list[dict[str, Any]]:
    """Лёгкий запрос: id + tags релаций admin_level=N внутри страны, без геометрии."""
    query = f"""
[out:json][timeout:180];
area["ISO3166-1:alpha2"="{country.iso.upper()}"]["admin_level"="2"]->.country;
relation(area.country)["admin_level"="{country.subject_admin_level}"]["boundary"="administrative"];
out tags;
""".strip()
    result = query_overpass(query, overpass_url, timeout=200)
    return result.get("elements", [])


def fetch_relation_closure(relation_id: int, overpass_url: str) -> dict[str, Any]:
    """Полный рекурсивный запрос: сама релация + все её члены (пути/узлы/вложенные релации)."""
    query = f"""
[out:json][timeout:180];
relation({relation_id});
(._;>>;);
out body;
""".strip()
    return query_overpass(query, overpass_url, timeout=200)


def build_osm_xml(elements: list[dict[str, Any]]) -> ET.ElementTree:
    """Overpass JSON elements -> минимальный валидный .osm (сначала nodes, потом ways, потом relations)."""
    root = ET.Element("osm", version="0.6", generator="fetch_boundaries.py")

    by_type: dict[str, list[dict[str, Any]]] = {"node": [], "way": [], "relation": []}
    for el in elements:
        by_type.setdefault(el["type"], []).append(el)

    for node in by_type["node"]:
        node_el = ET.SubElement(
            root, "node", {"id": str(node["id"]), "lat": str(node["lat"]), "lon": str(node["lon"])}
        )
        for k, v in node.get("tags", {}).items():
            ET.SubElement(node_el, "tag", {"k": k, "v": v})

    for way in by_type["way"]:
        way_el = ET.SubElement(root, "way", {"id": str(way["id"])})
        for ref in way.get("nodes", []):
            ET.SubElement(way_el, "nd", {"ref": str(ref)})
        for k, v in way.get("tags", {}).items():
            ET.SubElement(way_el, "tag", {"k": k, "v": v})

    for rel in by_type["relation"]:
        rel_el = ET.SubElement(root, "relation", {"id": str(rel["id"])})
        for member in rel.get("members", []):
            ET.SubElement(
                rel_el,
                "member",
                {"type": member["type"], "ref": str(member["ref"]), "role": member.get("role", "")},
            )
        for k, v in rel.get("tags", {}).items():
            ET.SubElement(rel_el, "tag", {"k": k, "v": v})

    return ET.ElementTree(root)


def process_country(country: common.Country, overpass_url: str, out_dir: Path, force: bool) -> tuple[int, int]:
    log.info(
        "Страна %s (%s): запрашиваю список субъектов (admin_level=%s)...",
        country.iso, country.name, country.subject_admin_level,
    )
    relations = list_subject_relations(country, overpass_url)
    log.info("Страна %s: найдено %d релаций admin_level=%s", country.iso, len(relations), country.subject_admin_level)

    written = 0
    skipped = 0
    out_dir.mkdir(parents=True, exist_ok=True)
    for rel in relations:
        tags = rel.get("tags", {})
        iso_code = tags.get("ISO3166-2", "").strip().lower()
        name = tags.get("name", tags.get("name:ru", "?"))
        if not iso_code:
            log.warning("Пропускаю релацию %s (%s) — нет тега ISO3166-2, сопоставить вручную", rel["id"], name)
            skipped += 1
            continue

        out_path = out_dir / f"{iso_code}.osm"
        if out_path.exists() and not force:
            log.debug("%s уже существует, пропускаю (--force для перекачки)", out_path.name)
            continue

        log.info("Тяну границу %s (%s, релация %s)...", iso_code, name, rel["id"])
        closure = fetch_relation_closure(rel["id"], overpass_url)
        elements = closure.get("elements", [])
        if not elements:
            log.warning("Пустой ответ Overpass для релации %s (%s), пропускаю", rel["id"], name)
            skipped += 1
            continue

        tree = build_osm_xml(elements)
        tree.write(out_path, encoding="UTF-8", xml_declaration=True)
        written += 1
        time.sleep(POLITE_DELAY_SECONDS)

    return written, skipped


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--regions", type=Path, default=common.REGIONS_YAML, help="путь к regions.yaml")
    parser.add_argument("--out-dir", type=Path, default=common.POLYGONS_DIR, help="куда писать <code>.osm")
    parser.add_argument("--overpass-url", default=DEFAULT_OVERPASS_URL, help="Overpass API endpoint")
    parser.add_argument("--only", help="ограничиться странами по iso, через запятую (например: ru)")
    parser.add_argument("--force", action="store_true", help="перекачать границу, даже если файл уже есть")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    common.setup_logging(args.verbose)

    countries = [c for c in common.filter_by_iso(common.enabled_countries(args.regions), args.only) if c.mode == "subjects"]
    if not countries:
        log.info("Нет enabled-стран с mode: subjects — делать нечего.")
        return 0

    total_written = total_skipped = 0
    for country in countries:
        written, skipped = process_country(country, args.overpass_url, args.out_dir, args.force)
        total_written += written
        total_skipped += skipped

    log.info("Готово: записано %d границ, пропущено %d.", total_written, total_skipped)
    return 0


if __name__ == "__main__":
    sys.exit(main())
