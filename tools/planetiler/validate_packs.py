#!/usr/bin/env python3
"""Проверка собранных .pmtiles: не потерял ли пак часть субъекта.

Конвейер умеет собрать формально исправный, но фактически дырявый пак: режем по
кривому полигону, planetiler честно печёт тайлы из того, что ему дали, а
index.json показывает валидный sha256 файла, в котором нет половины области.
Снаружи это выглядит просто как «пак подозрительно маленький», и то если знать,
каким он должен быть. Так из сборки 2026-08-31 выпали шесть субъектов — ru-kgn
(Кургана нет вообще), ru-mag, ru-ngr, ru-psk, ru-sak, ru-yan; причина — вложенные
subarea-релации в файле полигона, см. fetch_boundaries.build_osm_xml.

Проверяется:

  1. **Структура.** PMTiles v3, тип тайлов MVT, minzoom и maxzoom совпадают с
     planetiler.yaml (иначе рендер будет оверзумить не оттуда, откуда ждёт, а
     ниже minzoom покажет пустой экран).
  2. **Слои.** Набор слоёв OpenMapTiles, который есть у любого субъекта РФ
     (REQUIRED_LAYERS), минус явно отключённое через exclude_layers. Порог
     бинарный: слой либо есть, либо нет, поэтому проверка ловит только полную
     потерю. В сборке 2026-08-31 все 84 пака отчитались OK, хотя ru-bel содержал
     9% нод области, ru-lip и ru-kgd — 14%, ru-cu — 18%.
  3. **Города (check_cities).** «У каждого НП внутри границы есть дороги на
     maxzoom» — то, чего не видит проверка по слоям: она смотрит на весь пак
     целиком, а эта — на каждую точку в отдельности, и порога тоже не требует.
     Проверено на заведомо битом паке: `ru-len`, обрезанный до восточной
     половины области, даёт «21 НП без дорог», здоровый — тишину.
  4. **Комплектность.** Ожидаемый набор паков считает common.pack_plan по
     границам polygons/<код>.osm и regions.yaml: склеенная группа (`merge`) —
     один пак на всю группу, исключённый субъект (`exclude`) — ни одного.
     Ловит регион, молча выпавший из сборки: fetch_boundaries.py пропускает
     субъект, если Overpass так и не отдал целый ответ.

**Чего проверка по городам не видит.** Точки НП она берёт из самого пака, так
что аккуратно вырезанный кусок, где пропали и дороги, и подписи, останется
незамеченным. Ловится ровно рассинхрон: обзорные тайлы (z11) шире subject-а и
переживают обрезку, унося подписи НП, у которых детальных тайлов уже нет. На том
же битом `ru-len` так поймалось 21 НП из примерно 250 потерянных — для сигнала
«пак дырявый» этого достаточно, для оценки масштаба потери — нет.

Читает и локальный out/, и уже опубликованный бакет (--base-url) — во втором
случае через HTTP range-запросы, паки целиком не качаются.

    python3 validate_packs.py                      # локальный out/, с проверкой по городам
    python3 validate_packs.py --only ru --verbose
    python3 validate_packs.py --cities off         # только структура/слои/комплектность
    python3 validate_packs.py --base-url https://storage.yandexcloud.net/snatch-dash-maps/

Времена (корпус 80 паков, M3 Pro): структура и слои — секунды, с проверкой по
городам — 1 мин 20 с. По HTTP проверка по городам выключена по умолчанию: это
сотни range-запросов на пак вместо двух.

Ненулевой код возврата = хотя бы один пак не прошёл, можно вешать в CI после
сборки. См. tools/planetiler/README.md.

**Что пробовали и что не работает** (чтобы не изобретать заново): доля случайных
точек внутри границы, для которых есть тайл на maxzoom — не различает сломанный
пак и разреженный. Замер по сборке 2026-08-31: сломанная Курганская область 9%,
здоровая соседняя Тюменская 8% — planetiler не печёт тайл там, где в OSM просто
ничего нет, и в тайге таких тайлов большинство. Отношение числа тайлов z14 к
числу z10 (плотность, не зависящая от площади) тоже не разделяет: 28 у
Курганской против 37 у худшей здоровой — разрыв слишком мал для порога (и z10 в
паках больше нет, с minzoom: 11 пришлось бы считать z14/z11).
"""
from __future__ import annotations

