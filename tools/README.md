# tools/

См. [`tools/CLAUDE.md`](CLAUDE.md) за тем, что вообще здесь лежит. Сейчас
единственное содержимое — [`planetiler/`](planetiler) (подготовка оффлайн
векторных тайлов в PMTiles), схема конвейера — [`planetiler/plan.md`](planetiler/plan.md).

## Окружение

Три внешние вещи нужны, чтобы прогнать конвейер `tools/planetiler/*`
целиком: **Python** (сами скрипты), **osmium-tool** (`split.py` режет
`.osm.pbf` через него) и **Java + planetiler.jar** (`build_pmtiles.sh`).
Ниже — как поставить каждую на Linux/macOS/Windows.

### Python 3.9+

Нужен для всех `.py`-скриптов (`fetch_boundaries.py`, `build_extract_config.py`,
`split.py`, `build_index.py`) и один пакет из `requirements.txt` (PyYAML).

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

### osmium-tool

Нужен только `split.py` (шаг «Нарезка» в [plan.md](planetiler/plan.md)) —
и только для стран с `mode: subjects` (сейчас — Россия). Проверить, что
поставилось: `osmium --version`.

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

### Java 17+ и planetiler.jar

Нужны только `build_pmtiles.sh` (шаг «Тайлы»). Planetiler требует **JDK/JRE
17 или новее** — более старый Java не подойдёт.

- **Linux (Debian/Ubuntu)**:
  ```bash
  sudo apt install openjdk-17-jre
  ```
- **macOS** (через Homebrew; Homebrew не линкует JDK в `PATH` по умолчанию —
  нужен один дополнительный шаг):
  ```bash
  brew install openjdk@17
  echo 'export PATH="'"$(brew --prefix openjdk@17)"'/bin:$PATH"' >> ~/.zshrc
  ```
- **Windows** — [Eclipse Temurin](https://adoptium.net/) (стандартный
  бесплатный сборщик OpenJDK для Windows):
  ```powershell
  winget install EclipseAdoptium.Temurin.17.JDK
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

Дальше путь до скачанного jar передаётся в `build_pmtiles.sh` через
переменную окружения — см. раздел «Как запустить» в [`planetiler/plan.md`](planetiler/plan.md):
```bash
PLANETILER_JAR=/path/to/planetiler.jar ./build_pmtiles.sh
```

**Память**: planetiler держит бо́льшую часть данных в памяти при сборке —
ориентировочно нужно ~1 ГБ heap на 1 ГБ входного `.osm.pbf`. Для отдельных
субъектов РФ хватит настроек по умолчанию, а для чего-то заметно крупнее
может понадобиться `JAVA_TOOL_OPTIONS="-Xmx8g"` (или больше) перед запуском
`build_pmtiles.sh`.

### Тюнинг под конкретную машину (пример: MacBook Pro M3 Pro, 18 ГБ)

`build_pmtiles.sh` не зашивает память/потоки под конкретное железо (иначе
он перестал бы быть переносимым на другую машину/CI) — обе настройки через
окружение, значения ниже — под M3 Pro/18 ГБ:

```bash
JAVA_TOOL_OPTIONS="-Xmx10g" PLANETILER_THREADS=8 \
  PLANETILER_JAR=/path/to/planetiler.jar ./build_pmtiles.sh
```

- **`-Xmx10g`** — из 18 ГБ отдаём JVM чуть больше половины, остальное — macOS
  и что там ещё открыто (браузер, IDE). Наш конвейер режет страну на субъекты
  **до** planetiler (`osmium extract` в `split.py`) — по сравнению с
  planet-scale/страна-целиком сборками, для которых обычно и пишут советы про
  экономию памяти planetiler, каждый отдельный вызов здесь получает файл
  размером с один регион, не с всю Россию сразу, так что 10 ГБ — с большим
  запасом даже для самых крупных субъектов РФ. При нехватке — поднять
  `-Xmx`, благо есть куда (до ~14-15 ГБ, оставляя системе минимум).
- **`PLANETILER_THREADS=8`** — подтверждено (`sysctl -n hw.ncpu` → `12`,
  `hw.perflevel0/1.logicalcpu` → `6`/`6`): это 12-ядерная старшая
  конфигурация M3 Pro (6 производительных + 6 энергоэффективных). 8 — не все
  12 ядер отдаются под многочасовую сборку, 4 остаются машине на всё
  остальное (macOS UI, браузер, IDE), пока конвейер крутится в фоне. Если
  ноутбук в этот момент не нужен ни для чего другого — можно смело убрать
  `PLANETILER_THREADS` вовсе (planetiler возьмёт все 12 ядер сам) или
  выставить его в `12` явно.
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
java -version       # 17 или новее
```
