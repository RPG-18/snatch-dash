# Баги: сеть и многопоточность

Область: нативный движок дэша (`packages/opendash_dash_engine/android`), хост-часть
Android (`android/app`), сетевой и асинхронный код на Dart (`lib/`). Проход
по коду от 2026-09-07, после правок из [review.md](./review.md) и
[review-2.md](./review-2.md) — их находки здесь не повторяются, только новое
и то, что осталось незакрытым.

Как и раньше: в Dart изолят один, «настоящих» гонок нет, но каждый `await` —
точка, где состояние меняется под ногами; такие места помечены как
*async-гонки*.

---

> **Статус на 2026-09-07**: пункты 1–5 исправлены (отмечено внутри каждого),
> 6–12 открыты.

## Критично

### 1. `DashEngineController.connect()` при живой сессии рвёт WiFi-линк к дэшу

`DashEngineController.kt:336-385`, `DashWifiManager.kt:127-139`, `:234-257`,
`:509-518`; вызывающий — `lib/state/route_controller.dart:62`.

[spec/fsm.md](./spec/fsm.md) (строка 92, переход `idleMap → navigating`)
обещает: «`RouteController.sendToDash()`: `DashEngine.setDestination()` (уже
подключено, `connect()` — no-op)». No-op — только `DashSession.connect()`
(гард по состоянию, `DashSession.kt:205`). `DashEngineController.connect()`
таким гардом не защищён и на уже подключённом дэше делает полный цикл заново:

1. `wifiWatchJob?.cancel()` и `wifiManager.connect(...)` →
   `requestNetwork()` → **первым делом `release()`** (`:243`) →
   `cm.unregisterNetworkCallback(cb)` (`:515`). Для сети, поднятой через
   `WifiNetworkSpecifier`, это единственный запрос, который её держит: по
   контракту API платформа разрывает соединение, как только запрос снят
   (то же самое spec/wifi_retry_policy.md описывает для ручного
   `disconnect()`, сценарий D).
2. Сразу после этого, на том же главном потоке,
   `findAlreadyConnectedDashNetwork()` (`:250`) читает
   `WifiManager.connectionInfo`. Разрыв асинхронный (binder →
   ConnectivityService → WifiNetworkFactory), поэтому SSID ещё на месте →
   ранний `return` → `markConnected()` **без регистрации нового
   `NetworkCallback`**. `onLost` для этой сети теперь не придёт никогда:
   коллбэка нет.
3. Линк падает. `DashWifiManager` остаётся в `CONNECTED`. Сокеты сессии
   привязаны (`Network.bindSocket`) к уже мёртвому `Network`, `send`
   молча падает (`DashSocket.send` глотает), `receive` только таймаутится →
   RX-watchdog через `RX_IDLE_TIMEOUT_MS` = 10 с → `endLink` → `IDLE` →
   дальше п. 4 (никто не переподключает) → через 120 с `giveupJob` →
   `disconnect()`.

Если же шаг 2 не сработает (SSID уже редактирован/сброшен) — ветка не
лучше: новый `cm.requestNetwork()` с нуля, риск системного диалога (spec,
сценарий D), `REQUESTING` → новый `wifiWatchJob` видит не-`CONNECTED` только
после того, как `sessionStarted` станет `true`, а он `false` — старая сессия
остаётся на сокетах старого `Network` и умирает тем же watchdog'ом.

Побочные эффекты того же вызова: `hasConnectedOnce = false` (`:133`) — первый
же `onUnavailable` после этого уходит в `ERROR` + `wantConnected = false`
вместо бесконечного ретрая; `RideDiagnostics.start("connect")` (`:338`)
открывает новый ride-файл посреди поездки; `authRetries = 0`,
`startMediaForwarding()` повторно.

Триггер — штатный: Главная → тап по карточке статуса (подключились, `idleMap`)
→ Маршрут → Превью → «Поехали» → `sendToDash()` → `connect()`. Через ~10 с
после «Поехали» дэш теряет картинку, через 2 минуты приложение сдаётся.

**Исправлено 2026-09-07**: гард по состоянию в `DashEngineController.connect()`
(выход, если сессия жива и WiFi `CONNECTED`) плюс гард в
`DashWifiManager.connect()` — если уже держим сеть с подходящим SSID, живой
запрос сохраняется и `requestNetwork()`/`release()` не вызываются вовсе.

