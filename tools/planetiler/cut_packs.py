#!/usr/bin/env python3
"""full/<iso>.pmtiles -> out/<iso>/<код>.pmtiles: вырезает пак каждого субъекта.

`pmtiles extract --region <geojson>` копирует из большого архива тайлы,
пересекающие полигон, не трогая их содержимое: внутренность пака бит-в-бит
совпадает с тем, что напёк planetiler. Полная под-пирамида от z0 до maxzoom —
дешёвая операция (весь корпус 2026-09-02: 82 выреза за 46 с).

Границы берутся из polygons/<код>.osm (fetch_boundaries.py) и переводятся в
GeoJSON через `osmium export`. Готовый полигон, отданный extract, остаётся
рядом как polygons/<код>.region.geojson — по нему видно, что именно вырезали.

**Буфер обязателен, значение по умолчанию 2 км.** Резать ровно по границе
нельзя: тайл кончается на границе субъекта, и райдер с одним скачанным паком
теряет карту сразу за ней. Замер по `ru-spe` (features в тайле z14):

    точка                  нынешний пак   без буфера   2 км   5 км
    Мурино, ~1 км за КАД             41    нет тайла   1806   1806
    Всеволожск, ~5 км                 1    нет тайла    нет     622

То есть без буфера в Мурино не остаётся даже обрывков, которые есть сейчас.
Цена буфера по корпусу: 2 км +3.4%, 5 км +8.1%; платят маленькие субъекты с
длинной границей (`ru-ad` +66%, `ru-mow` +40%), дальние большие — почти ноль
(`ru-sa` +0.8%).

mode: whole — резать нечего, сборка страны и есть пак: full/<iso>.pmtiles
копируется в out/<iso>/<iso>.pmtiles.

На Python, а не shell — чтобы конвейер не зависел от bash/zsh и одинаково
работал под Windows/macOS/Linux (см. tools/planetiler/README.md).
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Optional

import common

try:
    from shapely.geometry import MultiPolygon, box, mapping, shape
    from shapely.ops import transform, unary_union
except ImportError as exc:  # проверяется в main(), чтобы --help работал и без зависимостей
    SHAPELY_IMPORT_ERROR: Optional[ImportError] = exc
else:
    SHAPELY_IMPORT_ERROR = None

log = logging.getLogger("cut_packs")

# Ширина bbox, при которой считаем, что граница пересекает 180-й меридиан. Мир
# в градусах долготы — 360; граница, растянутая почти во всю ширину, на самом
# деле «заворачивается» через ±180 (Чукотка: 157°..180° и -180°..-169°).
# Обычный субъект РФ шире ~120° не бывает, порог 350 отделяет надёжно.
ANTIMERIDIAN_WIDTH = 350.0

# Один градус широты в километрах. Для буфера в километрах этого хватает:
# долгота домножается на cos(широты), ошибка проекции на масштабе субъекта
# меньше, чем разница между 2 и 5 км, которую мы всё равно выбираем на глаз.
KM_PER_DEGREE = 111.32

WORLD = box(-180.0, -90.0, 180.0, 90.0) if SHAPELY_IMPORT_ERROR is None else None


def require_binary(name: str, hint: str) -> str:
    path = shutil.which(name)
    if not path:
        log.error("Бинарник `%s` не найден в PATH — %s", name, hint)
        sys.exit(1)
    return path


def export_polygon(osmium_bin: str, polygon_osm: Path, dest: Path) -> Any:
    """polygons/<код>.osm -> геометрия границы (Polygon/MultiPolygon).

    osmium сам собирает площадь из релации границы; --geometry-types=polygon
    отсекает точки и линии, которые в файле тоже есть (сама релация тянет за
    собой ноды и вэи).
    """
    subprocess.run(
        [osmium_bin, "export", str(polygon_osm), "-f", "geojson",
         "--geometry-types=polygon", "-o", str(dest), "--overwrite"],
        check=True, capture_output=True, text=True,
    )
    data = json.loads(dest.read_text(encoding="utf-8"))
    geoms = [shape(f["geometry"]) for f in data.get("features", []) if f.get("geometry")]
    if not geoms:
        raise ValueError(f"{polygon_osm}: osmium export не дал ни одного полигона")
    return unary_union(geoms)


def split_at_antimeridian(geom: Any) -> list[tuple[str, Any]]:
    """Граница через ±180 -> два куска: западный и восточный.

    osmium export уже режет геометрию по антимеридиану и отдаёт куски по обе
    стороны, поэтому достаточно разложить их по знаку долготы. Суффикс `-east`
    у восточного куска — та же схема имён, что была до перехода на вырезы
    (ru-chu + ru-chu-east), клиент про неё знает.
    """
    minx, _, maxx, _ = geom.bounds
    if (maxx - minx) <= ANTIMERIDIAN_WIDTH:
        return [("", geom)]

    west, east = [], []
    for poly in (geom.geoms if isinstance(geom, MultiPolygon) else [geom]):
        # ±180 сами по себе знака не дают — считаем по остальным вершинам кольца
        xs = [x for x, _ in poly.exterior.coords if abs(abs(x) - 180.0) > 1e-9]
        (east if sum(1 for x in xs if x < 0) > len(xs) / 2 else west).append(poly)
    if not west or not east:
        return [("", geom)]
    return [("", unary_union(west)), ("-east", unary_union(east))]


def buffer_km(geom: Any, km: float) -> Any:
    """Буфер вокруг границы в километрах.

    Считается в «локальных» градусах: долгота сжимается на cos(широты) центра,
    буферизуется, разжимается обратно. Результат подрезается миром — у
    антимеридианного куска буфер иначе уехал бы за ±180, где тайлов нет (и где
    лежит уже соседний пак этого же субъекта).
    """
    if km <= 0:
        return geom
    kx = math.cos(math.radians(geom.centroid.y)) or 1.0
    scaled = transform(lambda x, y: (x * kx, y), geom)
    grown = scaled.buffer(km / KM_PER_DEGREE, quad_segs=4, join_style="mitre")
    return transform(lambda x, y: (x / kx, y), grown).intersection(WORLD)


def write_region(geom: Any, dest: Path) -> None:
    dest.write_text(json.dumps({
        "type": "FeatureCollection",
        "features": [{"type": "Feature", "properties": {}, "geometry": mapping(geom)}],
    }), encoding="utf-8")


def cut_subjects(country: common.Country, full: Path, out_dir: Path, polygons_dir: Path,
                 osmium_bin: str, pmtiles_bin: str, km: float) -> int:
    polygon_files = sorted(polygons_dir.glob(f"{country.iso}-*.osm"))
    if not polygon_files:
        log.error("%s: нет границ %s-*.osm в %s — сначала fetch_boundaries.py",
                  country.iso, country.iso, polygons_dir)
        return 1

    dest_dir = out_dir / country.iso
    dest_dir.mkdir(parents=True, exist_ok=True)
    total = len(polygon_files)
    made = 0
    for i, polygon_osm in enumerate(polygon_files, start=1):
        code = polygon_osm.name.removesuffix(".osm")  # ru-mow.osm -> ru-mow
        geom = export_polygon(osmium_bin, polygon_osm, polygons_dir / f"{code}.geojson")
        for suffix, part in split_at_antimeridian(geom):
            name = f"{code}{suffix}"
            region = polygons_dir / f"{name}.region.geojson"
            write_region(buffer_km(part, km), region)
            output = dest_dir / f"{name}.pmtiles"
            log.info("[%d/%d] %s -> %s", i, total, name, output)
            subprocess.run(
                [pmtiles_bin, "extract", str(full), str(output), f"--region={region}", "-q"],
                check=True,
            )
            made += 1
    log.info("%s: готово %d паков в %s", country.iso, made, dest_dir)
    return 0


def cut_whole(country: common.Country, full: Path, out_dir: Path) -> int:
    """mode: whole — резать нечего, Geofabrik уже обрезал страну по границе."""
    dest_dir = out_dir / country.iso
    dest_dir.mkdir(parents=True, exist_ok=True)
    output = dest_dir / f"{country.iso}.pmtiles"
    log.info("%s: mode whole — копирую %s -> %s", country.iso, full, output)
    shutil.copyfile(full, output)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--full-dir", type=Path, default=common.FULL_DIR)
    parser.add_argument("--out-dir", type=Path, default=common.OUT_DIR)
    parser.add_argument("--polygons-dir", type=Path, default=common.POLYGONS_DIR)
    parser.add_argument("--buffer-km", type=float, default=2.0,
                        help="перекрытие с соседями, км (0 — резать ровно по границе, не рекомендуется)")
    parser.add_argument("--only", help="ограничиться странами по iso, через запятую")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    common.setup_logging(args.verbose)

    if SHAPELY_IMPORT_ERROR is not None:
        log.error("Нет пакета shapely (%s) — поставьте зависимости: pip install -r requirements.txt",
                  SHAPELY_IMPORT_ERROR)
        return 1

    countries = common.filter_by_iso(common.enabled_countries(), args.only)
    if not countries:
        log.error("Нет стран с enabled: true (после фильтра --only) в regions.yaml")
        return 1

    osmium_bin = require_binary("osmium", "установите osmium-tool (см. tools/README.md)")
    pmtiles_bin = require_binary("pmtiles", "установите pmtiles CLI (см. tools/README.md)")
    if args.buffer_km <= 0:
        log.warning("--buffer-km=%s: паки будут обрезаны ровно по границе, и карта у соседа "
                    "оборвётся на ней же — см. докстринг", args.buffer_km)

    for country in countries:
        full = args.full_dir / f"{country.iso}.pmtiles"
        if not full.is_file():
            log.error("%s: нет %s — сначала build_pmtiles.py --only %s", country.iso, full, country.iso)
            return 1
        if country.mode == "whole":
            rc = cut_whole(country, full, args.out_dir)
        else:
            rc = cut_subjects(country, full, args.out_dir, args.polygons_dir,
                              osmium_bin, pmtiles_bin, args.buffer_km)
        if rc:
            return rc

    log.info("Дальше — build_index.py и validate_packs.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
