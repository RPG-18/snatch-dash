# tools/

Вспомогательные скрипты, не входящие в сборку приложения. Сейчас здесь одно —
[`planetiler/`](planetiler): конвейер «выгрузка OSM → офлайн-тайлы PMTiles».

- **Что это и как запускать** — [`planetiler/README.md`](planetiler/README.md).
- **Что вообще лежит в `tools/`** — [`CLAUDE.md`](CLAUDE.md).
- **Как ставить окружение** — этот файл, ниже.

## Окружение

Четыре внешние вещи нужны, чтобы прогнать конвейер `tools/planetiler/*`
целиком: **Python** (сами скрипты), **osmium-tool** (`cut_packs.py` переводит
границу субъекта в GeoJSON через `osmium export`), **Java + planetiler.jar**
(`build_pmtiles.py`) и **pmtiles CLI** (`cut_packs.py` вырезает им паки из
сборки страны). Ниже — как поставить каждую на Linux/macOS/Windows. Версии, на
которых конвейер реально прогонялся целиком: Python 3.14, osmium-tool 1.19.1,
OpenJDK 21.0.12, pmtiles 1.31.2 (macOS 26.6, Apple M3 Pro).

### Python 3.9+

Нужен для всех `.py`-скриптов (`fetch_boundaries.py`, `build_pmtiles.py`,
`cut_packs.py`, `build_index.py`) и два пакета из `requirements.txt`: PyYAML и
shapely (буфер вокруг границы субъекта перед нарезкой пака).

- **Linux (Debian/Ubuntu)**:
  ```bash
  sudo apt install python3 python3-pip
  ```
- **macOS**: системный `python3` (идёт с Xcode Command Line Tools) подходит,
  но удобнее через Homebrew — не конфликтует с системным и `pip` работает без
  прав администратора:
  ```bash
  brew install python@3.12
  ```