**Подтверждено полевым логом 2026-09-07 21:38:29** (app_log.txt): повторный
`connect()` через 1.4 с после `ERROR` → `Requesting WiFi` → `Using
already-connected matching WiFi 'RE_****18'` → `CONNECTED` без коллбэка →
через секунду `TX send failed (link down?): sendto failed: ENETUNREACH` дважды
подряд — сеть, которую только что «нашли», уже снята вместе с запросом.

Починка: в `DashEngineController.connect()` ранний выход, если
`session.state.value !in {IDLE, ERROR}` и `wifiManager.state.value.status ==
CONNECTED` (или как минимум не трогать `wifiManager.connect()`, пока WiFi
поднят); и убрать безусловный `release()` из `requestNetwork()` для случая,
когда текущий коллбэк уже держит подходящую сеть.

### 2. `DashSession.disconnect()` шлёт `projectionStop`/`projectionOff` с главного потока — команды не доходят

`DashSession.kt:266-270`, `DashSocket.kt:74-84`; вызывающие —
`DashEngineController.kt:456` (метод-канал, `giveupJob`, `dispose()` — всё
`Dispatchers.Main`) и `:382` (`wifiWatchJob`, тоже Main).

`DatagramSocket.send` с адресом проходит через `BlockGuardOs.sendto →
onNetwork()`, а главному потоку приложения с targetSdk ≥ 11 ОС ставит
`penaltyDeathOnNetwork` — то есть `NetworkOnMainThreadException`. Это
`RuntimeException`, и `DashSocket.send` его ловит своим `catch (e: Exception)`
и пишет `TX send failed (link down?): null`. Оба пакета, ради которых дэш
должен выйти из режима проекции, теряются на каждом штатном отключении —
дэш остаётся на последнем кадре, пока сам не отвалится по таймауту.

Все остальные отправки в файле специально ушли на IO
(`updateRouteCard`, `:248`; все `launch*`), это единственное место, где
`send` остался на вызывающем потоке.

**Исправлено 2026-09-07**: оба прощальных пакета и закрытие сокета ушли в
`runBlocking(Dispatchers.IO)` — не `scope.launch`, потому что `dispose()`
отменяет скоуп следующей же строкой и launch бы просто не выполнился.

Проверка по полевому логу: строка `TX send failed (link down?): null` сразу
после `Disconnect requested`. Починка: отправлять прощальные пакеты в
`scope.launch(Dispatchers.IO)` и закрывать сокет после них (или `runBlocking`
на IO — два пакета, миллисекунды), либо просто закрывать сокет в том же
launch'е после отправки.

### 3. Перестроение маршрута не работает при выключенном экране

`lib/main.dart:49-63`, `lib/nav/nav_loop.dart:141-170`, `lib/nav/router.dart:89-131`.

`_MapkitLifecycleObserver` на `AppLifecycleState.paused` зовёт
`mapkit.onStop()`. Собственный комментарий в `main.dart:34-36`: «MapKit stays
idle — search/routing requests never leave the device — until onStart() is
called». Выключенный экран — это `onPause` + `onStop` активити → `paused`. А
выключенный экран во время поездки — заявленный основной режим приложения
(CLAUDE.md, первый абзац).

Итог: `NavLoop._reroute` → `Router.route()` → `requestRoutes` при остановленном
MapKit не уходит в сеть. `Completer` не завершается, таймаута нет,
`_rerouting = true` держится до `onStart()`. Ни одного перестроения за всю
поездку с погашенным экраном, а обещание
[spec/route_restructuring.md](./spec/route_restructuring.md) выполняется
только пока телефон в руках. Когда экран включат — зависший запрос
доедет и применится «задним числом», уже с давно неактуальной точкой
старта.

**Исправлено 2026-09-07**: решение «должен ли MapKit сейчас работать» вынесено
в `lib/nav/mapkit_lifecycle.dart` и принимается по двум входам — приложение на
переднем плане **или** идёт навигация (`NavLoop.start()/stop()`). Плюс
`Router.routes` получил `.timeout(30s)` с `session.cancel()`, чтобы `_rerouting`
не мог застрять ни по какой другой причине.

