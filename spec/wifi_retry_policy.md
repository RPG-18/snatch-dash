# Стратегия переподключения к dash по Wi-Fi

Как сейчас устроено обнаружение обрыва и переподключение к Wi-Fi-точке
дэша, и где в этой логике дыры — для последующей доработки в сторону
прогрессивного (backoff) переподключения. Три независимых уровня, живут в
разных файлах и не координируются напрямую друг с другом:

- [`DashWifiManager.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/DashWifiManager.kt) —
  уровень ОС: `ConnectivityManager`/`WifiNetworkSpecifier`, знает только
  «есть сеть с таким SSID или нет», ничего не знает о состоянии K1G-сессии.
- [`DashSession.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/DashSession.kt) —
  уровень протокола: WiFi может быть технически жив, а дэш уже не отвечает
  на пакеты — обнаруживается только здесь.
- [`DashEngineController.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt) —
  координатор: сводит состояние первых двух и решает, когда пересоздавать
  K1G-сессию поверх нового Wi-Fi-подключения.

## Константы

| Константа | Значение | Где | Смысл |
|---|---|---|---|
| `CONNECT_TIMEOUT` | 30 000 мс | `DashWifiManager` | Сколько ОС ищет сеть по одному `requestNetwork()`, прежде чем вызвать `onUnavailable()` |
| `RECONNECT_DELAY` | 8 000 мс | `DashWifiManager` | Фиксированная пауза перед повторным `requestNetwork()` — не растёт со временем (не exponential backoff) |
| `RX_IDLE_TIMEOUT_MS` | 10 000 мс | `DashSession` | Сколько тишины от дэша в `STREAMING` считается «дэш умер», даже если WiFi-линк формально жив |
| `RSSI_POLL_INTERVAL_MS` | 5 000 мс | `DashWifiManager` | Частота записи RSSI/link speed в лог, пока `CONNECTED` |
| `AUTH_TIMEOUT` | 15 000 мс | `DashSession` | Таймаут K1G-хендшейка после того, как Wi-Fi уже поднялся (отдельно от Wi-Fi-таймаутов выше) |

Итого один полный цикл «увидели обрыв → следующая попытка» — до
`RECONNECT_DELAY + CONNECT_TIMEOUT` ≈ **38 секунд**, и это значение не
меняется от количества подряд идущих неудач.

## Три источника обнаружения обрыва

1. **`onLost(network)`** (`DashWifiManager`) — ОС сообщает, что ранее
   поднятая сеть пропала. Всегда планирует повтор через `RECONNECT_DELAY`,
   пока `wantConnected == true`.
2. **`onUnavailable()`** (`DashWifiManager`) — `requestNetwork()` не нашёл
   подходящую сеть за `CONNECT_TIMEOUT`. Ветвится по `hasConnectedOnce`
   (см. ниже) — это единственное место, где ретраи могут остановиться
   насовсем без участия пользователя.
3. **RX-watchdog** (`DashSession.launchReceiveLoop`) — независимо от того,
   что говорит ОС о состоянии Wi-Fi, если в `STREAMING` от дэша нет ни
   одного пакета `RX_IDLE_TIMEOUT_MS`, сессия сама закрывает сокет и
   уходит в `IDLE`. Обнаруживает случай «Wi-Fi всё ещё „подключён“, но дэш
   не отвечает» — единственный из трёх, кто это видит.

## Поток событий

### A. Обрыв после успешного коннекта (`hasConnectedOnce == true`)

1. `onLost`/повторный `onUnavailable` → статус `REQUESTING`, `scheduleReconnect()`.
2. Через `RECONNECT_DELAY` — новый `requestNetwork()`. Молча, без диалогов
   (тот же `NetworkCallback`, что и раньше — коллбэк не пересоздаётся).
3. Повторяется **бесконечно**, пока `wantConnected == true` — интервал
   между попытками не растёт (см. «Пробелы» ниже).
4. Как только сеть находится — `onAvailable`/`onCapabilitiesChanged` →
   `markConnected()` → `DashEngineController`'s `wifiManager.state.collect`
   видит `CONNECTED` и поднимает свежую `session.connect()`.

### B. Обрыв на самой первой попытке (`hasConnectedOnce == false`)

1. `connect(ssid, ...)` выставляет `hasConnectedOnce = false`.
2. Если `requestNetwork()` не находит сеть за `CONNECT_TIMEOUT` — сразу
   `onUnavailable()` с `hasConnectedOnce == false` → статус `ERROR`,
   **`wantConnected = false`**. Дальше ничего не происходит само.
3. Требуется явное действие пользователя — тап «Подключиться» на
   [Dash-экране](dash_screen.md) → заново `DashEngineController.connect()`
   → заново `DashKeepAliveService.start()` + `wifiManager.connect(...)`.

### C. Дэш умолк, пока Wi-Fi формально жив

1. `DashSession`: `RX_IDLE_TIMEOUT_MS` без пакетов в `STREAMING` →
   закрывает сокет, `setState(IDLE)`, `onError("Lost connection to dash")`.
2. `DashWifiManager` **ничего не замечает** — сеть с его точки зрения
   всё ещё `CONNECTED`, никакого `onLost`/`onUnavailable` не будет.
3. Восстановление зависит от `DashEngineController`: `sessionWatchJob`
   слушает только переходы в `READY` (чтобы запустить стрим), но не
   реагирует на неожиданный уход `session.state` в `IDLE`, пока
   `wifiManager.state` не сменился сам — а раз WiFi «жив», он не сменится.
   На практике сессия просто зависает в `IDLE`, пока WiFi когда-нибудь не
   моргнёт сам по другой причине, либо пока пользователь не нажмёт
   «Отключить»/«Подключиться» вручную.

### D. Явный disconnect() → connect() (ручной цикл)

1. Пользователь жмёт «Отключить» (или fix из `DashEngineController`
   разрывает зомби-сессию при уходе Wi-Fi из `CONNECTED`) →
   `wifiManager.disconnect()` → `release()` →
   **`cm.unregisterNetworkCallback()`**, `NetworkCallback`-объект уничтожен.
2. Следующий `connect()` строит **новый** `NetworkCallback` и вызывает
   `cm.requestNetwork()` с нуля — не «тот же самый», что до разрыва.
3. Подтверждено логами 2026-08-27 (`adb logcat` + Wi-Fi verbose logging):
   именно и только в этом сценарии Android поднял системный диалог
   `com.android.settings.wifi.NetworkRequestDialogActivity` — несмотря на
   точный (не префиксный) SSID, который уже был одобрен раньше. Все
   остальные переподключения в тот же день (18 из 19, через `onLost`/
   `onUnavailable` без промежуточного `disconnect()`) прошли молча.
4. **Вероятная причина — не время жизни `NetworkCallback`, а BSSID.**
   Согласие ОС кэшируется по тройке **(SSID, BSSID, тип безопасности)**,
   не только по SSID — см. «Внешние находки» ниже. Если у дэша при подъёме
   Wi-Fi-радио после полного разрыва меняется BSSID (MAC точки доступа),
   Android видит формально другую сеть и просит подтверждение заново, даже
   при полностью совпадающем SSID.

## Внешние находки (2026-08-27)

- **Кэш согласия `WifiNetworkSpecifier` привязан к (SSID, BSSID, тип
  безопасности), а не только к SSID** — официально задокументировано:
  [Wi-Fi Network Request API — Android Developers](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap),
  исходник [`WifiNetworkSpecifier.java`](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-29/+/refs/heads/androidx-emoji-release/android/net/wifi/WifiNetworkSpecifier.java).
  Это объясняет сценарий D лучше, чем «пересоздание `NetworkCallback`»:
  если BSSID дэша не фиксирован (плавает при каждом подъёме радио —
  типично для дешёвых embedded Wi-Fi модулей), диалог будет всплывать при
  каждом полном переподключении независимо от кода приложения.
  **Инструментировано** — `DashWifiManager.logSignalInfo` теперь пишет
  `bssid=` в каждую строку `Signal (...)`, включая `Signal (connected)`
  сразу при успешном коннекте и `Signal (last before loss)` перед
  `onLost` — сравнить эти два значения в `app_log.txt` через цикл
  disconnect→connect, без доп. adb/verbose logging.
- **EMUI отдельно (не связано с диалогом) агрессивно убивает фоновые
  процессы по wakelock/сетевой активности** — начиная с EMUI 9 в системе
  живёт `PowerGenie`/`HwPFWService`, убивающий всё не в белом списке
  Huawei; сторонним приложениям попасть в список нельзя.
  Источники: [dontkillmyapp.com/huawei](https://dontkillmyapp.com/huawei),
  [XDA: Remove PowerGenie](https://xdaforums.com/t/remove-powergenie-to-allow-background-apps-to-receive-push-notifications.3890409/),
  [dont-kill-my-app issue #183](https://github.com/urbandroid-team/dont-kill-my-app/issues/183).
  Задокументированный обход (несколько источников независимо):
  **Настройки → Батарея → Запуск приложений → snatch-dash → выключить
  «Управлять автоматически»** → вручную включить «Автозапуск» +
  «Вторичный запуск» + «Работа в фоне». Это отдельный от диалога риск-
  фактор для `DashKeepAliveService` — стоит проверить, включён ли этот
  обход на тестовом телефоне.

## Из оригинального приложения (re_app, com.royalenfield.reprime)

По команде — разобран decompiled-эквивалент в `re_app/jadx_out` (каталог локальный, в репозиторий не входит):
`bluconnect/seh.java` (по отладочным символам — обфусцированный
`NetworkEngine.kt`) + обёртка `bluconnect/ldh.java`. Прямой аналог
`DashWifiManager`, три существенных отличия:

1. **Нет таймаута на `requestNetwork()`.** Используют 2-аргументную
   перегрузку (`requestNetwork(request, callback)`) без `Handler`/timeout —
   у нас же 4-аргументная с `CONNECT_TIMEOUT=30000`. Без таймаута
   `onUnavailable()` от истечения времени не срабатывает вообще — запрос
   просто висит, пока штатный Wi-Fi-сканер ОС сам не найдёт точку. Это
   полностью снимает нашу «асимметрию первой попытки» (см. пробелы выше) —
   там нечему таймаутиться и сдаваться.
2. **Нет ручного scheduling ретраев.** В `ldh.b.f()` (`onLost`/
   `onUnavailable`/`onLosing`) — только сброс флага и broadcast статуса
   `RED`, никакого `delay()+requestNetwork()` аналога `scheduleReconnect()`.
   Переподключение, похоже, полностью на пользователе/UI-слое.
3. **Device-gated обход пересоздания callback**:
   ```java
   public final boolean h() {  // isPixel14Plus, по сути
       return Build.MANUFACTURER.equalsIgnoreCase("Google")
           && Build.MODEL.contains("Pixel");
   }
   public final void g(edh networkConfiguration) {
       if (!h() || Build.VERSION.SDK_INT <= 33) { i(); }  // i() = unregisterNetworkCallback()
       ...
   }
   ```
   `unregisterNetworkCallback()` пропускается **только** на Google Pixel +
   Android 14+ — на всех остальных устройствах (любой Huawei в том числе)
   коллбэк каждый раз пересоздаётся заново. Прямое свидетельство, что
   команда RE сама наткнулась на зависимость диалога/кеша согласия от
   пересоздания callback или устройства, и закрыла узким хардкодом, а не
   общим решением.
4. **BSSID не «запоминается» от предыдущего подключения** — приходит
   готовым извне (похоже, из QR-кода/BLE при сопряжении через
   `SsidScanActivity`) и пинится сразу в первый же `WifiNetworkSpecifier`,
   если похож на валидный MAC (ровно 12 hex-символов, не начинается с «0»
   — вероятно, фильтр от заглушек/placeholder-значений).

**Выводы для нашей доработки:** убрать `CONNECT_TIMEOUT`/полагаться на
беcтаймаутный `requestNetwork()` вместо ручного 8-секундного цикла — самое
дешёвое изменение, снимающее сразу два пробела (нет эскалации интервала +
асимметрия первой попытки). Device-gated обход unregister — тоже кандидат,
но менее приоритетный: у нас источник дребезга иной (полный
`disconnect()`/`connect()` пользователем или zombie-teardown), не каждый
реконнект.

## Из живого форка (OpenMotoDash/NorthStar)

По команде — найден и склонирован активный форк удалённого проекта
[`adityadasika21/NorthStar`](https://github.com/adityadasika21/NorthStar)
(оригинал снят по требованию Royal Enfield, но у форка
[`OpenMotoDash/NorthStar`](https://github.com/OpenMotoDash/NorthStar) код
жив, 45 коммитов). Имена классов/полей почти один в один совпадают с
нашими (`DashWifiManager`, `hasConnectedOnce`↔`everConnected`,
`pendingSsid`, `scheduleReconnect`) — общее происхождение от исходного
`open-dash`. Их `DashWifiManager.kt` уже реализует retry-with-backoff,
почти идентичный нашему фиксу №1 (та же развилка по `everConnected`). Но
выше, в `DashViewModel.kt`, есть три механизма, которых у нас нет вообще:

1. **Верхний «giveup timer», не в `DashWifiManager`, а в ViewModel.**
   `RECONNECT_GIVEUP_MS = 120_000` (2 минуты) — таймер, который взводится
   при любом не-`STREAMING` состоянии, пока `userWantsConnection`, и
   снимается по достижении `STREAMING`. Если за 2 минуты стрим не
   поднялся — `giveUp()` полностью останавливает WiFi-реконнект-луп,
   foreground-сервис, GPS и энкодер. Закрывает ровно наш пробел «нет
   эскалации/верхней границы» — не наращиванием интервала, а простым
   потолком по суммарному времени ожидания.
   **Реализовано** (2026-08-27) — [`DashEngineController.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt)'s
   `sessionWatchJob`: `armGiveupTimer()`/`cancelGiveupTimer()` на каждом
   `session.state`, `RECONNECT_GIVEUP_MS = 120_000`; по истечении без
   `STREAMING` вызывает уже существующий `disconnect()` (полный штатный
   teardown, ничего не дублировалось).
