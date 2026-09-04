# Ревью: офлайн-карты — ветка `offline-tiles-implementation` целиком (k3)

Полный проход по ветке относительно `main` (123 файла, ~11 300 вставок):
нативный движок дэша (Kotlin/MapLibre offscreen), Android-загрузчик паков
(`DownloadManager`), Dart-слой данных/состояния, экраны/роутер/локализация и
Python-конвейер сборки тайлов (`tools/`).

**Метод.** Пять независимых проходов по зонам (движок Kotlin, Android-загрузчик,
Dart данные/состояние, Dart экраны, Python-конвейер), затем ручная сверка всех
находок уровня CRITICAL/MAJOR по исходникам и контрактам
[`spec/remote_map_server.md`](spec/remote_map_server.md),
[`spec/drawing_from_local_tiles.md`](spec/drawing_from_local_tiles.md),
[`spec/offline_maps_screen.md`](spec/offline_maps_screen.md). Каждая находка
ниже подтверждена чтением кода, а не догадкой по диффу.

**Ограничение.** Flutter SDK в окружении нет — `flutter analyze`/`flutter test`/
сборка не перезапускались, ревью статическое. Класс «видно только на железе
526×300» (наклон камеры, читаемость, держит ли offscreen целевой fps) за рамками.

**Итог:** 1 CRITICAL, 13 MAJOR, ~30 MINOR. Блокеров «ничего не собирается/не
работает» нет; основная масса серьёзных находок — конкурентность и тайминг
(кадровый цикл, поллер, main↔worker в загрузчике), а не контракты с внешними API.

---

## CRITICAL

### 1. Дедлайн снапшота не ограничивает замерзание карты: в диапазоне 500 мс…5 с на приборке висит статичный кадр

[`MapSnapshotProvider.kt:114-167`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/map/MapSnapshotProvider.kt):

```kotlin
if (inFlight) {
    if (now - inFlightSince < WEDGED_MS) { … return@withContext null }
    abandoned++
    runCatching { snapshotter.cancel() }
    inFlight = false
}
…
val snapshot = withTimeoutOrNull(deadlineMs) { suspendCancellableCoroutine { … } }
```

`withTimeoutOrNull(deadlineMs)` выходит по таймауту, но `inFlight` остаётся
`true` — его сбрасывает только колбэк `start()`, когда MapLibre досчитает
снапшот. Каждый следующий `capture()` (кадр каждые ~250 мс) попадает в
`if (inFlight)` и возвращает `null` вплоть до `WEDGED_MS` = 5 с. Цикл крутится,
стрим идёт, но кадр не перерисовывается — на дэше последний удачный снапшот.

Док-комментарий класса обещает, что дедлайн защищает ровно от этого («a single
snapshot that never completes freezes the dash»), но он ограничивает только
сколько *цикл ждёт*, а не сколько *карта простаивает*. В режиме «карта еле
тянет» (тяжёлый стиль, много паков, медленный носитель — ровно то, для чего
дедлайн вводился) райдер 0.5–5 с видит замершую карту, неотличимую от обрыва
связи. Решение «не отменять по дедлайну, а обгонять» само по себе осознанное и
верное (отмена теряет битмап в нативной куче) — проблема в незакрытом хвосте и
в том, что телеметрия (`timeouts`/`abandoned`) не считает «кадров, пропущенных
из-за `inFlight`», то есть порог перехода на `SurfaceTexture` из спеки не по чем
принять.

**Что делать.** Минимум — считать/логировать inFlight-пропуски отдельно. По
существу: снимать `inFlight` по таймауту и recycle'ить опоздавший битмап по
поколению запроса, либо double-buffer (второй снапшоттер), либо принять отмену
с утечкой, как уже принято для `WEDGED_MS`.

---

## MAJOR

### 2. `disconnect()` релизит энкодер, не дождавшись кадрового цикла — гонка, уже починенная в `startStream`

[`DashEngineController.kt:338` против `:345`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt):

```kotlin
streamJob?.cancel(); streamJob = null   // :338 — без cancelAndJoin()
…
encoder?.release(); encoder = null      // :345 — на главном потоке
```