Стоит подтвердить на устройстве одной строкой лога вокруг `Router.route` при
погашенном экране. Починка: не звать `mapkit.onStop()` пока живёт `NavLoop`
(или пока `DashStage.streaming`) — foreground-сервис и так держит процесс;
плюс таймаут на `Router.routes` (`completer.future.timeout(...)`, с
`session.cancel()` в обработчике), чтобы `_rerouting` не мог застрять ни по
какой причине.

### 4. Уход сессии в `IDLE` по RX-watchdog при «живом» WiFi никем не обрабатывается

`DashSession.kt:404-412`, `:449-477`; `DashEngineController.kt:393-435`.

Известно и записано в [spec/wifi_retry_policy.md](./spec/wifi_retry_policy.md),
сценарий C, но не закрыто: `endLink` ставит `IDLE`, а `sessionWatchJob`
реагирует только на `READY` и `ERROR`. `wifiWatchJob` переподключает сессию
только по смене `wifiManager.state`, которая в этом сценарии по определению не
меняется. Единственный выход — `giveupJob` через 120 с → `disconnect()` →
дальше только руками.

**Исправлено 2026-09-07**: `sessionWatchJob` обрабатывает `IDLE` вместе с
`ERROR` — тот же ограниченный `session.connect()` на той же сети, под
дополнительным условием `sessionStarted` (иначе ветка ловила бы стартовый
`IDLE`, который `StateFlow` отдаёт новому коллектору до появления линка).

Комментарий у `endLink` (`:441-445`) объясняет выбор `IDLE` тем, что гард в
`connect()` иначе «refuses every reconnect» — но `connect()` после этого
никто не вызывает. Здесь же и то, что превращает п. 1 из «10 секунд без
картинки» в «2 минуты и сдались».

Починка: в `sessionWatchJob` обрабатывать `IDLE` при `wifi == CONNECTED &&
sessionStarted` так же, как `ERROR` — ограниченный ретрай `session.connect()`;
либо пусть `endLink` ставит `ERROR`, раз `fail()` уже отличается от него
только сообщением.

---

## Умеренно

### 5. `fail()` из `runSession` порождает второй, ложный `endLink` с «Lost connection to dash»

`DashSession.kt:710-721` (`fail`), `:380-397` (RX-цикл), `:449-477` (`endLink`).

`fail()` делает `rxJob?.cancel()` и `socket?.close()`, но `sessionSeq` **не
поднимает**. RX-цикл в этот момент почти всегда стоит в блокирующем
`rxSocket.receive()` — отмена его не прерывает, а `close()` заставляет
бросить `SocketException("Socket closed")`. `withContext(Dispatchers.IO)` в
`receive()` при отменённой корутине отдаёт наружу именно `SocketException`, а
не `CancellationException` (kotlinx предпочитает не-cancellation причину), и
она попадает в `catch (e: Exception)` → `endLink(seq, sock)` с всё ещё
актуальным `seq`.

Дальше по порядку: `RideDiagnostics.log("error", "RX loop stopped — socket
error: Socket closed")`, `deliberate = (state == IDLE)` → `false` (сейчас
`ERROR`), `setState(IDLE)` — переход `ERROR → IDLE`, второй
`onError("Lost connection to dash")`, который в Dart затирает настоящее
сообщение (`Auth timed out…`).

Самый частый провал в поле — как раз таймаут аутентификации (spec), так что
каждая такая попытка оставляет в ride-файле ложную «ошибку сокета» и ошибку
не о том. Функционально ретрай в `sessionWatchJob` всё же срабатывает (он
уже в `delay(1500)`, StateFlow схлопывает `IDLE`, гард `connect()` пропускает
из `IDLE`), но после исчерпания `MAX_AUTH_RETRIES` сессия стоит в `IDLE` с
«Lost connection», а не в `ERROR` с «Auth timed out».

**Исправлено 2026-09-07**: `fail()` поднимает `sessionSeq`, как и
`disconnect()`, — `endLink` из умирающего RX-цикла молча выходит по гарду
токена. Заодно `fail()` теперь гасит `projHbJob`/`routeCardJob`/`navInfoJob`:
раньше их отменял именно тот ложный `endLink`.