2. **Голосовое оповещение о разрыве (`armConnLostAlert`).** Через
   `CONN_LOST_ALERT_MS = 12_000` устойчивого (не разового) разрыва —
   вибрация + голосовая фраза «Dash disconnected. Restart the dash to
   reconnect.» через тот же voice-канал, что и озвучка манёвров; при
   восстановлении — «Dash reconnected». У нас узнать об обрыве можно
   только по экрану (который специально выключен) — прямой кандидат.
   **Реализовано** (2026-08-27), но на стороне **Dart**, не нативно (в
   отличие от остального в этом документе) — у нас, в отличие от
   NorthStar, вся речь уже централизована в `VoiceManager`
   (`lib/nav/voice_manager.dart`, `flutter_tts`), нативная сторона речи не
   производит вообще (см. её же класс-докстринг про `ToneGenerator`).
   Поэтому: новый `DashConnectionAlertController`
   (`lib/state/dash_connection_alert_controller.dart`, подписан eagerly из
   `main.dart` как `DashButtonController`) слушает уже существующий
   `dashEngineStateProvider` и сам считает 12-секундный debounce — никакого
   нового моста native→Dart для самого таймера не потребовалось, он уже
   есть (`publishState()` и так шлёт каждый `stage`). Два новых метода на
   `VoiceManager` — `announceConnectionLost()`/`announceConnectionRestored()`
   — используют существующий `_speak`/`playChime`, с новыми ARB-ключами
   `voiceDashDisconnected`/`voiceDashReconnected`.

   Одна тонкость, которой нет у NorthStar: и ручной `disconnect()`, и
   необорванный (RX-watchdog/zombie-teardown) обрыв сессии дают на нашей
   стороне одно и то же `DashState.IDLE` — по одному только `stage` их не
   отличить, иначе ручное отключение тоже через 12с озвучилось бы как
   «связь потеряна». Решено добавлением явного одноразового флага
   `explicitDisconnect` в `publishState()` (`true` только внутри самого
   `disconnect()`) — `DashEngineState.explicitDisconnect` в Dart, читает
   только `DashConnectionAlertController`.
