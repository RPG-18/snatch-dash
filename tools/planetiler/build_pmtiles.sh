#!/usr/bin/env bash
# extracts/*.osm.pbf -> out/<iso>/<код>.pmtiles через planetiler.
#
# Единственный оставшийся shell-скрипт в конвейере (split.py/build_index.py —
# на Python ради переносимости, см. plan.md); здесь это просто цикл вызовов
# одной Java-команды, переписывать на Python не просили.
#
# Требует переменную окружения PLANETILER_JAR — путь к planetiler.jar
# (https://github.com/onthegomap/planetiler/releases).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXTRACTS_DIR="${EXTRACTS_DIR:-$SCRIPT_DIR/extracts}"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/out}"

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

for f in "${files[@]}"; do
  base="$(basename "$f" .osm.pbf)" # ru-mow.osm.pbf -> ru-mow ; by.osm.pbf -> by
  iso="${base%%-*}"                # ru-mow -> ru ; by -> by
  dest_dir="$OUT_DIR/$iso"
  mkdir -p "$dest_dir"
  echo "==> $base -> $dest_dir/$base.pmtiles"
  java -jar "$PLANETILER_JAR" --osm-path="$f" --output="$dest_dir/$base.pmtiles"
done