import argparse
import gzip
import json
import logging
import math
import struct
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

import common
import yaml

log = logging.getLogger("validate_packs")

PLANETILER_YAML = common.ROOT / "planetiler.yaml"

# Слои, которые в схеме OpenMapTiles есть у любого субъекта РФ. Проверено по
# сборке 2026-08-31: набор целиком присутствует в 78 паках и отсутствует ровно у
# шести выеденных подчистую (часть из этих 78 при этом урезана — по набору слоёв
# такое не видно, см. докстринг модуля). Сюда намеренно НЕ входят park,
# mountain_peak, aeroway, aerodrome_label и water_name — они законно бывают
# пустыми (нет гор/аэродромов/именованных водоёмов), и требовать их значило бы
# ловить ложные срабатывания.
REQUIRED_LAYERS = frozenset({
    "boundary", "building", "housenumber", "landcover", "landuse", "place",
    "poi", "transportation", "transportation_name", "water", "waterway",
})


# ── Чтение PMTiles v3 ────────────────────────────────────────────────────────
# Формат: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
# Свой минимальный ридер, а не зависимость: нужны только заголовок и метаданные,
# тела тайлов не читаются вовсе.

def read_varint(buf: bytes, i: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = buf[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


class LocalSource:
    def __init__(self, path: Path) -> None:
        self.name = str(path)
        self._f = path.open("rb")

    def read(self, offset: int, length: int) -> bytes:
        self._f.seek(offset)
        return self._f.read(length)

    def close(self) -> None:
        self._f.close()


class HttpSource:
    """Тот же интерфейс, но кусками по HTTP Range — чтобы проверять опубликованное, не скачивая."""

    # Бакет изредка рвёт TLS-хендшейк при частых новых соединениях — на прогоне
    # в 80 паков это поймаешь почти наверняка, поэтому ретраи здесь, а не
    # «перезапустите проверку».
    RETRIES = 4
    RETRY_DELAY_SECONDS = 1.5

    def __init__(self, url: str) -> None:
        self.name = url

    def read(self, offset: int, length: int) -> bytes:
        req = urllib.request.Request(self.name, headers={"Range": f"bytes={offset}-{offset + length - 1}"})
        last_error: Exception | None = None
        for attempt in range(1, self.RETRIES + 1):
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    # 200 вместо 206 означает, что сервер проигнорировал Range и
                    # отдаёт файл целиком — до 356 МБ в память ради заголовка в
                    # несколько килобайт. Ошибка, а не «медленно»: проверять пак
                    # по такому источнику всё равно нельзя.
                    if resp.status != 206:
                        raise RuntimeError(
                            f"сервер не поддержал Range (HTTP {resp.status} вместо 206) — "
                            f"проверка по HTTP невозможна: {self.name}"
                        )
                    return resp.read()
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                last_error = exc
                log.debug("range %d+%d не удался (попытка %d/%d): %s", offset, length, attempt, self.RETRIES, exc)
                if attempt < self.RETRIES:
                    time.sleep(self.RETRY_DELAY_SECONDS * attempt)
        assert last_error is not None
        raise last_error

    def close(self) -> None:
        pass


class Pmtiles:
    def __init__(self, source) -> None:
        self.src = source
        header = source.read(0, 127)
        if len(header) < 127 or header[:7] != b"PMTiles" or header[7] != 3:
            raise ValueError("не PMTiles v3 (сигнатура/версия не совпали)")
        (self.root_offset, self.root_length, self.meta_offset, self.meta_length,
         self.leaf_offset, self.leaf_length, self.data_offset, self.data_length,
         self.addressed_tiles, self.tile_entries, self.tile_contents) = struct.unpack_from("<11Q", header, 8)
        (self.clustered, self.internal_compression, self.tile_compression,
         self.tile_type, self.min_zoom, self.max_zoom) = struct.unpack_from("<6B", header, 96)
        self._dir_cache: dict[tuple[int, int], list[tuple[int, int, int, int]]] = {}

    def metadata(self) -> dict:
        raw = self.src.read(self.meta_offset, self.meta_length)
        if self.internal_compression == 2:
            raw = gzip.decompress(raw)
        return json.loads(raw)

    def layers(self) -> set[str]:
        return {layer["id"] for layer in self.metadata().get("vector_layers", [])}

    # ── тела тайлов ──────────────────────────────────────────────────────────
    # Директория PMTiles v3 — четыре varint-массива подряд: id (дельтами),
    # run_length, длина, смещение (0 = «сразу за предыдущим»). run_length == 0
    # означает не тайл, а ссылку на leaf-директорию.

    def _directory(self, offset: int, length: int) -> list[tuple[int, int, int, int]]:
        cached = self._dir_cache.get((offset, length))
        if cached is not None:
            return cached
        raw = self.src.read(offset, length)
        if self.internal_compression == 2:
            raw = gzip.decompress(raw)
        i = 0
        count, i = read_varint(raw, i)
        ids, last = [0] * count, 0
        for k in range(count):
            delta, i = read_varint(raw, i)
            last += delta
            ids[k] = last
        runs = [0] * count
        for k in range(count):
            runs[k], i = read_varint(raw, i)
        lengths = [0] * count
        for k in range(count):
            lengths[k], i = read_varint(raw, i)
        offsets = [0] * count
        for k in range(count):
            value, i = read_varint(raw, i)
            offsets[k] = offsets[k - 1] + lengths[k - 1] if value == 0 and k > 0 else value - 1
        entries = list(zip(ids, runs, lengths, offsets))
        self._dir_cache[(offset, length)] = entries
        return entries

    def iter_tiles(self, zoom: int):
        """-> ((z, x, y), (offset, length)) по всем тайлам зума. Обходит и leaf-директории.

        Отдаёт ссылку на тело, а не тело: PMTiles дедуплицирует одинаковые тайлы
        (океан — один блоб на тысячи адресов), и разжимать их по разу на адрес
        значит делать ту же работу многократно.
        """
        for tid, run, length, offset in self._directory(self.root_offset, self.root_length):
            if run == 0:
                leaves = self._directory(self.leaf_offset + offset, length)
            else:
                leaves = [(tid, run, length, offset)]
            for leaf_id, leaf_run, leaf_len, leaf_off in leaves:
                for step in range(max(leaf_run, 1)):
                    z, x, y = tileid_to_zxy(leaf_id + step)
                    if z == zoom:
                        yield (z, x, y), (leaf_off, leaf_len)

    def tile(self, z: int, x: int, y: int) -> Optional[bytes]:
        """Точечный поиск: root -> leaf, без обхода всего архива."""
        tid = zxy_to_tileid(z, x, y)
        offset, length = self.root_offset, self.root_length
        for _ in range(4):
            entries = self._directory(offset, length)
            found = None
            lo, hi = 0, len(entries) - 1
            while lo <= hi:
                mid = (lo + hi) // 2
                if entries[mid][0] <= tid:
                    found, lo = entries[mid], mid + 1
                else:
                    hi = mid - 1
            if found is None:
                return None
            entry_id, run, entry_len, entry_off = found
            if run == 0:
                offset, length = self.leaf_offset + entry_off, entry_len
                continue
            return self.body(entry_off, entry_len) if tid < entry_id + run else None
        return None

    def body(self, offset: int, length: int) -> bytes:
        raw = self.src.read(self.data_offset + offset, length)
        return gzip.decompress(raw) if self.tile_compression == 2 else raw


# ── Тайлы: обход директорий, разбор MVT, точка-в-полигоне ────────────────────
# Нужно только проверке по городам (check_cities). Всё минимальное: из MVT
# читаются имена слоёв, точки слоя place и «есть ли хоть одна фича» — полного
# разбора геометрии здесь нет и не нужно.

MVT_EXTENT_DEFAULT = 4096
PLACE_CLASSES = frozenset({"city", "town", "village"})


def tileid_to_zxy(tid: int) -> tuple[int, int, int]:
    """Hilbert tile id -> (z, x, y). Раскладка PMTiles v3."""
    acc, z = 0, 0
    while True:
        num = 1 << (2 * z)
        if acc + num > tid:
            break
        acc += num
        z += 1
    pos, n = tid - acc, 1 << z
    x = y = 0
    s = 1
    while s < n:
        rx = 1 & (pos // 2)
        ry = 1 & (pos ^ rx)
        if ry == 0:
            if rx == 1:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
        x += s * rx
        y += s * ry
        pos //= 4
        s *= 2
    return z, x, y


def zxy_to_tileid(z: int, x: int, y: int) -> int:
    acc = sum(1 << (2 * i) for i in range(z))
    n, d, s = 1 << z, 0, (1 << z) // 2
    while s > 0:
        rx = 1 if x & s else 0
        ry = 1 if y & s else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
        s //= 2
    return acc + d


def lonlat_to_tile(lon: float, lat: float, z: int) -> tuple[int, int]:
    n = 1 << z
    rad = math.radians(lat)
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.log(math.tan(rad) + 1 / math.cos(rad)) / math.pi) / 2.0 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


def tile_pixel_to_lonlat(z: int, x: int, y: int, px: int, py: int, extent: int) -> tuple[float, float]:
    n = 1 << z
    wx = (x + px / extent) / n
    wy = (y + py / extent) / n
    lon = wx * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * wy))))
    return lon, lat