`startStream()` ту же гонку чинит через `cancelAndJoin()` (`:590`, комментарий
`:541-548` прямо описывает этот баг). В `disconnect()` — нет: цикл на
`Dispatchers.Default` останавливается только на следующей точке приостановки, и
в окне между `cancel()` и остановкой главный поток успевает `encoder.release()`
→ `renderFrame`/`drain` на мёртвом `MediaCodec` → `IllegalStateException`, а при
`failures >= 3` цикл **пересоздаёт** энкодер (`:709-713`) уже после того, как
disconnect обнулил поле — утёкший кодек живёт до следующего `startStream`.

**Что делать.** Тот же `cancelAndJoin()` перед `release()` (в suspend-контексте)
или teardown в корутине, которая join'ит цикл до release.

### 3. RX-цикл при ошибке сокета выходит без teardown — сессия зависает в STREAMING навсегда

[`DashSession.kt:322-333`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/DashSession.kt) против `:340-352`:

Ветка `catch (e: Exception)` делает только `reportLinkLost()` и `break`: сокет
не закрыт, `socket` не обнулён, state не переведён в IDLE/ERROR,
heartbeat/stream-джобы не погашены. Watchdog-ветка по таймауту рядом всё это
делает — и её комментарий «Mirrors the socket-error path above» неверен.
`reportLinkLost` → `onError` — а `onError` в контроллере это всего лишь
`publishState(errorMessage = msg)` ([`DashEngineController.kt:257`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt)),
состояние сессии он не меняет. Give-up таймер к этому моменту отменён (он
гасится при достижении STREAMING, `:309`). Итог: `ENETUNREACH`/обрыв на
`receive()` без события от WiFi-менеджера → state STREAMING с мёртвым сокетом,
стрим шлёт RTP в никуда, а guard в `connect()` (`!= IDLE && != ERROR`, `:167`)
блокирует любой reconnect до ручного disconnect райдером.

**Что делать.** Симметричный с watchdog teardown: `sock.close(); socket = null;
setState(IDLE)` + отмена джоб (и `ackCounterJob` заодно — см. MINOR).

### 4. Пейсинг кадра: полный `delay(interval)` после ожидания снапшота — целевой fps недостижим, PTS дрейфует

[`DashEngineController.kt:643-644`, `:660`, `:718`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt):

Период итерации = `tick()` (ожидание снапшота до дедлайна) + encode/drain +
`delay(frameIntervalMs)`. При 4 fps (250 мс) и снапшоте ~100 мс фактический
период ~350 мс (~2.9 fps); при 300 мс — ~1.8 fps. Просадка ровно под нагрузкой.
При этом `videoPtsMs` шагает на **задуманный** интервал (`:660`), а кадры уходят
медленнее — RTP-таймлайн расходится с реальным темпом. Спека
(`drawing_from_local_tiles.md`, «Цикл ждёт снапшот») фиксирует «период =
max(интервал, латентность)» — реализация даёт `интервал + латентность`.
Комментарий `:630-636` («интервал и есть длина кадра») противоречит фактическому
`delay` после тела.

**Что делать.** `delay((frameIntervalMs - elapsedThisIteration).coerceAtLeast(0))`,
PTS от измеренного интервала.

### 5. Гонка main-поток vs `mapsWorker`: перезапуск закачки во время хеширования устанавливает недокачанный пак

[`MapPackDownloader.kt:171-215` (`install`), `:74-103` (`start`/`cancel`)](android/app/src/main/kotlin/ru/snatchdash/app/MapPackDownloader.kt);
диспетчеризация — [`MainActivity.kt:128-136`](android/app/src/main/kotlin/ru/snatchdash/app/MainActivity.kt).

