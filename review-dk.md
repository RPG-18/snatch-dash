# Ревью: офлайн-карты — полный проход по ветке

Ревью ветки `offline-tiles-implementation` целиком, относительно `main`
(123 файла, ~11 340 вставок). Предмет — весь контур офлайн-карт: конвейер
сборки паков (`tools/planetiler`), Android-загрузчик (`DownloadManager`),
Dart-слой данных/состояния/экранов и нативный рендер дэша на MapLibre поверх
скачанных `.pmtiles`.

**Метод.** Пять параллельных проходов (движок Kotlin, Android-загрузчик, Dart
данные/состояние, Dart экраны/роутер, Python-конвейер), затем независимая
сверка всех находок уровня CRITICAL/MAJOR по исходникам. Контракты сверялись со
[`spec/remote_map_server.md`](spec/remote_map_server.md) и
[`spec/drawing_from_local_tiles.md`](spec/drawing_from_local_tiles.md) — оба
документа очень точные, и код в основном им соответствует.

**Ограничение проверки.** `flutter`/`dart` не на `PATH`, поэтому `flutter
analyze` / `flutter test` / сборку APK не перезапускал — ревью статическое.
Предыдущие проходы ([`review.md`](review.md)) фиксировали: analyze чистый, тесты
65/65, Kotlin-тесты 21/22, APK собирается. На устройстве не запускалось ничего —
весь класс «проверяется глазами на 526×300» (наклон камеры, читаемость подписей,
логотип/атрибуция, держит ли offscreen 4 fps) остаётся за MVP.

**Итог:** 1 CRITICAL, 5 MAJOR, 31 MINOR. Блокеров «ничего не работает» нет:
`pmtiles://file:///`-форма, атомарная установка, правила бэкапа и сборка стиля
проверены и верны (см. «Проверено — корректно» в конце). Все серьёзные находки —
контракты с конкурентностью и таймингом, а не с внешними API.

---

## CRITICAL

### 1. Дедлайн снапшота не ограничивает зависание карты — в диапазоне 500 мс…5 с карта замерзает целиком

[`MapSnapshotProvider.kt:121-133`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/map/MapSnapshotProvider.kt) и `:140-167`:

```kotlin
if (inFlight) {
    if (now - inFlightSince < WEDGED_MS) {
        // Still out, but not for long enough to call it dead. Skip this frame …
        return@withContext null
    }
    abandoned++
    runCatching { snapshotter.cancel() }
    inFlight = false
}
```

**Суть.** После того как `withTimeoutOrNull(deadlineMs)` выходит по таймауту,
флаг `inFlight` остаётся `true` — его сбрасывает только колбэк `start()`, а он
придёт, когда MapLibre наконец досчитает снапшот. Поэтому каждый следующий вызов
`capture()` (кадр каждые 250 мс) попадает в `if (inFlight)` и мгновенно
возвращает `null` вплоть до `WEDGED_MS` = 5 с. Кадровый цикл честно крутится,
но кадр не перерисовывается: на приборке висит последний удачный кадр.

**Почему это важно.** Док-комментарий самого класса обещает: «Waiting without
one means a single snapshot that never completes freezes the dash» — ради этого
дедлайн и вводился. Но дедлайн ограничивает только то, сколько *цикл* ждёт
(500 мс), а не сколько *карта* простаивает. В диапазоне 500 мс — 5 с (ровно тот
режим «карта еле тянет»: тяжёлый стиль, медленная SD, много паков) райдер видит
замершую карту, неотличимую от обрыва связи — то самое, против чего дедлайн
должен был защищать. В штатном случае (~100–600 мс на снапшот) цена — 1–2
пропущенных кадра, не страшно; патологический случай не ограничен ничем, кроме
`WEDGED_MS`.