def _pb_key(buf: bytes, i: int) -> tuple[int, int, int]:
    v, i = read_varint(buf, i)
    return v >> 3, v & 7, i


def _pb_skip(buf: bytes, i: int, wire: int) -> int:
    if wire == 0:
        return read_varint(buf, i)[1]
    if wire == 2:
        ln, i = read_varint(buf, i)
        return i + ln
    if wire == 5:
        return i + 4
    if wire == 1:
        return i + 8
    raise ValueError(f"неизвестный wire type {wire}")


def _pb_layers(buf: bytes):
    """-> итератор (имя слоя, тело слоя) по полю 3 тайла."""
    i = 0
    while i < len(buf):
        field, wire, i = _pb_key(buf, i)
        if field != 3 or wire != 2:
            i = _pb_skip(buf, i, wire)
            continue
        ln, i = read_varint(buf, i)
        body, i = buf[i:i + ln], i + ln
        name, j = None, 0
        while j < len(body):
            f, w, j = _pb_key(body, j)
            if f == 1 and w == 2:
                sl, j = read_varint(body, j)
                name, j = body[j:j + sl].decode("utf-8", "replace"), j + sl
                break
            j = _pb_skip(body, j, w)
        yield name, body


def mvt_layer_has_features(buf: bytes, layer: str) -> bool:
    for name, body in _pb_layers(buf):
        if name != layer:
            continue
        j = 0
        while j < len(body):
            f, w, j = _pb_key(body, j)
            if f == 2:  # feature
                return True
            j = _pb_skip(body, j, w)
    return False