`reconcile()` (sha256 секунды + `Files.move`) крутится на однопоточном
`mapsWorker`, а `start`/`cancel`/`delete` — на главном потоке; общие
`pending`-prefs и `.part`-файлы без единой блокировки. Сценарий: закачка
завершилась, worker в `install()` прочитал `expected` (`:173`) и хеширует;
пользователь перезапускает закачку того же кода → на main `cancel()` удаляет
`.part` и pending, `start()` пишет pending с новым id/sha, DownloadManager
создаёт тот же путь `<code>.pmtiles.part`. Worker дохешировал старое содержимое
(fd на удалённый inode дочитывается), хеш сходится с **старым** `expected` →
`Files.move` переименовывает **новый, пишущийся** `.part` в `<code>.pmtiles`,
`forget(code)` стирает pending новой закачки. Итог: обрезанный/битый пак под
финальным именем (движок его перечислит и будет рисовать), новая закачка пишет
в переименованный дескриптор и никогда не будет подхвачена. Альтернативное
проявление той же дыры: worker читает `expected` уже после перезаписи pending →
CHECKSUM_MISMATCH → `part.delete()` (`:186`) удаляет файл активной закачки.

**Что делать.** Прогонять `start`/`cancel`/`delete` через тот же
single-thread-исполнитель, либо в `install()` перед `move` перепроверять, что
pending id для кода всё ещё тот же и размер `.part` сходится с манифестом.

### 6. Одно исключение в `reconcile()` теряет результаты всего батча → файл на диске без строки в реестре

[`MapPackDownloader.kt:135-169`](android/app/src/main/kotlin/ru/snatchdash/app/MapPackDownloader.kt) — нет per-code try/catch; `install()` бросает
(`Files.move` `:195-200`, `sha256Of` `:246-257`). Исключение прерывает цикл,
накопленный `results` отбрасывается, `MainActivity` отвечает `RECONCILE_FAILED`.
Коды, обработанные до упавшего, уже переименованы/удалены из pending — Dart их
`INSTALLED` не получит. Это дрейф «файл без строки реестра»: движок пак видит и
рисует, а UI и гейт `hasInstalledPacks` — нет; `_dropRegistryRowsWithoutFiles`
([`offline_maps_controller.dart:185-193`](lib/state/offline_maps_controller.dart))
лечит только обратное направление (строка без файла). Туда же ведёт убийство
приложения между нативным rename и записью в sqlite — «атомарность установки» из
комментариев [`offline_map.dart:45-49`](lib/models/offline_map.dart) на деле две
операции через канал.

**Что делать.** Per-code try/catch с `FAILED` в результат; фолбэк на неатомарный
`Files.move(REPLACE_EXISTING)` при `AtomicMoveNotSupportedException`; healing
«файл без строки» при старте (или хотя бы честные комментарии/спека).

### 7. Гонка «последнего тика»: поллер останавливается при уже начатой новой закачке

[`offline_maps_controller.dart:288-306`, `:276-279`](lib/state/offline_maps_controller.dart).

Тик при пустом `live` обнуляет `progress` и уходит в `await _harvest()`. Во
время этого await пользователь жмёт «скачать»: `download()` доходит до
`_startPolling()`, но `_poller != null` (тик ещё исполняется) — no-op. Тик
возобновляется, видит `progress.isEmpty` → `_poller.cancel()`. Новая закачка
идёт без поллера: прогресс не обновляется, а завершение не будет подхвачено
вообще — провайдер не autoDispose, повторный `build()` не случится до рестарта
приложения. `.part` висит неустановленным.

**Что делать.** После `_harvest()` перечитывать `progress()` перед решением об
остановке, либо в `_startPolling` ориентироваться на «тик исполняется» флагом и
перезапускать таймер из `finally`.

### 8. `checksumMismatch` ретраится вслепую, без сравнения `generated_at` — отклонение от протокола

[`offline_maps_controller.dart:325-331`, `:343-364`](lib/state/offline_maps_controller.dart). Спека `remote_map_server.md` («Порядок скачивания», п. 5):
несовпавший sha256 → перечитать `index.json` и сравнить `generated_at`:
изменился — штатный конфликт, повторить; **тот же — настоящая порча, сообщить и
не повторять**. Код обрабатывает `conflict` и `checksumMismatch` одинаково —
безусловный ретрай до 2 раз. На битом объекте в бакете это до 3 скачиваний по
сотням МБ (Якутия — 356 МБ) по мобильной сети, прежде чем пользователь увидит
ошибку. Тесты закрепляют именно это поведение — расхождение со спекой зашито и
в тест.

**Что делать.** Сравнивать `generatedAt` из результата reconcile (он возвращается
нативной стороной) со свежим манифестом; совпал — ошибка пользователю без ретрая.