**Контекст.** Решение «по дедлайну не отменять, а обгонять» — осознанное и
правильное (отмена теряет битмап в нативной куче). Ограничение «снапшоттер
обслуживает один запрос» — тоже документировано. Проблема не в этих решениях, а
в том, что их комбинация даёт незадокументированный хвост: дедлайн не решает
заявленную задачу. Спека сама называет запасной путь — держать MapLibre в
`SurfaceTexture` — и фиксирует порог перехода «до замеров, а не после», но
текущие счётчики (`timeouts`/`abandoned`) не показывают именно эту величину
(«сколько кадров подряд ушло в `null` из-за `inFlight`»).

**Что делать.** Как минимум — считать и логировать, сколько кадров пропущено по
`inFlight`, отдельно от `timeouts`; тогда станет видно, что дедлайн не
срабатывает, и порог для `SurfaceTexture` можно принять по данным. По существу —
либо отменять по дедлайну (приняв утечку битмапа, которая уже считается
приемлемой для `WEDGED_MS`), либо double-buffer (второй снапшоттер), либо
снять флаг `inFlight` по таймауту и отдавать опоздавший битмап на `recycle`
по поколению, а не по общему флагу.

---

## MAJOR

### 2. `disconnect()` освобождает энкодер, не дождавшись кадрового цикла

[`DashEngineController.kt:338`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt) против `:345`:

```kotlin
streamJob?.cancel(); streamJob = null   // :338 — без cancelAndJoin()
…
encoder?.release(); encoder = null      // :345 — на главном потоке
```

`startStream()` ту же гонку **уже чинил** — там стоит `streamJob?.cancelAndJoin()`
(`:590`) и комментарий, почему без join нельзя: старый цикл успевал позвать
`renderFrame`/`drain` на уже освобождённом `MediaCodec`, что давало шторм
`IllegalStateException` и бесполезный пересбор энкодера. А `disconnect()`
по-прежнему отменяет цикл без join: `streamJob` крутится на `Dispatchers.Default`,
`cancel()` кооперативен, и цикл останавливается только на следующей точке
приостановки (`delay` / `capture`). В окне между `cancel()` и фактической
остановкой главный поток успевает `encoder.release()` → `MediaCodec.stop()`/
`release()` гоняется с `renderFrame`/`drain`.

**Что делать.** Тот же приём, что в `startStream()`: перед `encoder?.release()`
дождаться `streamJob?.cancelAndJoin()` (в suspend-контексте), либо вынести teardown
в корутину, которая join'ит цикл до release. Дополнительно — защититься от
реентерабельности с `giveupJob`/watch-джобами.

### 3. Пейсинг кадра: после ожидания снапшота ещё и целый `delay(interval)`, поэтому целевой fps недостижим и RTP-PTS дрейфует

[`DashEngineController.kt:643-644`, `:660`, `:718`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt):

```kotlin
frameIntervalMs = 1000L / (if (camMoving) FPS_MOVING else FPS_IDLE)
tick(frameIntervalMs)            // ждёт снапшот до deadlineMs
…
videoPtsMs += frameIntervalMs     // PTS шагает на ЗАДУМАННЫЙ интервал
…
delay(frameIntervalMs)            // плюс ещё целый интервал после
```

Тело цикла = `tick` (до 500 мс на снапшот) + encode/drain + `delay(interval)`.
Итоговый период — `tick_time + interval`, а не `interval`. При 4 fps
(`interval`=250 мс) и снапшоте ~100 мс получается ~350 мс/кадр (~2.85 fps),
при 200 мс — ~2.2 fps. При этом `videoPtsMs` шагает на задуманный интервал, а
кадры реально уходят медленнее — презентационные метки RTP расходятся с
реальным темпом отправки (для display-only дэша это скорее косметика, но
`FPS_MOVING`/`FPS_IDLE` не выполняются, и телелеметрия `frames=X/expected`
читается как вечный недобор кадров даже в идеальных условиях).

**Что делать.** Пейсинг по дедлайну: мерить `elapsed` от верха итерации и
`delay((frameIntervalMs - elapsed).coerceAtLeast(0))`; приращение PTS брать от
того же измеренного интервала. Заодно комментарий в `:630-636` («интервал и есть
длина кадра») станет верным — сейчас он противоречит фактическому `delay` после
тела.