def mvt_place_points(buf: bytes) -> list[tuple[str, int, int, int]]:
    """Точки слоя place класса city/town/village -> [(имя, px, py, extent)].

    Координаты — внутри тайла: тело тайла может быть общим для нескольких
    адресов, так что переводить в градусы должен вызывающий, зная (z, x, y).
    """
    out: list[tuple[str, int, int, int]] = []
    for name, body in _pb_layers(buf):
        if name != "place":
            continue
        keys: list[str] = []
        vals: list[object] = []
        feats: list[tuple[list[int], list[int]]] = []
        extent = MVT_EXTENT_DEFAULT
        j = 0
        while j < len(body):
            f, w, j = _pb_key(body, j)
            if f == 3 and w == 2:      # keys
                sl, j = read_varint(body, j)
                keys.append(body[j:j + sl].decode("utf-8", "replace"))
                j += sl
            elif f == 4 and w == 2:    # values
                sl, j = read_varint(body, j)
                vals.append(_pb_value(body[j:j + sl]))
                j += sl
            elif f == 5:               # extent
                extent, j = read_varint(body, j)
            elif f == 2 and w == 2:    # feature
                sl, j = read_varint(body, j)
                feats.append(_pb_feature(body[j:j + sl]))
                j += sl
            else:
                j = _pb_skip(body, j, w)
        for tags, geom in feats:
            props = {keys[tags[k]]: vals[tags[k + 1]]
                     for k in range(0, len(tags) - 1, 2)
                     if tags[k] < len(keys) and tags[k + 1] < len(vals)}
            if props.get("class") not in PLACE_CLASSES:
                continue
            title = props.get("name")
            if not isinstance(title, str) or not geom:
                continue
            out.append((title, geom[0], geom[1], extent))
    return out