**Подтверждено полевым логом 2026-09-07 21:38:27**: `ERROR — Auth timed out` →
`Sockets closed` → через 70 мс `RX loop stopped — socket error: recvfrom
failed: EBADF` → `state ERROR -> IDLE`. Та же пара строк на каждом
`disconnect()` (21:38:31, 21:40:48, 21:41:52) — там она безвредна
(`superseded session … not reporting`), но в ride-файл всё равно попадает как
`[error]`.

Починка: в `fail()` тоже `sessionSeq.incrementAndGet()` (тогда `endLink`
молча выйдет), либо в RX-цикле перед `endLink` проверять `isActive` — сокет,
закрытый после отмены, это не обрыв связи.

### 6. Сетевые запросы без таймаутов; бутстрап офлайн-карт ждёт сеть

`lib/util/github_release.dart:88`, `:98`; `lib/data/map_manifest_api.dart:80`;
`lib/util/apk_downloader.dart:39-55`; `lib/nav/suggest_api.dart:116-118`;
`lib/state/app_update_controller.dart:127-163`;
`lib/state/offline_maps_controller.dart:205-226`.

Ни один `http.get`/`send` не имеет `.timeout()`, а `dart:io`-клиент по
умолчанию не ограничивает ни установку соединения, ни ожидание ответа.
Последствия по местам:

- `AppUpdateController._check` — статус `checking` навсегда, кнопка
  «Проверить обновления» не возвращается.
- `downloadAndInstall` — `downloading` навсегда **и `_installing = true`
  навсегда** (`:131`, снимается только в `finally` завершившегося вызова):
  до перезапуска приложения обновление больше нельзя запустить. Плюс
  `http.Client()` в `downloadApk` (`:40`) никогда не закрывается.
- `OfflineMapsController.build()` — микротаск делает `refresh()` (сеть)
  **до** `_reconcileRegistryAgainstDisk()` и `_tick()` (локально). Именно
  `_tick()` подхватывает пак, докачанный системным `DownloadManager`, пока
  приложение было закрыто — «единственный сценарий, ради которого выбран
  системный загрузчик» (комментарий на `:221-224`). Подвисший `GET
  index.json` откладывает его на неопределённый срок; комментарий на
  `:206-210` говорит, что шаги независимы, — тогда сетевой должен идти
  последним или параллельно.

Починка: `.timeout()` на каждый запрос (манифест и GitHub — 10–15 с, APK —
таймаут на простой между чанками), закрывать клиент, в бутстрапе сначала
локальные шаги.

### 7. `DashKeepAliveService.stop()` через `startService` может бросить `IllegalStateException` из фона

`DashKeepAliveService.kt:50-54`; вызывающий — `DashEngineController.kt:480`,
в том числе из `dispose()` (`:737-740`) при `onDetachedFromEngine`.

Остановка сервиса сделана как `startService(ACTION_STOP)`. На API 26+ из
фонового процесса `startService` запрещён (`IllegalStateException: Not allowed
to start service Intent`), исключение ничем не поймано. Пока свой
foreground-сервис работает, процесс «в переднем плане», и всё в порядке; но
`disconnect()` вызывается и когда сервис **не** запущен — `dispose()` при
уничтожении активити. Сценарий: приложение не было подключено к дэшу, ушло в
фон, через > ~1 минуты (после этого ОС считает uid idle) система убирает
активити → `onDetachedFromEngine` → `dispose()` → `disconnect()` →
`startService` → падение процесса на выходе, с записью через `CrashGuard`.

Починка: `context.stopService(Intent(context, DashKeepAliveService::class.java))` —
это разрешено из фона всегда и не требует `onStartCommand`; `ACTION_STOP`
тогда не нужен.

---

## Мелочи / на заметку

### 8. `routePoints` и `routeJam` — два отдельных `@Volatile`, читаются раздельно

`DashEngineController.kt:226-230`, запись `:581-586`, чтение `:1194-1199` и
`:1258-1259`.

`setNavState` пишет сначала точки, потом пробки; кадровый цикл читает их
двумя чтениями. Между ними на главном потоке может лечь новое
`setNavState` — кадр получает новую геометрию со старым списком пробок. При
несовпадении длин `OverlayRenderer` откатывается на сплошную линию (один
кадр), при совпадении (перестроение с тем же числом точек) — раскрасит новую
дорогу старыми пробками. Косметика на один кадр; лечится одним неизменяемым
объектом `RouteGeometry(points, jam)` в одном поле.