### 4. Гонка: `reconcile` на worker-потоке против `start`/`cancel`/`delete` на главном — можно установить недокачанный пак

[`MapPackDownloader.kt:74` (`start`), `:98` (`cancel`), `:171-215` (`install`)](android/app/src/main/kotlin/ru/snatchdash/app/MapPackDownloader.kt) и диспетчеризация
[`MainActivity.kt:128-136`](android/app/src/main/kotlin/ru/snatchdash/app/MainActivity.kt).

`reconcile()` (в нём — `sha256Of` на секунды и `Files.move`) крутится на
однопоточном `mapsWorker`, а `start`/`cancel`/`delete`/`progress`/`installedFiles`
— на главном потоке обработчика канала. `install()` хеширует `.part`, **не
перепроверяя**, что это всё ещё тот же download для `code`. Если райдер во время
хеширования перезапускает закачку того же кода, новый `start()` зовёт `cancel()`
(удаляет `.part` и pending), закачка пересоздаёт `maps/<code>.pmtiles.part`, а
worker затем `Files.move`'ом переименовывает этот **недокачанный** файл в
`<code>.pmtiles`. Движок, перечисляющий `*.pmtiles`, прочитает битый/обрезанный
пак; новая закачка продолжает писать в уже переименованный дескриптор.

Окно узкое (перезапуск закачки того же кода именно в секунды хеширования), но
общий дефект — разделяемое изменяемое состояние (`pending`-prefs и файлы
`.part`) без синхронизации между двумя потоками — реален и даёт и более простые
проявления (см. MAJOR → MINOR ниже: исключение из `Files.move` рвёт весь батч).

**Что делать.** Прогонять `start`/`cancel`/`delete` через тот же
single-thread-исполнитель, либо в `install()` перед `move` проверять, что текущий
`pending` id для `code` всё ещё равен обрабатываемому и размер `.part` сходится с
манифестом.

### 5. Выход из навигации оставляет «призрачный» маршрут в `RouteController`

[`lib/screens/dash_screen.dart:161`](lib/screens/dash_screen.dart) и
[`lib/state/route_controller.dart:67-71`](lib/state/route_controller.dart):

```dart
void exitNavigation() {
  _navLoop?.stop();
  _navLoop = null;
  DashEngine.instance.clearDestination();
  // state.destination / state.route НЕ сбрасываются
}
```

FAB вызывает `exitNavigation()` и `context.go('/home')`. `state` сохраняет
`destination` + `route`. Повторный вход на `/home/dash` пересобирает экран с
этими значениями: рисуется старый полилайн и пин назначения, висит кнопка
«выйти из навигации», хотя нативно `navigating == false` и `remainingKm == null`.
Вводящий в заблуждение «призрачный» маршрут; райдер может «выйти» ещё раз.

**Что делать.** В `exitNavigation()` сбросить состояние, например
`state = const RouteState();` (`copyWith` использует `??`, так что им поля не
обнулить — нужно именно присваивание нового состояния).

### 6. `upload.sh` публикует манифест раньше паков — боевой CDN висит битым полчаса

[`tools/planetiler/upload.sh:19-22`](tools/planetiler/upload.sh):

```bash
yc --profile="$PROFILE" storage s3 cp --recursive "$LOCAL_DIR" "s3://${BUCKET_NAME}/${PREFIX}"
```

Рекурсивная заливка идёт по алфавиту, `index.json` уезжает **первым**. Всё время
публикации (~30 мин на 4.45 ГБ) манифест обещает паки с новыми `sha256`, которых
ещё нет. Это ровно тот сбой, что README фиксирует за корпусом 2026-09-02, и
README сам предписывает фикс («сначала `ru/`, потом манифест отдельной командой»)
— но скрипт не менялся.