### 9. Завершённая «за кадром» закачка не попадает в реестр до открытия экрана офлайн-карт

[`offline_maps_controller.dart:166-179`](lib/state/offline_maps_controller.dart)
+ отсутствие eager-watch в [`main.dart`](lib/main.dart).

`offlineMapsControllerProvider` ленив: reconcile запускается в `build()`
провайдера, а его никто не читает, кроме экрана офлайн-карт. Сценарий: приложение
убито во время скачивания, DownloadManager докачал пак; пользователь открывает
приложение — гейт навигации на Главной остаётся закрытым (реестр пуст), пока он
сам не зайдёт в «Офлайн-карты». Спека обещает обратное («блокировка снимается,
как только первый пак дошёл до реестра»), а комментарий `:172-175` называет это
основным сценарием, ради которого выбран системный загрузчик.

**Что делать.** Eager `ref.watch`/`read` в `main.dart` по образцу
`dashButtonControllerProvider`.

### 10. Async-gap: `ref.read` после `await showDialog` — `StateError` на disposed WidgetRef

[`offline_maps_screen.dart:221-222`, `:247-248`, `:371-372`](lib/screens/offline_maps_screen.dart).

Сценарий для `_AvailableTile._confirmCancel`: пока диалог открыт, поллер
доводит пак до `installed` → `installedPacksProvider` обновляется → пикер
пересобирается → установленный регион отфильтровывается из `matches` → тайл
unmount'ится под открытым диалогом → подтверждение вызывает `ref.read` на
defunct ref → необработанный `StateError`. Для `_InstalledTile` то же возможно
через `_dropRegistryRowsWithoutFiles` (строка реестра исчезает под диалогом
удаления). Провайдер не autoDispose — достаточно захватить notifier до `await`.

### 11. Утечка `MapSnapshotter` при detach Flutter-движка

[`OpendashDashEnginePlugin.kt:189-191`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/OpendashDashEnginePlugin.kt)
против [`DashEngineController.kt:351-352`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt).

`onDetachedFromEngine`: `controller?.dispose()` → `disconnect()` ставит
`scope.launch { snapshots.release(generation) }`, а следующей строкой
`job.cancel()` убивает этот scope — release-корутина с высокой вероятностью не
выполнится никогда. Hot restart / пересоздание FlutterEngine → snapshotter со
стилем, GL-контекстом и открытыми `.pmtiles` утекает на каждый detach.

**Что делать.** Освобождать snapshotter синхронно до `job.cancel()` (внутри
`release` всё равно уход на Main — `runBlocking` допустим на detach).

### 12. `upload.sh` публикует манифест раньше паков — боевой CDN битый всё время заливки

[`tools/planetiler/upload.sh:19-22`](tools/planetiler/upload.sh).

Рекурсивное копирование идёт по алфавиту, `index.json` уезжает первым. Всё
время публикации (~30 мин на 4.45 ГБ) манифест обещает паки с новыми `sha256`,
которых ещё нет; обрыв посередине оставляет корпус битым надолго. Спека
(`remote_map_server.md`, «Публикация») требует «`index.json` публикуется
последним», README предписывает обход («сначала `ru/`, потом манифест») — но
скрипт коммитится со старым поведением. Сопутствующее: нет исключения
`index.json.tmp` (его оставляет прерванный `build_index.py` — уедет мусором в
бакет), нет верификации после заливки.

### 13. Выход из навигации оставляет «призрачный» маршрут в `RouteController`

[`route_controller.dart:67-71`](lib/state/route_controller.dart) и
[`dash_screen.dart:32-37`, `:161`](lib/screens/dash_screen.dart).

`exitNavigation()` гасит `_navLoop` и зовёт `clearDestination()`, но
`state.destination`/`state.route` не сбрасывает (через `copyWith` их и не
обнулить — там `??`). Повторный вход на `/home/dash` рисует старый полилайн и
пин назначения и показывает «выйти из навигации», хотя нативно
`navigating == false`. Вводящий в заблуждение UI; райдер может «выйти» ещё раз.

**Что делать.** `state = const RouteState();` в `exitNavigation()`.

### 14. Диапазон глифов не покрывает «№» (U+2116) — битые подписи POI на дэше