- **Windows**: установщик с [python.org](https://www.python.org/downloads/)
  (обязательно отметить «Add python.exe to PATH» в инсталляторе) или:
  ```powershell
  winget install Python.Python.3.12
  ```

Дальше одинаково на всех трёх ОС:
```bash
cd tools/planetiler
pip install -r requirements.txt
```

В проекте уже есть venv в корне репозитория — ставьте зависимости конвейера
туда и оттуда же запускайте скрипты:

```bash
../../.venv/bin/pip install -r requirements.txt
../../.venv/bin/python cut_packs.py --only ru
```

Это не вкусовщина: на Homebrew, свежих Debian/Ubuntu и Fedora системный Python
закрыт PEP 668, и `pip install` в него отвечает
`error: externally-managed-environment`. Ломать систему
`--break-system-packages` не нужно — для этого и venv.

### osmium-tool

Нужен только `cut_packs.py` (шаг «Паки субъектов» в
[README.md](planetiler/README.md)), где `osmium export` собирает полигон границы
из релации, — и только для стран с `mode: subjects` (сейчас — Россия). Проверить,
что поставилось: `osmium --version`.

- **Linux (Debian/Ubuntu 20.04+)** — есть в стандартных репозиториях:
  ```bash
  sudo apt install osmium-tool
  ```
  На дистрибутивах без готового пакета — через conda-forge (см. ниже) или
  сборка из исходников ([osmcode/osmium-tool](https://github.com/osmcode/osmium-tool)).
- **macOS** — через Homebrew (подтягивает `libosmium`, `protozero`, `gdal` и
  т.д. сам):
  ```bash
  brew install osmium-tool
  ```
- **Windows** — готовых официальных сборок под Windows нет. Самый надёжный
  путь — **WSL2** (Ubuntu), дальше внутри него та же команда, что на Linux:
  ```powershell
  wsl --install
  ```
  ```bash
  # уже внутри WSL/Ubuntu
  sudo apt install osmium-tool
  ```
  На Windows после этого проще всего гонять **весь** конвейер (включая
  Python-скрипты) прямо внутри WSL — тогда инструкция ничем не отличается от
  Linux. Если WSL не вариант — можно попробовать conda-forge (`conda install
  -c conda-forge osmium-tool`), но актуальность win-64 сборки этого пакета я
  не проверял, WSL надёжнее.

Через conda (кросс-платформенно, если уже используете conda/mamba —
Linux/macOS проверено, Windows не проверял):
```bash
conda install -c conda-forge osmium-tool
```

### Java 21+ и planetiler.jar

Нужны только `build_pmtiles.py` (шаг «Тайлы»). Нужен **JDK/JRE 21 или новее**:
классы в нашем `planetiler.jar` имеют версию формата 65, а её понимает только
Java 21+. На 17 запуск падает с `UnsupportedClassVersionError` ещё до чтения
данных — то есть «поставить 17, вдруг хватит» не сработает.

- **Linux (Debian/Ubuntu)**:
  ```bash
  sudo apt install openjdk-21-jre
  ```
- **macOS** (через Homebrew; JDK там keg-only и в `PATH` сам не попадает —
  нужен второй шаг, иначе `java` просто «не найден», хотя пакет установлен):
  ```bash
  brew install openjdk@21
  echo 'export PATH="'"$(brew --prefix openjdk@21)"'/bin:$PATH"' >> ~/.zshrc
  ```
  Разово, без правки `~/.zshrc`, можно и так:
  ```bash
  PATH="$(brew --prefix openjdk@21)/bin:$PATH" python3 build_pmtiles.py
  ```
- **Windows** — [Eclipse Temurin](https://adoptium.net/) (стандартный
  бесплатный сборщик OpenJDK для Windows):
  ```powershell
  winget install EclipseAdoptium.Temurin.21.JDK
  ```

Планетайлер распространяется одним jar-файлом с
[GitHub Releases](https://github.com/onthegomap/planetiler/releases), сборка
не нужна — просто скачать:

```bash
# Linux / macOS
curl -L -o planetiler.jar https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar
```

```powershell
# Windows (PowerShell)
Invoke-WebRequest -Uri "https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar" -OutFile planetiler.jar
```

Если имя ассета в конкретном релизе отличается — проверить на странице
[releases](https://github.com/onthegomap/planetiler/releases/latest).

Дальше путь до скачанного jar передаётся в `build_pmtiles.py` через
переменную окружения — см. раздел «Как запустить» в [`planetiler/README.md`](planetiler/README.md):
```bash
PLANETILER_JAR=/path/to/planetiler.jar python3 build_pmtiles.py
```

**Память и диск**: официальное требование planetiler —
[не меньше 0.5× RAM от размера входного `.osm.pbf` и не меньше 10× диска](https://github.com/onthegomap/planetiler/blob/main/PLANET.md);
то есть узкое место — диск, а не heap. По умолчанию planetiler держит данные не в памяти, а на диске
(`storage=mmap`, `nodemap_type=sparsearray`, `nodemap_storage=mmap`) — в RAM
остаётся только индекс. Замерено 2026-09-02: на входе 147 МБ пиковый RSS 3.0
ГиБ; preflight на всей России (`ru-latest.osm.pbf`, 4.15 ГБ) просит 300 МБ RAM
под индекс, 18–20 ГБ временных файлов и 6.9 ГБ под выход. То есть ограничение
здесь дисковое, а не по heap: для субъектов РФ хватает настроек по умолчанию,
а `JAVA_TOOL_OPTIONS="-Xmx…"` поднимают ради скорости тайлинга, а не чтобы
влезть. Свои требования planetiler печатает в начале лога (уровень DEBUG) и
предупреждает, если места не хватает.

### pmtiles CLI

Нужен `cut_packs.py`: паки субъектов вырезаются из сборки страны командой
`pmtiles extract --region`. Один статический бинарник на Go, зависимостей нет.
Проверить: `pmtiles --version`.

- **macOS** — Homebrew:
  ```bash
  brew install pmtiles
  ```
- **Linux / Windows** — готовый бинарник со страницы релизов
  [protomaps/go-pmtiles](https://github.com/protomaps/go-pmtiles/releases),
  положить в любой каталог из `PATH`. Или, если стоит Go:
  ```bash
  go install github.com/protomaps/go-pmtiles@latest
  ```

Версия должна уметь `extract --region` (проверено на 1.31.2). Без региона
`extract` умеет только bbox — этого мало: bbox субъекта захватывает соседей и,
у Ленинградской области, пол-Балтики.

### Тюнинг под конкретную машину (пример: MacBook Pro M3 Pro, 18 ГБ)

`build_pmtiles.py` не зашивает память/потоки под конкретное железо (иначе
он перестал бы быть переносимым на другую машину/CI) — обе настройки через
окружение, значения ниже — под M3 Pro/18 ГБ:

```bash
JAVA_TOOL_OPTIONS="-Xmx10g" PLANETILER_THREADS=8 \
  PLANETILER_JAR=/path/to/planetiler.jar python3 build_pmtiles.py
```

- **`-Xmx10g`** — из 18 ГБ отдаём JVM чуть больше половины, остальное — macOS
  и что там ещё открыто (браузер, IDE). С 2026-09-02 planetiler получает на вход
  всю страну сразу (4.15 ГБ по России), но heap это почти не трогает: временные
  данные лежат в mmap-файлах, в RAM — 300 МБ индекса. Замер полного прогона по
  России: пик RSS 8.6 ГБ при `-Xmx10g`. При нехватке — поднять `-Xmx`, благо
  есть куда (до ~14-15 ГБ, оставляя системе минимум).
- **`PLANETILER_THREADS=8`** — подтверждено (`sysctl -n hw.ncpu` → `12`,
  `hw.perflevel0/1.logicalcpu` → `6`/`6`): это 12-ядерная старшая
  конфигурация M3 Pro (6 производительных + 6 энергоэффективных). 8 — не все
  12 ядер отдаются под многочасовую сборку, 4 остаются машине на всё
  остальное (macOS UI, браузер, IDE), пока конвейер крутится в фоне. Если
  ноутбук в этот момент не нужен ни для чего другого — можно смело убрать
  `PLANETILER_THREADS` вовсе (planetiler возьмёт все 12 ядер сам) или
  выставить его в `12` явно.
**Проверено этими значениями** (2026-09-02): вся Россия собралась одним
прогоном за 11 мин 57 с (пик RSS 8.6 ГБ, ~20 ГБ временных файлов, выход 5.07 ГБ),
затем 84 пака вырезались минуту. До перехода на эту схему тот же результат
занимал 32 минуты на 84 прогона planetiler плюс 20 минут нарезки osmium.
Прежний замер по отдельным субъектам: самый крупный вход — Краснодарский край, 107 МБ
`.osm.pbf`.

- Более тонкие флаги planetiler под экономию памяти (`--nodemap-type`,
  `--nodemap-storage` и т.п. — то, что реально нужно на planet-scale
  сборках) сознательно не трогали: при нашей архитектуре (нарезка на
  субъекты до planetiler) они, скорее всего, не понадобятся. Если всё же
  словите OOM на каком-то отдельно большом субъекте — смотреть
  `java -jar planetiler.jar --help` за актуальным списком, а не куда-то
  устаревшее в этом README.

### Итоговая проверка

```bash
python3 --version   # 3.9+
osmium --version    # любая современная версия osmium-tool
java -version       # 21 или новее
pmtiles --version   # 1.31+ (нужен `extract --region`)
```