**Что делать.** Залить всё, кроме `index.json`, затем манифест отдельной
финальной командой; добавить проверку наличия `yc`/`aws` и `Content-Type`, чтобы
`.json` отдавался как `application/json`.

---

## MINOR

### Движок Kotlin

- **M7.** `frameBitmap` не `recycle()` между переподключениями —
  [`DashEngineController.kt:596`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt). Каждый `startStream` аллоцирует свежий 526×300 ARGB_8888 (~631 КБ, нативная куча) и бросает старый; `disconnect` не чистит. Не покадровая утечка, но серия «Send to Dash»/переподключений копит нативную память до финализатора. Фикс: `runCatching { frameBitmap?.recycle() }` перед переаллокацией и в `disconnect`.

- **M8.** `snapshot.pixelForLatLng()` зовётся не на главном потоке —
  [`OverlayRenderer.kt:874`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/map/OverlayRenderer.kt) из `redrawFrame` на `Dispatchers.Default`, тогда как `MapSnapshotProvider.kt:33-35` декларирует инвариант «всё здесь — на главном». `MapSnapshotter` — `@UiThread`; `pixelForLatLng` — `external` (JNI) по `nativePtr`. Скорее всего безопасно (неизменяемые данные пост-захвата), но инвариант молча нарушен. Фикс: проектировать точки маршрута внутри `capture` на `Main` и отдавать готовый массив, оставив Default-потоку только композицию растра — либо задокументировать безопасность.

- **M9.** `startStream()` без обработки ошибок — исключение убивает наблюдателя сессии и оставляет движок в `READY` — [`DashEngineController.kt:311`, тело `:549-624`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt). `styleAssembler.assembleCurrent()` (чтение ассетов/листинг) или `snapshots.prepare()` (`MapLibre.getInstance`, `Style.Builder.fromJson`) могут бросить; исключение всплывает из коллектора и гасит `sessionWatchJob`, а `session.state == READY` без кадрового цикла. Самоизлечение — только через 120-секундный `giveupJob`. Фикс: `try/catch` вокруг `startStream()` с уводом в `onError`/`disconnect`.

- **M10.** Отложенный release снапшоттера отменяется при отсоединении движка —
  [`DashEngineController.kt:352`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt) (`scope.launch { snapshots.release(generation) }`) против [`OpendashDashEnginePlugin.kt:189-191`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/OpendashDashEnginePlugin.kt) (`dispose()` → `job.cancel()` синхронно сразу после). Release-корутина может быть отменена до исполнения — утечка `MapSnapshotter` до смерти процесса. Фикс: освобождать снапшоттер синхронно в `disconnect()` (там только `cancel()` + обнуление поля, безопасно на Main).

- **M11.** Снапшот, промахнувшийся по дедлайну и затем упавший, считается дважды — [`MapSnapshotProvider.kt:154-167`](packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/map/MapSnapshotProvider.kt). После таймаута (`timeouts++`) опоздавший failure-колбэк всё равно делает `errors++` (`failed` читается только до). Искажение телеметрии, не баг рендера. Фикс: инкремент `errors` только при активном континуейшене, зеркально success-ветке.

### Android-загрузчик

- **M12.** Одно исключение рвёт весь батч `reconcile` — [`MainActivity.kt:129-135`](android/app/src/main/kotlin/ru/snatchdash/app/MainActivity.kt) ловит вокруг всего `reconcile()`, а `Files.move(…, ATOMIC_MOVE, …)` в [`MapPackDownloader.kt:195-200`](android/app/src/main/kotlin/ru/snatchdash/app/MapPackDownloader.kt) может бросить (`AtomicMoveNotSupportedException` на файловых системах без атомарного rename, или любой `IOException`). Один битый пак → один generic `RECONCILE_FAILED`, результаты остальных готовых паков теряются, а `pending`-запись виновника остаётся и бросает на каждом следующем тике (поллер не успокаивается). Фикс: per-code `try/catch` в `install()`, и при `AtomicMoveNotSupportedException` — фолбэк на неатомарный `Files.move(…, REPLACE_EXISTING)`.