3. **Отдельный, дешёвый путь ретрая именно хендшейка.** Если рвётся не
   WiFi, а K1G-аутентификация (`DashState.ERROR` при
   `wifiManager.state.value.status == CONNECTED`) — просто повторяют
   `session.connect()` до `MAX_AUTH_ATTEMPTS = 4` раз с паузой 1.5с,
   **не трогая WiFi-подключение вообще**. У нас в логе 2026-08-27 виден
   именно такой случай (`AUTHENTICATING -> ERROR` → «Using
   already-connected matching WiFi» → полный новый цикл) — можно дешевле.
   **Реализовано** (2026-08-27) — [`DashEngineController.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/DashEngineController.kt)'s
   `sessionWatchJob`: на `DashState.ERROR` при живом WiFi — до
   `MAX_AUTH_RETRIES = 4` повторов `session.connect()` с паузой
   `AUTH_RETRY_DELAY_MS = 1500`мс, без обращения к `DashWifiManager`.
   Счётчик `authRetries` сбрасывается на `READY` (хендшейк реально прошёл)
   и на каждом новом верхнеуровневом `connect()`.

Также по мелочи: `RECONNECT_DELAY = 4_000` у них (у нас 8_000), и есть
`isWifiEnabled()` — явная проверка, что радио WiFi вообще включено, чтобы
сразу отправить райдера в настройки вместо тихого таймаута.

## Заметки / пробелы (кандидаты для прогрессивного reconnect)

- **Нет эскалации интервала.** `RECONNECT_DELAY` = 8 000 мс фиксированно и
  для первого повтора, и для сотого — при затяжной RF-деградации (см.
  находки по GPS-jamming зонам) это означает ретраи каждые ~38 секунд
  часами, без нарастания паузы и без верхней границы числа попыток.
- **Асимметрия «первая попытка» vs «уже была связь».** Единственный
  случай, где Wi-Fi-модуль сдаётся сам — таймаут на самой первой попытке
  в рамках `connect()`. На практике (лог 2026-08-27) дэшу иногда физически
  нужно больше 30 секунд, чтобы точка снова стала видна после разрыва —
  это ошибочно классифицируется как «неверный SSID/пароль» и требует
  ручного вмешательства, хотя ситуация полностью аналогична сценарию A.
- **Полный disconnect()/connect() пересоздаёт `NetworkCallback`** и, судя
  по всему, из-за этого может заново показать системный диалог даже для
  уже одобренного точного SSID (см. поток D) — тогда как «мягкое»
  переподключение через `scheduleReconnect()` этого не делает. Ручной
  цикл «Отключить → Подключиться» пользователем — самый дорогой путь
  восстановления связи именно поэтому.
- **Три уровня не сообщают друг другу о своих находках.** RX-watchdog
  (сценарий C) знает, что дэш замолчал, но не может попросить
  `DashWifiManager` разорвать и поднять Wi-Fi заново — а раз ОС считает
  сеть живой, `DashWifiManager` сам этого не предложит. Сейчас это чинится
  косвенно (`DashEngineController`'s `session.disconnect()`, если
  `wifiManager` сменит статус — но он не обязан меняться) — то есть
  сценарий C может зависнуть до внешнего повода поменять Wi-Fi-статус.
