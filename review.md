# Ревью: многопоточность и гонки

Область: нативный движок дэша (`packages/opendash_dash_engine/android`), хост-часть
Android (`android/app`), асинхронный код на Dart (`lib/`).

Про Dart: изолят один, «настоящих» гонок за память там нет — но каждый `await`
это точка, где состояние может измениться под ногами. Такие места отмечены
отдельно как *async-гонки*.

Многое в коде уже сделано аккуратно (`@Volatile` на кросс-поточных полях,
проверка identity сокета в `endLink`, поколения снапшоттера, generation-guard'ы в
Riverpod-нотифаерах, однопоточный `mapsWorker`). Ниже — то, что не покрыто.

---

## Критично

### 1. `DashSession.fail()` рвёт чужую сессию — две `runSession` живут одновременно

`packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/DashSession.kt:614-621`

`endLink()` (:397) специально проверяет `if (socket === sock)`, прежде чем занулить
поле. `fail()` такой проверки не делает — он безусловно отменяет `rxJob`,
`heartbeatJob`, `mediaInfoJob`, `ackCounterJob`, закрывает `socket` и ставит
`ERROR`. И `connect()` (:166-170) не отменяет предыдущий `sessionJob`, а просто
перезаписывает ссылку на него.

Конкретный сценарий (весь он — штатный путь ретрая из
`DashEngineController.kt:341-349`):

1. `connect()` → `sessionJob` #1 → сокет `S1`, `runSession` #1 висит в
   `while (!authConfirmed && ...) delay(100)` — окно до `AUTH_TIMEOUT` = 15 с (:261).
2. `rxJob` ловит битый пакет → `dispatchIncoming` бросает → `fail()` → `ERROR`.
   `runSession` #1 при этом **не отменена** — `fail()` её не трогает.
3. `sessionWatchJob` видит `ERROR`, WiFi ещё `CONNECTED`, `authRetries < 4` →
   `delay(1500)` → `session.connect(...)`.
4. Гард `if (_state.value != IDLE && != ERROR) return` пропускает (состояние
   `ERROR`), `sessionJob` #2 перезаписывает ссылку на живую #1. Сокет `S2`,
   `authConfirmed = false`, свои `rxJob`/`heartbeatJob`/`ackCounterJob`.
5. У `runSession` #1 остаётся ~13.5 с её дедлайна. Если #2 не успевает
   аутентифицироваться за это время — #1 вызывает `fail("Auth timed out…")` и
   **закрывает `S2`, отменяет `rxJob`/`heartbeatJob`/`ackCounterJob` сессии #2 и
   ставит `ERROR`**. Сессия #2 уничтожена чужим таймаутом, её RX-цикл мёртв, она
   тоже упрётся в свой таймаут — и так до исчерпания `MAX_AUTH_RETRIES`.

Ирония в том, что срабатывает это ровно тогда, когда ретрай и нужен: пока дэш
отвечает быстро, #2 успевает и всё выглядит нормально (хотя #1 всё равно молча
шлёт `enterNavMode` в закрытый `S1` — сообщения проглатываются в
`DashSocket.send`).

Починка: передать в `fail()` сокет, за который он отвечает, и проверять identity
как в `endLink`; и/или в `connect()` делать `sessionJob?.cancel()` перед
запуском новой (лучше — `cancelAndJoin` из suspend-контекста), чтобы гард
`!= IDLE && != ERROR` не пропускал вторую `runSession` поверх живой первой.

---

## Серьёзно

### 2. Поля `rxJob` / `heartbeatJob` / `ackCounterJob` пишутся с IO, читаются с Main без `@Volatile`

`DashSession.kt:77`, `:80`, `:104`; запись — `DashSession.kt:316`, `:513`, `:537` (все внутри
`runSession`, то есть на `Dispatchers.IO`), чтение — `disconnect()` на `:211-212`
(главный поток).

Ссылки на эти три Job'ы обычные, не `@Volatile`, и между потоком IO, который их
записал, и главным, который их отменяет, нет ни одного отношения
happens-before. По модели памяти `disconnect()` вправе увидеть там `null` и
ничего не отменить.

Последствия неодинаковые:

- `rxJob` — самовосстанавливается: сокет закрыт, `receive()` бросает, `endLink`
  доводит дело до конца.