- **M13.** `start()` полагается на скрытую связку «`maps/` уже создан» — [`MapPackDownloader.kt:74-86`](android/app/src/main/kotlin/ru/snatchdash/app/MapPackDownloader.kt). `setDestinationInExternalFilesDir` создаёт только базовый `getExternalFilesDir`, а подкаталог `maps/` появляется лишь потому, что `hasRoomFor()` зовёт `mapsDir()` (с `mkdirs()`) и Dart по совпадению вызывает `hasRoomFor` перед `start`. Стоит вызвать `start()` без предварительного `hasRoomFor`/`mapsDir` — у `DownloadManager` нет родителя, и закачка падает без внятной причины. Фикс: `mapsDir()` в начале `start()`.

- **M14.** `getExternalFilesDir(null)` не проверяется на `null` — [`MapPackDownloader.kt:47`](android/app/src/main/kotlin/ru/snatchdash/app/MapPackDownloader.kt). `File(context.getExternalFilesDir(null), MAPS_DIR)` даёт NPE при отмонтированном внешнем носителе; спека явно требует обрабатывать (`«Может вернуть null… обрабатывать»`). `hasRoomFor` случайно глотает (возвращает `true`), но `installedFiles`, `start→cancel→partFile`, `delete` уронят обработчик канала на главном потоке. Фикс: null-check в `mapsDir()` и понятная ошибка/пустой результат.

- **M15.** `delete()` не останавливает идущую закачку того же кода — [`MapPackDownloader.kt:106`](android/app/src/main/kotlin/ru/snatchdash/app/MapPackDownloader.kt) удаляет только `.pmtiles`, не трогая `pending` и не снимая `dm`-закачку. Если пак удалить посреди загрузки, системный загрузчик докачает и переустановит его. Низкая вероятность (интерфейс разводит действия), но нативный API это допускает — а в связке с M16 (пункт «удаление активно во время обновления») становится достижимым. Фикс: `delete` → заодно `cancel(code)` либо защита инварианта на Dart-стороне.