def _pb_value(buf: bytes):
    i = 0
    while i < len(buf):
        f, w, i = _pb_key(buf, i)
        if f == 1 and w == 2:
            ln, i = read_varint(buf, i)
            return buf[i:i + ln].decode("utf-8", "replace")
        if f in (4, 5):
            return read_varint(buf, i)[0]
        i = _pb_skip(buf, i, w)
    return None


def _pb_feature(buf: bytes) -> tuple[list[int], list[int]]:
    """-> (tags, [x, y] первой точки геометрии). Только MoveTo, этого хватает для place."""
    tags: list[int] = []
    geom: list[int] = []
    i = 0
    while i < len(buf):
        f, w, i = _pb_key(buf, i)
        if f == 2 and w == 2:      # tags
            ln, i = read_varint(buf, i)
            end = i + ln
            while i < end:
                v, i = read_varint(buf, i)
                tags.append(v)
        elif f == 4 and w == 2:    # geometry
            ln, i = read_varint(buf, i)
            end = i + ln
            if not geom:
                cmd, i = read_varint(buf, i)
                if (cmd & 0x7) == 1 and (cmd >> 3) >= 1:  # MoveTo
                    dx, i = read_varint(buf, i)
                    dy, i = read_varint(buf, i)
                    geom = [(dx >> 1) ^ -(dx & 1), (dy >> 1) ^ -(dy & 1)]
            i = end
        else:
            i = _pb_skip(buf, i, w)
    return tags, geom


def load_boundary(path: Path) -> list[tuple[tuple[float, float, float, float], list]]:
    """GeoJSON от cut_packs -> [(bbox, [внешнее кольцо, дыры...]), ...].

    bbox считается сразу: у субъекта из десятка колец и тысяч вершин проверка
    точки — это самое дорогое место, а отсечь большинство точек можно сравнением
    четырёх чисел (у `ru-len` в границе 8218 вершин).
    """
    data = json.loads(path.read_text(encoding="utf-8"))
    polys = []
    for feature in data.get("features", []):
        geom = feature.get("geometry") or {}
        parts = geom.get("coordinates") or []
        if geom.get("type") == "Polygon":
            parts = [parts]
        elif geom.get("type") != "MultiPolygon":
            continue
        for rings in parts:
            prepared = [[(c[0], c[1]) for c in ring] for ring in rings]
            xs = [x for x, _ in prepared[0]]
            ys = [y for _, y in prepared[0]]
            polys.append(((min(xs), min(ys), max(xs), max(ys)), prepared))
    return polys


def _in_ring(lon: float, lat: float, ring: list[tuple[float, float]]) -> bool:
    inside = False
    for i in range(len(ring)):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % len(ring)]
        if (y1 > lat) != (y2 > lat) and lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def in_boundary(lon: float, lat: float, polys) -> bool:
    for (minx, miny, maxx, maxy), rings in polys:
        if not (minx <= lon <= maxx and miny <= lat <= maxy):
            continue
        if _in_ring(lon, lat, rings[0]) and not any(_in_ring(lon, lat, h) for h in rings[1:]):
            return True
    return False