- `heartbeatJob` (`:513-521`) — цикл `while (isActive)` **без проверки состояния**.
  Не отменённый, он остаётся сиротой и раз в секунду шлёт в никуда до отмены
  всего scope (то есть до отсоединения плагина).
- `ackCounterJob` (`:537-550`) — то же самое: на не-`STREAMING` он делает
  `continue`, а не выходит.

Остальные четыре (`projHbJob`, `routeCardJob`, `navInfoJob`, `mediaInfoJob`)
пишутся из `startStreaming()` с главного потока — там этой проблемы нет.

Починка: `@Volatile` на три поля (дёшево), либо запускать эти корутины оттуда же,
откуда их отменяют.

### 3. `startStream` не джойнит старый цикл кадров, если между ними был `disconnect()`

`DashEngineController.kt:629` против `:362`

Комментарий на `:578-587` обещает: «previous frame loop is *joined* before this
one installs a new encoder». Но `disconnect()` делает `streamJob?.cancel(); streamJob = null`
(:362) — отменяет кооперативно и **тут же теряет ссылку**. Следующий
`startStream()` видит `streamJob == null`, `cancelAndJoin()` не джойнит ничего, и
дальше на `:642` присваивает новый энкодер.

Если старый цикл в этот момент ещё разматывается на `Dispatchers.Default`, его
`finally` (:810-811) сделает `encoder?.release()` — по **новой** ссылке — и
`encoder = null`. Свежесозданный `MediaCodec` освобождён, поле обнулено, в цикле
кадров `val enc = encoder` навсегда `null`. На дэше замерший кадр до конца
поездки, без единой ошибки в логе: сессия дошла до `STREAMING`, таймер сдачи снят.

Вероятность низкая — окно размотки старого цикла порядка сотен миллисекунд, а
переподключение после `disconnect()` требует WiFi + хендшейк, то есть секунды. Но
инвариант, на который явно ссылаются комментарии в `disconnect()` и `startStream`,
на этом пути не выполняется.

Починка: не занулять `streamJob` в `disconnect()` — пусть `cancelAndJoin()` в
`startStream` его дождётся; ссылка на завершённую Job безвредна.

---

## Умеренно

### 4. Гонка счётчиков качества GPS между главным потоком и `Dispatchers.Default`

`dash/map/LocationTracker.kt:41-43`, инкременты `:51-52`, `:56`; чтение и сброс
`:158-159`

`acceptedCount` / `rejectedCount` / `degradedCount` инкрементируются в
`LocationListener`, который зарегистрирован на `Looper.getMainLooper()` (:107), а
читаются и обнуляются в `launchQualityLog` на `Dispatchers.Default` (:142). Поля
обычные, инкремент — не атомарный read-modify-write.

Ущерб только диагностический: часть инкрементов теряется, отчёт может показать
устаревшие числа. Но именно ради этих строк подсистема и существует
(post-mortem по `diag/`), так что «примерно правильные» числа тут хуже, чем
кажется. `lastFixAtMs` рядом уже `@Volatile` — эти три просто пропустили.

Починка: `AtomicInteger`, либо считать в том же потоке, что и читает.

### 5. `MaintenanceNotifier` — флаг инициализации выставляется до `await`, prefs читаются-пишутся через `await`

`lib/state/maintenance_notifier.dart:26-40` и `:53-97`; вызывается из
`lib/state/garage_controller.dart:158`

*Async-гонка.* `_ensureInitialized` ставит `_initialized = true` **до** `await
_plugin.initialize(...)`. Второй вызов `check()`, стартовавший пока первый ещё
инициализируется, пролетает `_ensureInitialized` мгновенно и доходит до
`_plugin.show(...)` (:83) на неинициализированном плагине.

`GarageController._reload()` явно документирует, что запускается конкурентно, и
дёргает `check()` через `unawaited` в конце каждого прошедшего generation-guard
прогона. Достаточно: `addFuel` → `_reload` → `check` #1 улетел, рядом тап
«удалить» → `_reload` → `check` #2, пока #1 ещё в `SharedPreferences`.

Второй эффект того же перекрытия: `_prefsKeyNotified` читается на `:60` и
записывается на `:67`/`:76`/`:79` через несколько `await` — классическое
потерянное обновление. Оба вызова считают `newlyDue` от одной и той же базы и
оба постят уведомление.