### 9. `SuggestApi` кэширует пустой результат после исчерпания ретраев

`lib/nav/suggest_api.dart:84-98`.

Ретраи существуют потому, что эндпоинт «может вернуть 0 результатов на
запрос, у которого они есть». Но после пяти пустых попыток (или пяти
не-200) `_cache[key] = results` записывает пустой список, и до конца сессии
тот же запрос больше не уходит в сеть — ровно то, от чего ретраи должны
защищать. Кэшировать только непустое.

### 10. Незавершаемые `Completer`'ы при отмене

`lib/nav/place_search.dart:36-39`, `:63`; `lib/state/map_tile_cache.dart:52-55`.

`PlaceSearch.cancel()` отменяет сессию SDK, но её `completer` никто не
завершает — `await completer.future` предыдущего `search()` висит навсегда
(вместе с фреймом `RouteSearchController.resolve`; `finally` там не
выполняется, спасает только `_resolveId`). То же в
`MapTileCacheController.clear()`, если SDK не позовёт `onClearCompleted`.
Утечка, не поломка; закрыть `completeError` при `cancel()`.

### 11. `DashEngine.instance.connect()` / `disconnect()` без `await` и без `catchError`

`lib/screens/home_screen.dart:58`, `lib/screens/dash_screen.dart:181`, `:183`.

`PlatformException` с канала (например, `NO_ENGINE` в момент переподключения
движка) превратится в необработанную асинхронную ошибку. В
`RouteController.exitNavigation` (`route_controller.dart:82-86`) это уже
сделано правильно — сделать так же.

### 12. `DashSocket.receive()` выделяет 64 КБ на каждый вызов

`DashSocket.kt:100`.

`ByteArray(BUF)` создаётся заново при каждом `receive`, включая пустые
таймауты дважды в секунду и каждый входящий пакет (~8/с в стриме). Это
~600 КБ/с мусора на IO-потоке всю поездку — не гонка, но лишняя работа GC
рядом с кадровым циклом. Один буфер на сокет: RX-цикл единственный читатель.

---

## Проверено и признано корректным

Чтобы не перепроверять по второму разу:

- `sessionSeq` в `connect()`/`disconnect()`, идентичность сокета в `endLink`,
  `rxJob?.cancel()` перед перезапуском — держатся; единственная дыра —
  п. 5 (`fail()` не поднимает токен).
- `startStream()`: `cancelAndJoin()` старого кадрового цикла, освобождение
  энкодера в `finally` цикла, `try/catch` вокруг `prepare()` — путей утечки
  `MediaCodec`/битмапа не найдено. Отмена `sessionWatchJob` посреди
  `startStream` (повторный `connect()`) тоже безопасна: `CancellationException`
  проходит через тот же `catch (Throwable)`.
- `MapSnapshotProvider`: всё на Main, `snapshotterIssue`/`inFlight`/`generation`
  согласованы, `releaseNow` из `disconnect()` не может освободить снапшоттер
  следующего стрима.
- `DashWifiManager` — main-confined целиком, включая коллбэк сотовой сети.
- `LocationTracker`, `MediaInfoProvider`, `CallInfoProvider` — коллбэки на
  главном лупере, счётчики атомарные.
- `RtpPacketizer`/`NalProcessor` — только из кадрового цикла.
- `DashCommands` — шаблоны копируются (`HB_0049.copyOf()`, `patchSeq` на
  копии), общих мутируемых буферов между IO-корутинами нет.
- `MainActivity.mapsWorker` + `MapPackDownloader` — один поток, `idNow != id`
  перед `Files.move`, per-pack `try` в `reconcile`.
- `OfflineMapsController` — `_ticking`/`_wantsPolling`; `MapManifestApi._saveLocal`
  с уникальным tmp; `AppUpdateController._installing`.
- `NavLoop` — `_stopped` после `await`, `_rerouting` в `finally`; проблема
  только в отсутствии таймаута и `mapkit.onStop()` (п. 3).
- `SuggestApi` — `cancel()` до присвоения нового `_abort`, `RequestAbortedException`
  не кэшируется; `RouteSearchController._requestId`/`_resolveId`.
- `PersistentLogWriter._queue`, `RideDiagnostics` под `synchronized`,
  `DebugLog.sink` `@Volatile`.