# ── Проверки ─────────────────────────────────────────────────────────────────

def resolve_flags(tuning: dict, code: str) -> dict:
    """Те же правила слияния, что и в build_pmtiles.py: countries > defaults."""
    iso = code.split("-")[0]
    merged = dict(tuning.get("defaults") or {})
    merged.update((tuning.get("countries") or {}).get(iso, {}))
    return merged


def check_pack(code: str, source, tuning: dict) -> list[str]:
    """-> список проблем; пустой список = пак в порядке."""
    pack = Pmtiles(source)
    problems: list[str] = []

    if pack.tile_type != 1:
        problems.append(f"tile_type={pack.tile_type}, ожидался 1 (MVT)")

    flags = resolve_flags(tuning, code)
    want_maxzoom = flags.get("maxzoom")
    if want_maxzoom is not None and pack.max_zoom != int(want_maxzoom):
        problems.append(f"maxzoom={pack.max_zoom}, в planetiler.yaml {want_maxzoom}")

    # minzoom проверяется так же, как maxzoom: пак с лишними обзорными зумами
    # (или, наоборот, без z13) означает, что его собрали не тем конфигом.
    want_minzoom = flags.get("minzoom", 0)
    if pack.min_zoom != int(want_minzoom):
        problems.append(f"minzoom={pack.min_zoom}, в planetiler.yaml {want_minzoom}")

    missing = (set(REQUIRED_LAYERS) - set(flags.get("exclude_layers") or [])) - pack.layers()
    if missing:
        problems.append(f"нет слоёв: {', '.join(sorted(missing))}")

    return problems


# Пункты ближе этого друг к другу считаем одним и тем же: один НП попадает в
# несколько соседних тайлов (у тайлов есть буфер), а координата подписи в них
# чуть разная. 0.002° ~ 200 м — меньше, чем расстояние между разными деревнями.
PLACE_DEDUP_GRID = 0.002

# Сколько названий показывать в тексте проблемы, чтобы лог оставался читаемым.
PROBLEM_SAMPLE = 5