Починка: кэшировать *future* инициализации (`Future<void>? _init; _init ??= _doInit()`),
как это сделано в `AppDatabase._opening` — там ровно этот паттерн уже разобран
правильно. Плюс сериализовать `check()` через ту же цепочку.

### 6. Два одновременных `downloadAndInstall` затирают APK друг друга

`lib/state/app_update_controller.dart:117-147` + `lib/util/apk_downloader.dart:19-23`

*Async-гонка.* `downloadApk` начинает с `dir.delete(recursive: true)` всего
`cache/updates/`, потом открывает свой `IOSink`. В контроллере нет защиты от
повторного входа, а статус `downloading` выставляется только **после**
`await ApkInstaller.canInstallPackages()` (:121) — то есть кнопка «Скачать»
(`settings_screen.dart:549`) всё это время остаётся активной, и точек входа
три: кнопка, диалог на запуске (`app_shell.dart:80`) и переигровка после выдачи
разрешения (`settings_screen.dart:95`).

Интерливинг: A стримит в `updates/app.apk`; B сносит каталог, пересоздаёт его и
открывает свой sink. Дескриптор A остаётся валидным на уже отвязанном inode — A
досчитывает прогресс до 100%, вызывает `ApkInstaller.installApk(file.path)`, а по
этому пути лежит недокачанный файл B. Установщик получает битый APK.

Починка: флаг повторного входа в `downloadAndInstall` (или ставить `downloading`
первой строкой, до всех `await`), плюс уникальное имя файла на скачивание вместо
`delete(recursive: true)` по общему каталогу.

### 7. Колбэк сотовой сети приходит не на главный поток и гонится с `releaseCellularDefault`

`dash/DashWifiManager.kt:195-225`

Основной запрос WiFi регистрируется с явным `Handler(Looper.getMainLooper())`
(:358) — всё состояние класса из-за этого живёт на главном потоке и обходится без
`@Volatile`, и это правильно. А `requestCellularDefault` использует
двухаргументный `cm.requestNetwork(request, cb)` (:215), у которого колбэки
приходят на внутренний поток `ConnectivityManager`.

Сами по себе `onAvailable`/`onLost` общих полей не трогают, но
`releaseCellularDefault()` (:221-225) с главного потока делает
`unregisterNetworkCallback` и затем `bindProcessToNetwork(null)` — а уже
находящийся в полёте `onAvailable` на другом потоке может выполнить
`bindProcessToNetwork(network)` после этого. Процесс остаётся привязанным к
сотовой сети от снятого запроса, в том числе после `onDetachedFromEngine`.

Починка: передать в этот `requestNetwork` тот же `Handler(Looper.getMainLooper())`,
и весь класс снова окажется однопоточным по построению.

---

## Мелочи / на заметку

### 8. `DebugLog.sink` не `@Volatile`

`util/DebugLog.kt:12`

Пишется на главном потоке в `onAttachedToEngine`
(`OpendashDashEnginePlugin.kt:77`), читается из `emit`/`e` со всех потоков —
`Dispatchers.IO` (RX-цикл, хартбиты), `Dispatchers.Default` (цикл кадров), потоки
`ConnectivityManager` и MediaLibre. Без `@Volatile` поток вправе не увидеть
установленный sink, и строки просто не доедут ни до `app_log.txt`, ни до Talker.
Для проекта, где единственный post-mortem — это файл на устройстве, стоит
поправить одним словом.

### 9. `RouteSearchController.resolve` — `resolving` без guard'а по запросу

`lib/state/route_search_controller.dart:119-129`

*Async-гонка.* В `_search` рядом (:99) есть `_requestId`, а в `resolve` — нет.
Два перекрывающихся `resolve` (быстрый повторный тап по подсказке): тот, что
завершится первым, снимет `resolving` в `finally`, пока второй ещё в полёте, и
индикатор погаснет раньше времени. Косметика, но паттерн уже решён в соседнем
методе.

### 10. `mapsWorker` привязан к экземпляру `MainActivity`

`android/app/src/main/kotlin/ru/snatchdash/app/MainActivity.kt:30`, `:58-61`