[`tools/styles/build_styles.py:38-40`](tools/styles/build_styles.py).

Комментарий утверждает «the punctuation block that carries dashes and "№"», но
`GLYPH_RANGES = ["0-255", "256-511", "1024-1279", "8192-8447"]`, а U+2116 = 8470
— блок `8448-8703`. Сгенерированные ассеты подтверждают: `8448-8703.pbf` в
`assets/glyphs/*/` нет. Подписи «школа №5», «ГСК №12» (типичные `name` в OSM)
рендерятся с tofu. Остальное (кириллица с «ё», ₽, тире, «») покрыто верно.

**Что делать.** Добавить `"8448-8703"` в `GLYPH_RANGES`, пересобрать глифы.

---

## MINOR

### Движок Kotlin

- **`inFlight` ставится до `start()`** — [`MapSnapshotProvider.kt:137-142`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/map/MapSnapshotProvider.kt). Синхронный throw из `snapshotter.start()` оставляет `inFlight=true` без колбэка: все `capture` молча отдают null до `WEDGED_MS`. Обернуть `start` в runCatching со сбросом флага.
- **Опоздавший failure считается дважды** — `:154-167`: таймаут инкрементит `timeouts`, затем опоздавший failure-колбэк — ещё и `errors`. Только телеметрия. Инкрементить `errors` лишь при активном continuation, зеркально success-ветке.
- **`frameBitmap` не recycle'ится** — [`DashEngineController.kt:596`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt): каждый `startStream` аллоцирует ~631 КБ в нативной куче, старый бросается; `disconnect` не чистит. Та же аргументация про нативную кучу, что использована для снапшотов (`:851-857`), здесь не применена.
- **`startStream()` без обработки ошибок в коллекторе сессии** — [`DashEngineController.kt:311`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt): `assembleCurrent()`/`prepare()` могут бросить → исключение гасит `sessionWatchJob`, сессия висит в READY без кадрового цикла до 120-секундного give-up.
- **`publishState` читает `camInit`/`camHdg` с Main без `@Volatile`** — `:217-231`, `:936`: поля пишутся циклом на Default; редкие протухшие значения bearing в состоянии для Dart.
- **Неполная сигнатура кадра** — `:779-787`: нет флага `headingUp`, содержимого `routePoints`/`routeJam` (только `size`), координат назначения → оверлеи/камера протухают до `FORCE_REDRAW_MS` при переключении heading-up на курсе ~0°, пересчёте маршрута с тем же числом точек, смене точки назначения.
- **Мёртвые поля `OverlayRenderer.Frame`** — [`OverlayRenderer.kt:61-64`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/map/OverlayRenderer.kt): `maneuverText`/`remainingText`/`etaPrimary`/`etaSecondary` нигде не заполняются, `destName` не используется в `draw` — ETA-pill (`:275-298`) недостижим. Подключить или удалить.
- **Watchdog RX не гасит `ackCounterJob`** — [`DashSession.kt:346-351`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/DashSession.kt): несимметрично с `fail()`/disconnect; безобидно (лог раз в 60 с).

### Android-загрузчик

- **`getExternalFilesDir(null) == null` → относительный путь `maps`** — [`MapPackDownloader.kt:47`](android/app/src/main/kotlin/ru/snatchdash/app/MapPackDownloader.kt): `File(null, "maps")` ≡ `File("maps")` от cwd процесса; `mkdirs()` тихо не срабатывает, `hasRoomFor`/`installedFiles`/`partFile` работают на фиктивном пути без диагностики. Явно падать/сигналить.
- **`apply()` вместо `commit()` при записи pending после enqueue** — `:87-92`: смерть процесса между `enqueue` и флашем prefs = закачка-сирота (завершится в системе, `reconcile` её не увидит). Это единственная запись, потеря которой равна потере закачки.
- **Сироты `.part` никогда не подметаются** — нет стартового свipa «`.part` без записи в pending → удалить»; следствия гонок выше копятся по сотням МБ, невидимо для UI.
- **`hasRoomFor` по allocatable, DownloadManager пишет по usable** — `:58-67`: `getAllocatableBytes` включает чужой вытесняемый кэш; при реальной нехватке DM упадёт с `ERROR_INSUFFICIENT_SPACE` на ~90% — ровно то, что проверка должна предотвращать. Компромисс задокументирован в коде, стоит зафиксировать в спеке.
- **`delete()` не останавливает идущую закачку того же кода** — `:106`: системный загрузчик докачает и переустановит удалённый пак. В связке с пунктом экранов ниже (удаление доступно при `updating`) достижимо из UI.
- **Диск и binder на главном потоке** — `mapsDir().mkdirs()`/`listFiles()`/`dm.query` в `progress`/`installedFiles`/`delete`: мелочь, но на том же канале, ради которого завели worker ради хеша.