def check_cities(code: str, source, polygons_dir: Path) -> list[str]:
    """«У каждого НП внутри границы есть дороги на maxzoom» — иначе пак дырявый.

    Порога у проверки нет: дороги либо есть, либо нет. Она сильнее проверки по
    набору слоёв — та ловит только полную потерю, потому что уцелевших городов
    хватает, чтобы каждый слой где-нибудь да встретился.

    Точки берутся с нижнего зума пака (сейчас z11): выше их не больше, а тайлов
    вчетверо — замерено на `ru-len`, z11 даёт 1167 тайлов и 570 НП, z12 — 4432
    тайла и 532 НП. Дороги ищутся в тайле самого НП, а если их там нет — в
    восьми соседних (см. ниже, почему).

    **Фильтр по границе обязателен.** В пак затекают чужие города: приграничная
    полоса шириной в буфер плюс обзорные зумы, где тайл шире субъекта. У `ru-ad`
    на z11 видно 379 названий, из них 119 — за границей Адыгеи. Без фильтра
    проверка ругалась бы на соседей, у которых дорог в этом паке и не должно
    быть. Граница берётся из polygons/<код>.geojson, который кладёт cut_packs.py;
    нет файла — проверка молча пропускается.
    """
    boundary_code = code[:-len("-east")] if code.endswith("-east") else code
    boundary_path = polygons_dir / f"{boundary_code}.geojson"
    if not boundary_path.is_file():
        log.debug("%s: нет %s — проверка по городам пропущена", code, boundary_path)
        return []

    polys = load_boundary(boundary_path)
    if not polys:
        log.debug("%s: %s без полигонов — проверка по городам пропущена", code, boundary_path)
        return []

    pack = Pmtiles(source)
    seen: dict[tuple[int, int], tuple[str, float, float]] = {}
    parsed: dict[tuple[int, int], list[tuple[str, int, int, int]]] = {}
    for (z, x, y), ref in pack.iter_tiles(pack.min_zoom):
        points = parsed.get(ref)
        if points is None:
            points = mvt_place_points(pack.body(*ref))
            parsed[ref] = points
        for name, px, py, extent in points:
            lon, lat = tile_pixel_to_lonlat(z, x, y, px, py, extent)
            seen.setdefault((int(lon / PLACE_DEDUP_GRID), int(lat / PLACE_DEDUP_GRID)), (name, lon, lat))

    # Фильтр по границе — после дедупликации, а не до: один НП лежит в десятке
    # соседних тайлов, а point-in-polygon по границе субъекта дороже всего
    # остального вместе взятого (на `ru-len` это было 2.2 с из 2.5).
    seen = {key: point for key, point in seen.items() if in_boundary(point[1], point[2], polys)}

    empty: list[str] = []
    checked_tiles: dict[tuple[int, int], bool] = {}

    def has_roads(x: int, y: int) -> bool:
        cached = checked_tiles.get((x, y))
        if cached is None:
            body = pack.tile(pack.max_zoom, x, y)
            cached = bool(body) and mvt_layer_has_features(body, "transportation")
            checked_tiles[(x, y)] = cached
        return cached

    for name, lon, lat in seen.values():
        tx, ty = lonlat_to_tile(lon, lat, pack.max_zoom)
        # Соседние тайлы смотрим, только если в своём дорог нет: у деревни на
        # 30 дворов подъезд запросто лежит в соседней клетке (2.4 км на z14), и
        # это не дыра в паке, а редкая застройка. Так отсеялись все три
        # срабатывания на корпусе 2026-09-02 — Сармантаевка, Шестаево,
        # Луньгинский Майдан: у каждой в своём тайле есть дома, но нет дорог, а
        # вокруг дороги на месте. Потерю целого региона это не маскирует: там
        # пусты и все соседи.
        if has_roads(tx, ty):
            continue
        if any(has_roads(tx + dx, ty + dy)
               for dx in (-1, 0, 1) for dy in (-1, 0, 1) if (dx, dy) != (0, 0)):
            continue
        empty.append(name)

    log.debug("%s: НП внутри границы %d, без дорог %d", code, len(seen), len(empty))
    if not empty:
        return []
    sample = ", ".join(sorted(empty)[:PROBLEM_SAMPLE])
    tail = f" и ещё {len(empty) - PROBLEM_SAMPLE}" if len(empty) > PROBLEM_SAMPLE else ""
    return [f"{len(empty)} НП без дорог на z{pack.max_zoom}: {sample}{tail}"]