- **M16.** Молчаливое удаление share/deep-link точек входа — [`AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml). Дифф убирает intent-фильтры `ACTION_SEND text/plain` и `ACTION_VIEW` (`maps.google.com`/`goo.gl`/`geo:`), и ни одна активность их не переобъявляет. Почти наверняка намеренно (удалены `LocationParser`, `SharedLocation.url`/`needsExpansion` — приложение больше не принимает расшаренные точки), но это функциональный регресс, если не планировался. Подтвердить намерение; если осознанно — подчистить `queries`/документацию.

### Dart: данные/состояние

- **M17.** `_dropRegistryRowsWithoutFiles` на холодном старте читает пустой реестр — [`offline_maps_controller.dart:185-193`](lib/state/offline_maps_controller.dart) берёт `ref.read(installedPacksProvider)`, который синхронно возвращает `[]` (наполнение — в `Future.microtask(reload)`). Если экран офлайн-карт — первый потребитель провайдера, проверка дрейфа реестр/диск проходит по пустому списку и ничего не чистит. Фикс: `await ref.read(installedPacksProvider.notifier).reload()` перед чтением.

- **M18.** Ветка `PackOutcome.failed` не чистит `_retries[code]` — [`offline_maps_controller.dart:333-335`](lib/state/offline_maps_controller.dart). Неудачная закачка терминальна и забыта на нативной стороне, но устаревший счётчик доживает до следующего ручного ре-даунлоада, который стартует с частично потраченным бюджетом ретраев. Фикс: `_retries.remove(result.code)` в `failed`-ветке.

- **M19.** `_saveLocal` пишет `index.json` на месте, не атомарно — [`map_manifest_api.dart:105-108`](lib/data/map_manifest_api.dart) (`writeAsString(flush: true)`). Обрыв/частичная запись портят хорошую локальную копию, и `readLocal` сочтёт её отсутствующей — подрывает заявленное «битый манифест не уничтожает хороший» (гарантия есть только против ошибок разбора, не против рваной записи). Фикс: писать во временный файл в том же каталоге и `File.rename`.

- **M20.** `delete()` игнорирует булев результат нативного удаления — [`offline_maps_controller.dart:260-263`](lib/state/offline_maps_controller.dart). Если `MapPackDownloader.delete` вернул `false`, файл остаётся на диске (движок его перечислит и отрисует), а реестр говорит «не установлен» — `hasInstalledPacksProvider` может сброситься в `false` и закрыть гейт навигации на Главной при отрисовываемой карте. Фикс: удалять строку реестра только при `true`, либо сверять через `installedFiles()`.

- **M21.** Гонка `_load` vs `select` в теме — [`map_theme_settings.dart:36-47`](lib/state/map_theme_settings.dart). `_load` (через `Future.microtask`) не упорядочен с `select`; если `select` отработает до завершения `_load`, последний перезапишет выбор устаревшим значением из префов (запись уже ушла). Маловероятно, но реально. Фикс: guard по поколению/флагу, либо `await _load` перед допуском `select`.

- **M22.** `readLocal()` зовёт `mapsDir()` вне `try` — [`map_manifest_api.dart:93-94`](lib/data/map_manifest_api.dart) + [`offline_maps_controller.dart:213-221`, `:169-177`](lib/state/offline_maps_controller.dart). `FileSystemException` (например, `getExternalStorageDirectory() == null`) всплывает: `refresh()` ловит только `ManifestVersionUnsupported`, и исключение выходит в тело `Future.microtask` в `build()` без `try/catch`. Итог: на устройстве без внешнего app-хранилища стартовый reconcile не запускается, и завершившаяся при закрытом приложении закачка не устанавливается. Фикс: `try/catch` вокруг тела стартовой микрозадачи (или хотя бы фолбэка `readLocal`).

- **M23.** Fire-and-forget `Future.microtask` без обработки ошибок — [`offline_maps_controller.dart:29`](lib/state/offline_maps_controller.dart), [`map_theme_settings.dart:32`](lib/state/map_theme_settings.dart). Бросок из чтения БД/`SharedPreferences.getInstance()` становится необработанным асинхронным исключением (проглатывается zone-логгером). Фикс: `try/catch` внутри микрозадачи.

- **M24.** `state.progress` снимается до `_harvest` — [`offline_maps_controller.dart:294`](lib/state/offline_maps_controller.dart). Пак, только что установившийся на нативной стороне, ещё один тик (~700 мс) висит в `progress` как «обновляется» (спиннер/стоп) при уже установленном. Косметика. Фикс: пересобирать `progress` свежим вызовом после `_harvest`.

### Dart: экраны/роутер

- **M25.** `ref` после `await showDialog` без mounted-гварда — [`offline_maps_screen.dart:222`, `:248`](lib/screens/offline_maps_screen.dart). `_confirmCancel`/`_confirmDelete` зовут `ref.read(...).cancel/delete` после `await`; при размонтировании в этот промежуток `ref.read` на disposed `WidgetRef` бросит. Практически маловероятно (тайл не исчезает, пока открыт его диалог), но это ровно тот паттерн async-разрыва, что стоит закрыть. Фикс: захватить notifier до `await` либо `context.mounted`.

- **M26.** Пункт «Удалить» активен во время закачки обновления — [`offline_maps_screen.dart:186-191`](lib/screens/offline_maps_screen.dart). `PopupMenuButton` предлагает удаление при `updating == true`; удаление снимает файл и строку реестра, но не отменяет идущую закачку, и по завершении `_harvest` (`PackOutcome.installed`) вернёт пак обратно — «удалённое» всплывает снова. Фикс: скрывать/деактивировать удаление при `updating`, либо `delete()` с отменой закачки кода.

- **M27.** `setState` после `await` без mounted — [`maneuver_glyph_probe.dart:48`](lib/screens/debug/maneuver_glyph_probe.dart). Debug-only (`kDebugMode`), низкий приоритет. Фикс: `if (!mounted) return;`.

- **M28.** Фоновая ошибка закачки молча теряется — [`offline_maps_screen.dart:30-35`](lib/screens/offline_maps_screen.dart). Снэкбар с ошибкой едет на `ref.listen` по `(lastError, errorNonce)`, который срабатывает только на изменение. Поллер контроллера живёт и после ухода с экрана: ошибка, записанная в состояние в отсутствие экрана, на возврате не показывается и не чистится через `errorShown()`. Фикс: при build (post-frame) показывать и гасить висящую `lastError`, либо вести ошибки через очередь/`StreamController`.

- **M29.** `TextEditingController` в диалогах не освобождается — [`settings_screen.dart:274`, `:305`](lib/screens/settings_screen.dart). По одному контроллеру на открытие диалога SSID/пароля, без `dispose()`. Небольшая утечка на диалог. Фикс: `dispose` в `StatefulBuilder`/обёртке, либо содержимое диалога — `StatefulWidget`, владеющий контроллером.

- **M30.** `didChangeAppLifecycleState` повторно гоняет `downloadAndInstall()` — [`settings_screen.dart:74-76`](lib/screens/settings_screen.dart). На каждый `resumed` (включая отклонение системного экрана разрешений или возврат откуда-либо) перезапускается флоу установки. Статус гейтит, но возможны нежелательные повторы. Фикс: одноразовый флаг или реакция только на переход из `needsInstallPermission`.

- **M31.** Жёстко зашитая строка `'SnatchDash logs'` — [`settings_screen.dart:269`](lib/screens/settings_screen.dart). Приложение двуязычное; строка уходит в системный шеринг и не локализована (бренд — ок, «logs» — нет). Фикс: ключ в `AppLocalizations`.

### Python-конвейер

- **M32.** `fetch_boundaries.py` всегда выходит с кодом 0 — [`fetch_boundaries.py:309-312`, `:325-326`](tools/planetiler/fetch_boundaries.py). Даже когда ничего не записано/релация не найдена, процесс отдаёт 0, и оборачивающий скрипт/CI видит успех. Фикс: ненулевой код при `skipped > 0` (или хотя бы когда ничего не записано).

- **M33.** Неатомарная запись + «пропустить, если файл есть» консервируют битый полигон — [`fetch_boundaries.py:231`, `:287`, `:257`](tools/planetiler/fetch_boundaries.py). `tree.write(out_path)` пишет прямо в финальный `.osm`; убитый на полуслове процесс оставляет обрезанный файл, который следующий запуск пропустит (`if out_path.exists() and not force: continue`). Фикс: временный файл в том же каталоге + `os.replace` (как уже делает `build_index.py`).

- **M34.** Антимеридианный фолбэк возвращает геометрию во весь мир — [`cut_packs.py:133-134`](tools/planetiler/cut_packs.py). `if not west or not east: return [("", geom)]` отдаёт неразрезанную геометрию; если она шире 350°, bbox пака покрывает весь мир — ровно то, что функция призвана не допустить. Сейчас недостижимо (Чукотка в `exclude`), но это молчаливый путь отказа при возврате Чукотки. Фикс: raise/log вместо возврата целой геометрии.

- **M35.** `build_index.py --only <iso>` перезаписывает частичным манифестом — [`build_index.py:76-85`](tools/planetiler/build_index.py). `--only` пишет `index.json` только из выбранного подмножества, затирая остальные включённые страны (их паки остаются в `out/`, но исчезают из манифеста; сиротская проверка не предупредит). Пока включена одна `ru` — безвредно, но латентная грабля. Фикс: при `--only` не перезаписывать манифест, опускающий включённые страны, либо мёржить.

- **M36.** Диапазон глифов `8192-8447` не содержит `№` — [`build_styles.py:40`](tools/styles/build_styles.py). `№` = U+2116 (0x2100–0x21FF = 8448–8703), то есть за пределами блока пунктуации 8192–8447. Подписи с «№» (частые в русских POI, «Школа № 5») получат tofu-квадрат. Фикс: добавить `8448-8703` в `GLYPH_RANGES` (или поправить комментарий).

- **M37.** Дубликаты релаций ISO3166-2 разрешаются непоследовательно — [`fetch_boundaries.py:218` vs `:247-259`](tools/planetiler/fetch_boundaries.py). `fetch_single_code` берёт первую из нескольких, а `process_country` пишет все совпавшие в один файл (последняя молча перетирает). Для редкого случая двух релаций с одним кодом пути дают разный результат. Фикс: warn-and-skip на уже скачанный код, зеркально `fetch_single_code`.

---

## Проверено — корректно (не трогать)

Коротко, чтобы зафиксировать, что серьёзное сверено и ок (в основном — контракты с MapLibre, `DownloadManager` и схемой корпуса):

- **`pmtiles://file://…`** — форма с двумя слешами после `file:` верна для локального файла; `pmtiles://asset://` не работает (байтовые range), паки лежат во внешнем каталоге — так и надо. Бага «одна косая» из прошлого ревью здесь нет.
- **Атомарная установка** — `Files.move(part, pack, ATOMIC_MOVE, REPLACE_EXISTING)` внутри `maps/`, `dm.remove(id)` только после переименования (иначе удалил бы установленный файл). Верно.
- **Правила бэкапа** — `<exclude domain="external" path="maps/" />` в обоих `backup_rules.xml` и `data_extraction_rules.xml` корректно исключает `getExternalFilesDir(null)/maps/` и из облачного бэкапа (квота 25 МБ), и из device-transfer; атрибуты в манифесте указывают на правильные ресурсы, разбивка Android 11− / 12+ верна.
- **Докачка** — `DownloadManager` при резюме шлёт `If-Match` + `Range`, устаревший ETag даёт `412` → `STATUS_CANNOT_RESUME`, который в `reconcile` мапится в `Outcome.CONFLICT`, а не в пользовательскую ошибку. Соответствует протоколу оптимистичной блокировки.
- **Сборка стиля** — размножение слоёв по числу паков, подстановка `source`, уникальные `id`, порядок по типу слоя, `background` без `source` ровно один раз; `sprite=asset://sprites/…`, `glyphs=asset://glyphs/{fontstack}/{range}.pbf` лежат в ассетах и читаются офлайн; вход через `Style.Builder().fromJson(...)` (не deprecated `withStyleJson`).
- **Снапшот** — `Bitmap` после `drawBitmap` ресайклится; порядок recycle/`pixelForLatLng` безопасен (`pixelForLatLng` читает только неизменный `nativePtr`).
- **Камера/паддинг** — знаки top/bottom укладывают цель в `NAV_PIVOT_Y` и инвертируют пан; лестница зумов в единицах MapLibre.
- **Видео** — `NalProcessor`/`RtpPacketizer` (FU-A, SPS/PPS-бадл, marker на последнем NAL) корректны; `DashAuth` ≤ 117 байт (лимит RSA-1024 PKCS#1 v1.5).
- **Конвейер** — PMTiles v3-читатель, delta-декодинг офсетов, Hilbert `tileid↔zxy`, MVT zigzag/protobuf — сходятся со спецификацией; `index.json`-схема пополево совпадает с Dart-парсером; фикс subarea-релаций в `build_osm_xml` на месте (см. [[planetiler-subarea-boundary-bug]]); `pack_plan`/`check_completeness`/`check_cities` корректны.
- **Каналы** — имена `ru.snatchdash.app/maps` и `ru.snatchdash.app/updater` совпадают с Dart; `reconcile` вынесен с главного потока с ответом через `runOnUiThread`; маппинг `Number.toLong()` ↔ Dart `int` верен; `riderSpeed` публикуется в м/с и совпадает с `dash_engine_state.dart`.