### Dart: данные/состояние

- **`PlatformException` из канала в `_tick`/init-микротаске без catch** — [`offline_maps_controller.dart:169-177`, `:288-306`](lib/state/offline_maps_controller.dart): `try` в `_tick` имеет только `finally`; бросок из `progress()`/`reconcile()` в колбэке `Timer.periodic` — unhandled async error каждые 700 мс; бросок в init-микротаске обрывает bootstrap (подхват завершённой закачки не случится).
- **Ветка `PackOutcome.failed` не чистит `_retries[code]`** — `:333-335`: устаревший счётчик доживает до следующего ручного скачивания, которое стартует с частично потраченным бюджетом ретраев.
- **`_dropRegistryRowsWithoutFiles` читает незагруженный реестр** — `:185-193`: `ref.read(installedPacksProvider)` синхронно возвращает `const []` (наполнение — в своей микротаске); проверка дрейфа молча превращается в no-op. `await …notifier.reload()` перед чтением или читать репозиторий напрямую.
- **`delete()` игнорирует булев результат натива** — `:260-263`: файл остался на диске (движок его видит), реестр говорит «не установлен» — возможен закрытый гейт навигации при отрисовываемой карте.
- **`_saveLocal` пишет `index.json` неатомарно** — [`map_manifest_api.dart:105-108`](lib/data/map_manifest_api.dart): обрыв записи портит хорошую локальную копию (гарантия «битый манифест не уничтожает хороший» есть только против ошибок разбора). Туда же: ошибка записи локальной копии после успешного парса (`:79-88`) превращает удачный fetch в fallback.
- **`readLocal()` зовёт `mapsDir()` вне try** — `:93-94`: `FileSystemException` (нет внешнего app-хранилища) всплывает в init-микротаску без catch → стартовый reconcile не запускается.
- **Fire-and-forget микротаски без catch** — `InstalledPacks.build` ([`offline_maps_controller.dart:29`](lib/state/offline_maps_controller.dart)), `_load` темы ([`map_theme_settings.dart:32`](lib/state/map_theme_settings.dart)); плюс гонка `_load` vs `select` темы (`:36-47`): поздно завершившийся `_load` перезапишет свежий выбор устаревшим из prefs.
- **`exitNavigation` — fire-and-forget `clearDestination()`** — [`route_controller.dart:70`](lib/state/route_controller.dart): Future не awaited, исключение канала станет unhandled.

### Dart: экраны/роутер