def check_completeness(codes: set[str], polygons_dir: Path, country: common.Country) -> list[str]:
    """Каждой границе — свой пак. Ловит субъект, молча выпавший из сборки.

    Ожидаемый список — не сами границы, а паки, которые из них должны получиться
    (common.pack_plan): склеенная группа (`merge`) — один пак, исключённый
    субъект (`exclude`) — ни одного. Без этого проверка ругалась бы на код
    группы как на лишний, а на её части и на исключённого — как на пропавших.
    """
    iso = country.iso
    if not polygons_dir.exists():
        log.debug("%s: нет %s — проверка комплектности пропущена", iso, polygons_dir)
        return []
    subjects = sorted(p.name.removesuffix(".osm") for p in polygons_dir.glob(f"{iso}-*.osm"))
    if not subjects:
        return []
    try:
        expected = set(common.pack_plan(country, subjects))
    except ValueError as exc:
        return [str(exc)]
    # Антимеридианный субъект дал бы два пака из одной границы (ru-chu +
    # ru-chu-east), поэтому лишним считается только пак, у которого нет границы и
    # после снятия суффикса -east. Сейчас таких субъектов нет — единственный
    # (Чукотка) в exclude, — но снятие суффикса живёт вместе с
    # cut_packs.split_at_antimeridian: они возвращаются только вдвоём.
    missing = sorted(expected - codes)
    extra = sorted(c for c in codes - expected if c.removesuffix("-east") not in expected)
    out = []
    if missing:
        out.append(f"нет паков для границ: {', '.join(missing)}")
    if extra:
        # Сюда попадает и пак субъекта, который с прошлой сборки уехал в группу
        # merge или в exclude: cut_packs.py его не удаляет, а build_index.py
        # возьмёт в манифест оба — и склейку, и осколок. Публиковать такое нельзя.
        out.append(
            "лишние паки (нет границы, либо субъект склеен в другой пак или исключён): "
            + ", ".join(extra)
        )
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--regions", type=Path, default=common.REGIONS_YAML)
    parser.add_argument("--tuning", type=Path, default=PLANETILER_YAML)
    parser.add_argument("--out-dir", type=Path, default=common.OUT_DIR)
    parser.add_argument("--polygons-dir", type=Path, default=common.POLYGONS_DIR)
    parser.add_argument("--base-url", help="проверять опубликованные паки по HTTP range вместо локального out/")
    parser.add_argument("--cities", choices=("auto", "on", "off"), default="auto",
                        help="проверка «у каждого НП есть дороги на maxzoom»: auto = включена локально "
                             "и выключена под --base-url (по HTTP это сотни range-запросов на пак)")
    parser.add_argument("--only", help="ограничиться странами по iso, через запятую")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    common.setup_logging(args.verbose)

    countries = common.filter_by_iso(common.enabled_countries(args.regions), args.only)
    if not countries:
        log.info("Нет enabled-стран — проверять нечего.")
        return 0

    tuning = {}
    if args.tuning.exists():
        tuning = yaml.safe_load(args.tuning.read_text(encoding="utf-8")) or {}

    failed = checked = 0
    with_cities = args.cities == "on" or (args.cities == "auto" and not args.base_url)
    if not with_cities:
        log.debug("проверка по городам выключена (--cities=%s, base_url=%s)", args.cities, bool(args.base_url))

    for country in countries:
        entries = pack_entries(country.iso, args)
        if not entries:
            log.error("%s: нечего проверять — паков нет", country.iso)
            failed += 1
            continue

        total = len(entries)
        for i, (code, location) in enumerate(entries, start=1):
            source = HttpSource(location) if args.base_url else LocalSource(location)
            try:
                problems = check_pack(code, source, tuning)
                if with_cities:
                    problems += check_cities(code, source, args.polygons_dir)
            except Exception as exc:  # битый файл — тоже результат проверки, а не повод падать
                problems = [f"не читается: {exc}"]
            finally:
                source.close()
            checked += 1
            if problems:
                failed += 1
                log.error("[%d/%d] %-14s ПЛОХО %s", i, total, code, "; ".join(problems))
            else:
                log.info("[%d/%d] %-14s OK", i, total, code)

        for problem in check_completeness({c for c, _ in entries}, args.polygons_dir, country):
            log.error("%s: %s", country.iso, problem)
            failed += 1

    if failed:
        log.error(
            "Проверено %d паков, проблем %d. Дырявый субъект пересобрать: удалить его "
            "polygons/<код>.osm, затем fetch_boundaries.py --code <код> и cut_packs.py --only <iso> "
            "(пересобирать full/<iso>.pmtiles заново не нужно, если исходник не менялся). "
            "Лишний пак — просто удалить файл из out/<iso>/: границу он не трогает, а у "
            "склеенного субъекта она ещё нужна группе.",
            checked, failed,
        )
        return 1
    log.info("Проверено %d паков, все в порядке.", checked)
    return 0


def pack_entries(iso: str, args) -> list[tuple[str, object]]:
    """[(код, путь-или-URL)] — из index.json опубликованного бакета или из локального out/<iso>/."""
    if args.base_url:
        base = args.base_url.rstrip("/")
        with urllib.request.urlopen(base + "/index.json", timeout=30) as resp:
            index = json.load(resp)
        return [
            (r["code"], f"{base}/{r['path']}")
            for c in index.get("countries", []) if c["iso"] == iso
            for r in c["regions"]
        ]
    return [(p.name.removesuffix(".pmtiles"), p) for p in sorted((args.out_dir / iso).glob("*.pmtiles"))]


if __name__ == "__main__":
    sys.exit(main())