Однопоточный executor — правильное решение (см. развёрнутый комментарий на
:125-146), но он поле экземпляра, а не синглтон. Два живых `MainActivity` дадут
два воркера и ровно те интерливинги над `.part`/pending prefs, которые этот
executor исключает. `launchMode="singleTask"` и широкий `configChanges` в
манифесте делают это маловероятным сегодня, но гарантия держится на манифесте, а
не на коде. Отдельно: `mapsWorker.shutdown()` в `onDestroy` — последующий вызов
через канал (если движок переживёт активити) бросит `RejectedExecutionException`
синхронно в обработчике method channel.

### 11. `VoiceManager.load()` — тот же флаг-до-await, что и в п. 5

`lib/nav/voice_manager.dart:55-64`

`_loaded = true` до `await SharedPreferences.getInstance()`. Сейчас безопасно:
единственный вызов — `await` в `main.dart:32` до `runApp`. Отмечено как латентное,
чинить не обязательно.

---

## Проверено и признано корректным

Чтобы не перепроверять по второму разу:

- **`snapshotGeneration` не имеет окна рассинхрона.** Проверялась гипотеза, что
  между `snapshots.prepare()` (`DashEngineController.kt:667`) и
  `snapshotGeneration = snapshots.currentGeneration()` (`:670`) успевает влезть
  `disconnect()` — прочитать старое поколение, получить «stale release … ignored» и
  утечь свежепостроенным `MapSnapshotter`. Гипотеза неверна: обе функции —
  `withContext(Dispatchers.Main)`, а `startStream` и сам исполняется на
  `Dispatchers.Main` (scope плагина — `OpendashDashEnginePlugin.kt:60`). При
  совпадающем `ContinuationInterceptor` `withContext` идёт по undispatched-пути и
  в очередь лупера не возвращается, так что точки приостановки здесь нет вообще.
  Реальные приостановки в `startStream` только две — `cancelAndJoin()` (:629) и
  `withContext(Dispatchers.IO)` (:665); в обеих `prepare()` ещё не вызывалась,
  снапшоттер не построен, и `catch` на :676 доубирает энкодер с битмапом. Утечки
  нет ни на одном пути.
- `MapSnapshotProvider` — схема с `snapshotterIssue`/`inFlight` держится: `issue`
  инкрементируется только когда `inFlight == false` (ранний выход на :244-250 не
  доходит до :277), поэтому поздний колбэк не может обнулить `inFlight` чужого
  запроса.
- `DashSession.endLink` (:397) и `runSession` (:282) — identity-проверки сокета на
  месте; проблема только в `fail()`.
- `RenderStats.reset()` вызывается с главного потока (`:690`), но между
  `cancelAndJoin` старого цикла (:629) и запуском нового (:692) — конкурента нет.
- `frameBitmap`/`encoder` — `@Volatile`, и порядок «join → присвоить → запустить»
  корректен на пути `ERROR → retry` (там `streamJob` не зануляется); ломается
  только на пути через `disconnect()` (п. 3).
- `MediaInfoProvider` — `start`/`stop`/`bind`/колбэки контроллера и сессий все на
  главном лупере.
- `AppDatabase._opening` (`lib/data/app_database.dart:32-41`) — кэширование
  *future*, а не результата, плюс сброс кэша на ошибке: правильно.
- Generation-guard'ы в `InstalledPacks.reload`, `GarageController._reload`,
  `RidesController._reload`, `VehicleStoreController._load`,
  `RouteSearchController._search` — на месте, вместе с `ref.mounted` после каждого
  `await`.
- `OfflineMapsController` — `_ticking` (перекрытие тиков) и `_wantsPolling`
  («последний тик») закрывают обе гонки поллера.
- `PersistentLogWriter._queue` — сериализация записи через цепочку future;
  `_append` глотает все исключения, так что цепочка не рвётся.
- `RideDiagnostics` — небезопасный `SimpleDateFormat` используется только из
  `raw()`, а все три вызывающих (`start`/`log`/`stop`) держат `synchronized(lock)`.
- `RouteController.sendToDash` — `_navLoop?.stop()` и присваивание в одном
  синхронном блоке после всех `await`, лишний `NavLoop` не утекает.
- `NavLoop._reroute` — `_rerouting` снимается в `finally`, проверка `_stopped`
  после `await` есть.