- **Пункт «Удалить» активен во время закачки обновления** — [`offline_maps_screen.dart:186-191`](lib/screens/offline_maps_screen.dart): удаление снимает файл и строку, но не отменяет закачку — по завершении `_harvest` вернёт пак обратно; «удалённое» всплывает снова.
- **Пикер карт открывается не на весь экран** — `:50-52`: `Navigator.of(context).push` пушит в навигатор ветки `StatefulShellRoute`, нижняя панель остаётся; спека требует полноэкранный попап (`rootNavigator: true` или GoRoute).
- **Кнопки диалога отмены загрузки не по спеке** — `:216-217`, `:366-367`: подтверждение подписано `actionDelete` («Удалить») в диалоге «Отменить загрузку» — семантически неверно, спека требует «Да»/«Нет».
- **Фоновая ошибка закачки теряется** — `:30-35`: snackbar едет на `ref.listen`, который не срабатывает на значении, установленном до подписки; поллер переживает экран, ошибка, случившаяся в его отсутствие, не показывается никогда.
- **Вспышка ложного «нет карт» при холодном старте** — `InstalledPacks.build` возвращает `const []` до чтения sqlite: Home/Settings/Dash на мгновение показывают заблокированную навигацию/«Ничего не скачано»/чип «Нет карт» при установленных паках; `hasInstalledPacksProvider` не различает «загружается» и «пусто».
- **`ManifestStatus.loading` не отображён** — строка поиска активна сразу, пикер при медленной сети открывается с ложным «Ничего не найдено».
- **`didChangeAppLifecycleState` перезапускает `downloadAndInstall()` на каждый `resumed`** — [`settings_screen.dart:66-75`](lib/screens/settings_screen.dart): включая возврат с системного диалога разрешений.
- **Жёстко зашитая строка `'SnatchDash logs'`** — [`settings_screen.dart:269`](lib/screens/settings_screen.dart): subject системного шеринга в двуязычном приложении.
- **Утечка `TextEditingController` в диалогах** — [`settings_screen.dart:274`, `:305`](lib/screens/settings_screen.dart): без `dispose()` (pre-existing паттерн).
- **`setState` после `await` без mounted** — [`maneuver_glyph_probe.dart:48`](lib/screens/debug/maneuver_glyph_probe.dart): debug-only.

### Python-конвейер

- **`fetch_boundaries.py` всегда выходит с кодом 0** — [`fetch_boundaries.py:309-326`](tools/planetiler/fetch_boundaries.py): «релация не найдена»/пустой ответ в ветке `--code` — всё равно 0; обёртка/CI видит успех при отсутствующей границе. Именно `--code` фигурирует в инструкции восстановления из `validate_packs.py`.
- **Неатомарная запись + «пропустить, если файл есть» консервируют битый полигон** — [`fetch_boundaries.py:231`, `:287`, `:257`](tools/planetiler/fetch_boundaries.py): `tree.write(out_path)` прямо в финальный `.osm`; убитый на полуслове процесс оставляет обрезанный файл, который следующий запуск пропустит. Временный файл + `os.replace` (как в `build_index.py`).
- **`build_index.py --only` перезаписывает частичным манифестом** — [`build_index.py:76-85`](tools/planetiler/build_index.py): затирает остальные включённые страны; пока включена одна `ru` — безвредно. Туда же: страна без паков молча выпадает из манифеста с exit 0.
- **Антимеридианный фолбэк возвращает геометрию во весь мир** — [`cut_packs.py:133-134`](tools/planetiler/cut_packs.py): `if not west or not east: return [("", geom)]` — bbox пака покрывает весь мир; сейчас недостижимо (Чукотка в `exclude`), но молчаливый путь отказа при её возврате.
- **`HttpSource` не проверяет, что сервер honour'ит Range** — [`validate_packs.py:140-146`](tools/planetiler/validate_packs.py): ответ 200 вместо 206 → весь пак (до 356 МБ) вычитывается в память; защита от смены хостинга.
- **`build_styles.py` качает `sprite@2x.png`, но не `@2x.json`** — [`build_styles.py:35`](tools/styles/build_styles.py): сейчас безвредно (`withPixelRatio(1f)`), при смене pixel ratio иконки пропадут; плюс `fetch()` без таймаута — потенциальное зависание CI.
- **`_pb_value` не декодирует sint64/float/double/bool** — [`validate_packs.py:425-435`](tools/planetiler/validate_packs.py): для используемых строковых полей безразлично, ловушка при расширении парсера.
- **Дубликаты релаций ISO3166-2 разрешаются непоследовательно** — [`fetch_boundaries.py:218` vs `:247-259`](tools/planetiler/fetch_boundaries.py): `fetch_single_code` берёт первую, `process_country` пишет все (последняя молча перетирает).

---

## Проверено — корректно (не трогать)

