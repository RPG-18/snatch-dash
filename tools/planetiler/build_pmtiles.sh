#!/usr/bin/env bash
# extracts/*.osm.pbf -> out/<iso>/<код>.pmtiles через planetiler.
#
# Единственный оставшийся shell-скрипт в конвейере (split.py/build_index.py —
# на Python ради переносимости, см. plan.md); здесь это просто цикл вызовов
# одной Java-команды, переписывать на Python не просили.
#
# Требует переменную окружения PLANETILER_JAR — путь к planetiler.jar
# (https://github.com/onthegomap/planetiler/releases).
#
# --languages=ru,en — приложение двуязычное (RU/EN, см. lib/l10n/), схема
# OpenMapTiles по умолчанию иначе тянет в тайлы name:* на всех языках,
# раздувая размер без пользы.
#
# --maxzoom=14 — в отличие от растра (где 11-20 из TileProvider — это 10
# отдельных наборов картинок), у векторных тайлов зум сверху — это потолок
# детализации, а не диапазон, который нужно печь целиком: любой нормальный
# vector-tile рендерер (MapLibre и т.п.) сам оверзумит выше maxzoom —
# масштабирует последний сгенерированный тайл, без новых данных с сервера.
# 14 — стандартный потолок схемы OpenMapTiles, дальше не имеет смысла (в
# OSM просто нет более мелких деталей дороги, чем то, что показывает z14).
# minzoom не трогали — вниз пирамида нужна вся, на разных зумах в схему
# попадают разные объекты (иначе на виде всей области рисовались бы заборы).
#
# Память/потоки — машино-зависимая настройка, не зашита сюда намеренно (это
# сломало бы переносимость на другое железо/CI), задаётся через окружение:
#   JAVA_TOOL_OPTIONS="-Xmx10g" PLANETILER_THREADS=8 ./build_pmtiles.sh
# Конкретные числа под MacBook Pro M3 Pro / 18 ГБ — см. tools/README.md
# ("Тюнинг под конкретную машину"). Без PLANETILER_THREADS planetiler сам
# берёт все доступные ядра (--threads не передаётся вовсе).
#
# --download — профиль OpenMapTiles (тот, что собирает planetiler.jar по
# умолчанию) требует ещё три глобальных вспомогательных датасета (лини
# берегов озёр, полигоны воды, Natural Earth) для фоновых слоёв на низких
# зумах — без них падает с "does not exist. Run with --download to fetch it"
# ещё до чтения нашего .osm.pbf. Это не зависит от региона и не качается
# заново на каждый запуск — кладётся в data/sources/ рядом и переиспользуется
# для всех следующих субъектов/стран, но на самый первый прогон (на любом,
# даже крошечном регионе) стоит рассчитывать ~1.4 ГБ разовой подкачки.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXTRACTS_DIR="${EXTRACTS_DIR:-$SCRIPT_DIR/extracts}"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/out}"

# Скаляр, а не массив: пустой bash-массив под `${arr[@]}` с `set -u` на
# стоковом macOS bash 3.2 падает как "unbound variable" (Apple не обновляет
# bash из-за GPLv3; баг чинили в bash 4.4) — а PLANETILER_THREADS не задан
# в обычном режиме по умолчанию. Незаквоченный ${threads_arg} ниже — это
# намеренно, чтобы при пустом значении флаг просто исчезал, а не передавался
# пустой строкой.
threads_arg=""
if [[ -n "${PLANETILER_THREADS:-}" ]]; then
  threads_arg="--threads=$PLANETILER_THREADS"
fi

if [[ -z "${PLANETILER_JAR:-}" ]]; then
  echo "PLANETILER_JAR не задан — укажите путь к planetiler.jar" >&2
  echo "  (скачать: https://github.com/onthegomap/planetiler/releases)" >&2
  exit 1
fi

if [[ ! -f "$PLANETILER_JAR" ]]; then
  echo "PLANETILER_JAR указывает на несуществующий файл: $PLANETILER_JAR" >&2
  exit 1
fi

shopt -s nullglob
files=("$EXTRACTS_DIR"/*.osm.pbf)
shopt -u nullglob

if [[ ${#files[@]} -eq 0 ]]; then
  echo "Нет .osm.pbf в $EXTRACTS_DIR — сначала запустить split.py" >&2
  exit 1
fi

total=${#files[@]}
i=0
for f in "${files[@]}"; do
  i=$((i + 1))
  base="$(basename "$f" .osm.pbf)" # ru-mow.osm.pbf -> ru-mow ; by.osm.pbf -> by
  iso="${base%%-*}"                # ru-mow -> ru ; by -> by
  dest_dir="$OUT_DIR/$iso"
  mkdir -p "$dest_dir"
  echo "==> [$i/$total] $base -> $dest_dir/$base.pmtiles"
  java -jar "$PLANETILER_JAR" --osm-path="$f" --output="$dest_dir/$base.pmtiles" --languages=ru,en --maxzoom=14 --download $threads_arg
done