- **`pmtiles://file://<abs path>`** — форма URL верна для локального файла; паки
  во внешнем каталоге, не в ассетах — правильно (asset:// не даёт range-чтения).
- **Атомарная установка файла** — `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`
  внутри одного каталога; `dm.remove(id)` строго после rename (иначе удалил бы
  установленный файл); `generatedAt` читается до `forget`.
- **Правила бэкапа** — `<exclude domain="external" path="maps/"/>` в обоих
  `backup_rules.xml` (≤11) и `data_extraction_rules.xml` (12+), обе секции
  cloud/device-transfer; требование из CLAUDE.md выполнено.
- **Докачка** — `If-Match`+`Range` → 412 → `ERROR_CANNOT_RESUME` → `CONFLICT`,
  соответствует протоколу оптимистичной блокировки из спеки.
- **Осознанное отсутствие BroadcastReceiver** — хеш 356 МБ не влезает в лимит
  ресивера; завершённые при убитом приложении закачки подхватываются reconcile
  при старте (с оговоркой MAJOR-9).
- **Удалённые intent-фильтры (SEND/VIEW geo) — не регрессия**: приёмника не было
  ни в `MainActivity` (проверено на `main`), ни в зависимостях; манифест обещал
  ОС возможность, которой не существовало.
- **Сборка стиля** — размножение слоёв по пакам с группировкой слой-затем-пак,
  уникальные id, фоновые слои без `source` не дублируются, пустой набор паков →
  валидный стиль из одного background; `sprite`/`glyphs` из ассетов читаются
  офлайн; вход через `Style.Builder().fromJson`.
- **Снапшот** — все обращения к snapshotter на Main (колбэки MapLibre постит на
  main-looper); повторный `start()` законен (MapLibre обнуляет callback);
  опоздавший bitmap recycle'ится; поколение защищает от release чужого
  snapshotter; `Bitmap` после `drawBitmap` освобождается.
- **Камера/паддинг** — порядок `[left, top, right, bottom]` совпадает с
  `MapSnapshotter.setPadding` (MapLibre 13.6.0); математика pivot 0.66h и знаки
  pan верны.
- **Видео** — `NalProcessor`/`RtpPacketizer`: сбор AU, marker на последнем NAL,
  SPS+PPS+IDR расщепляются до FU-A, 3/4-байтовые start-коды, wrap sequence —
  верны. `DashAuth`: `MAX_SSID_BYTES = 85 = 117 − 32` верно.
- **RX-цикл**: `ensureActive()` + проверка идентичности сокета закрывают гонку
  teardown/READY; guard сокета в `startStreaming`; malformed input не роняет
  engine (локальный try/catch + `fail()`).
- **Каналы** — имена совпадают с Dart; `reconcile` на worker с ответом через
  `runOnUiThread`; маппинг outcome 1:1 с деградацией неизвестного в `failed`;
  `riderSpeed` в м/с сквозь все слои.
- **Миграция БД v1→v2** — единый DDL в `onCreate`/`onUpgrade`; кэширование
  открытия с сбросом при ошибке.
- **Парсер `index.json`** — соответствует схеме спеки пополево; `version >
  supported` — отказ; битый remote не затирает локальную копию; незнакомые поля
  игнорируются. `map_region_names` — 80 записей, fallback на code, тест пинает
  таблицу к манифесту.
- **Контроллер офлайн-карт** — `_ticking`-guard против наложения тиков;
  errorNonce для повторных одинаковых ошибок; проверка места до старта;
  `ENQUEUE_FAILED` → ошибка в UI; ретрай-бюджет снимается на всех терминальных
  путях, кроме `failed` (MINOR).
- **Гейт навигации** — оба входа на Home заблокированы без паков, подключение к
  дэшу не блокируется, обратная блокировка действий во время `navigating` — по
  спеке.
- **Локализация** — парность ключей RU/EN проверена: расхождений нет; удалённые
  ключи нигде не используются; plural-формы корректны для RU.
- **Регрессий от удаления wallpaper/location_parser нет** — ссылок не осталось
  (grep по `lib/`, `test/`, ассетам).
- **CSV-экспорт** — защита от формульных инъекций корректна.
- **Конвейер** — PMTiles v3-ридер (заголовок, gzip, дельта-офсеты, leaf), Hilbert
  tileid↔zxy (round-trip z0–14 чистый), MVT zigzag/protobuf — по спецификации;
  запись `index.json` атомарная (tmp+`os.replace`); `pack_plan` валидирует
  конфликты конфига; фикс subarea-релаций в `build_osm_xml` на месте;
  `validate_packs` даёт честные коды выхода для CI; антимеридианная логика
  согласована с проверкой комплектности.
