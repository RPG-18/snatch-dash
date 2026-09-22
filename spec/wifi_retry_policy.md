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

> **Текст ниже писался в августе 2026 по тогдашнему коду и местами описывает
> механизмы, которых больше нет.** Он оставлен как история — полевые логи и
> внешние находки в нём по-прежнему единственный источник. Что заменено:
>
> | Было в тексте | Сейчас |
> |---|---|
> | `RECONNECT_DELAY = 8_000` — фиксированная пауза | [`ReconnectPolicy`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/ReconnectPolicy.kt): full-jitter backoff `random(0, min(30s, 2s·2^n))` |
> | `sessionWatchJob`, `wifiWatchJob`, флаги и три джобы в контроллере | [`ConnectionFsm`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/ConnectionFsm.kt) — чистый редьюсер, два фида-переводчика |
> | «сдаётся сам только на первой попытке» | решение целиком в `ReconnectPolicy.next()`: `coldRetries = 1`, потолок `giveUpAfterMs = 120_000` |
> | «три уровня не сообщают друг другу» | сообщают: `LinkEvent` → `ConnEvent` → `Effect` в одной очереди и одном порядке |
>
> Сценарии A-D ниже никуда не делись — они теперь тесты
> (`ConnectionFsmTest`, `ReconnectPolicyTest`), а не поездки.

## Константы

| Константа | Значение | Где | Смысл |
|---|---|---|---|
| `CONNECT_TIMEOUT` | 30 000 мс | `DashWifiManager` | Сколько ОС ищет сеть по одному `requestNetwork()`, прежде чем вызвать `onUnavailable()` |
| ~~`RECONNECT_DELAY`~~ | ~~8 000 мс~~ | — | **Удалена.** Паузу считает `ReconnectPolicy.next()`: `random(0, min(capMs, baseMs·2^attempt))`, `baseMs = 2 000`, `capMs = 30 000` |
| `LINGER_MS` | 60 000 мс | `DashWifiManager` | Сколько запрос к платформе живёт после «Отключить», чтобы возврат внутри окна не требовал нового `NetworkCallback` — сценарий D |
| `giveUpAfterMs` | 120 000 мс | `ReconnectPolicy` | Сколько может длиться текущий обрыв, прежде чем Wi-Fi-слой перестанет пробовать (сценарий B) |
| `coldRetries` | 1 | `ReconnectPolicy` | Сколько повторов даётся соединению, которое ни разу не поднималось — итого 2 попытки по `CONNECT_TIMEOUT` |
| `RX_IDLE_TIMEOUT_MS` | 10 000 мс | `DashSession` | Сколько тишины от дэша в `STREAMING` считается «дэш умер», даже если WiFi-линк формально жив |
| `RSSI_POLL_INTERVAL_MS` | 5 000 мс | `DashWifiManager` | Частота записи RSSI/link speed в лог, пока `CONNECTED` |
| `AUTH_TIMEOUT` | 15 000 мс | `DashSession` | Таймаут K1G-хендшейка после того, как Wi-Fi уже поднялся (отдельно от Wi-Fi-таймаутов выше) |

| `STREAM_SETTLE_MS` | 20 000 мс | `DashEngineController` | Сколько стрим должен прожить, чтобы считаться рабочим и простить потраченный бюджет повторов — сценарий E |
| `RECONNECT_GIVEUP_MS` | 120 000 мс | `DashEngineController` | Верхняя граница на всю попытку соединения — от первого `connect()` до рабочего стрима |

Итого один полный цикл «увидели обрыв → следующая попытка» — от
`CONNECT_TIMEOUT` до `CONNECT_TIMEOUT + capMs` ≈ **30-60 секунд**, и
нижняя граница паузы всегда ноль: full-jitter специально не выстраивает
попытки в такт, чтобы несколько телефонов рядом с одним дэшем не били в
него синхронно.

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
   **Закрыто** — сессия сама шлёт `SessionEvent.DashSilent`, фид переводит
   его в `ConnEvent.SessionEnded`, и `ConnState.Handshaking` пересобирает
   сессию на том же линке до `MAX_AUTH_RETRIES = 4` раз. Wi-Fi при этом не
   трогается вообще, что и было смыслом дешёвого пути.

### E. Дэш отвечает на хендшейк и тут же роняет стрим

Форма «дэш перезагружается по кругу»: рукопожатие проходит, `Streaming`
живёт секунду, сессия умирает, ретрай, снова проходит. Ни один
предохранитель раньше не срабатывал — вход в `Streaming` обнулял
`authRetries`, поэтому бюджет повторов не тратился вообще: счётчик не
переваливал за 1, а `RECONNECT_GIVEUP_MS` снимался достижением стрима.
Телефон ретраил до разряда батареи.

Разведено на два разных факта: «стрим начался» и «стрим работает».
Бюджет повторов теперь **проезжает через** `ConnState.Streaming`
(поле `authRetries` есть и там), а обнуляет его только
`ConnEvent.StreamSettled` — таймер `STREAM_SETTLE_MS = 20 000`, вдвое
больше `RX_IDLE_TIMEOUT_MS`, то есть к этому моменту обмен хартбитами с
дэшем реально шёл в обе стороны. Поэтому:

- стрим, проживший 20 с, прощает потраченное, и следующий обрыв получает
  полные четыре попытки — обычное переподключение ничего не теряет;
- стрим, умирающий внутри окна, не прощает ничего: после
  `MAX_AUTH_RETRIES` ретраи прекращаются, ветка «бюджет исчерпан» взводит
  `RECONNECT_GIVEUP_MS` заново, и через две минуты мигание заканчивается
  `GaveUp` с полным `StandDown` — вейклоки, GPS, сервис.

**Почему бюджет, а не сам отсчёт двух минут.** Первая редакция этой
правки оставляла `RECONNECT_GIVEUP_MS` идти под стримом и снимала его по
`StreamSettled`. Своё же ревью нашло в этом регресс хуже исходного
дефекта: линк имеет полное право подниматься почти все две минуты
(`CONNECT_TIMEOUT` — 30 с на попытку, между попытками backoff), и стрим,
начавшийся на 100-й секунде, убивался бы на 120-й — рабочим, под
райдером. Отсчёт снова снимается входом в `Streaming`; границу держит
бюджет повторов, который под стрим не сбрасывается.

В ride-файле это видно по метке состояния: `Streaming?#3` — стрим ещё не
дожил до окна и начат на четвёртом рукопожатии этого линка, `Streaming` —
дожил, бюджет прощён.

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
4. **Закрыто 2026-09-22 — запрос переживает «Отключить».** Подтверждено на
   Huawei: диалог вылезает именно и только на ручном цикле. Все четыре цикла
   «Отключить → Подключить» в ride-файлах того дня прошли через
   `Effect.ReleaseWifi`, а `release()` снимает `NetworkCallback` — после чего
   гард «держим живой запрос» в `DashWifiManager.connect` заведомо ложен (ему
   нужны разом `CONNECTED`, `linkCallback != null` и `network != null`), и
   следующий `connect()` обязан идти в `requestNetwork()`.

   Правка 20.09 (убрать `ReleaseWifi` из `restart()`) этот путь не покрывала:
   она помогает, только если райдер жмёт «Подключить» **без** предшествующего
   «Отключить».

   Теперь `Effect.ReleaseWifi` несёт флаг `linger`. На тапе райдера запрос к
   платформе живёт ещё `LINGER_MS = 60_000` — состояние сразу публикуется как
   IDLE (райдеру сказали, что он отключён), опросчики останавливаются, но
   `NetworkCallback` не снимается. `connect()` внутри этого окна отменяет
   отложенный релиз и перезабирает запрос через `markConnected`, **не обращаясь
   к `ConnectivityManager` вообще**. На обоих путях сдачи `linger = false`:
   там нечего придерживать (линк и так лежит — потому и сдались), а рядом стоит
   `StandDown`, смысл которого противоположный.

   Минута выбрана по тому, что райдер реально делает: интервалы между
   «Отключить» и «Подключить» в логах 22.09 — 2 с, 7 с, 20 с. Цена окна —
   только сама ассоциация: дефолт процесса уже привязан к сотовой сети.

   **Почему это устойчиво к обеим гипотезам про диалог.** Нового
   `requestNetwork()` на обратном пути нет вовсе, так что коллбэк не
   перерегистрируется — и, поскольку линк не рвётся, BSSID точки не может
   смениться под нами. Пункт ниже про BSSID остаётся верным как объяснение,
   но перестаёт быть решающим.

5. **Вероятная причина — не время жизни `NetworkCallback`, а BSSID.**
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
  `bssid=` в каждую строку `signal (...)`, включая `signal (connected)`
  сразу при успешном коннекте и `signal (last before loss)` перед
  `onLost` — сравнить эти два значения через цикл disconnect→connect,
  без доп. adb/verbose logging. С 2026-09-08 обе эти строки, как и все
  переходы линка (`link up`, `link lost`, `still unavailable`, итоговая
  `session summary`), пишутся в файл поездки `diag/ride-*.log` — то есть
  доступны и на релизной сборке, где `app_log.txt` нативных строк не
  содержит. В `app_log.txt` (только debug) остаётся вдобавок полная кривая
  сигнала: опрос RSSI раз в 5 с.
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
   `armGiveupTimer()`/`cancelGiveupTimer()`, `RECONNECT_GIVEUP_MS = 120_000`.
   С 2026-09-21 взводит и снимает их не наблюдатель за `session.state`, а
   `ConnectionFsm` эффектами `ArmGiveUp`/`CancelGiveUp`; по истечении
   приходит `ConnEvent.GiveUpDue`, и редьюсер сам решает, что делать. Так
   же ушёл и вызов `disconnect()` из таймера: teardown разложен на
   `StopSession` + `ReleaseWifi` + `StandDown`, потому что «сдались» и
   «райдер нажал Отключить» — не одно и то же событие, хотя отпускают они
   одно и то же.
   Отличие от NorthStar, найденное уже своим ревью: снимать таймер по
   достижении `STREAMING` недостаточно — см. сценарий E выше.
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
   С 2026-09-21 счётчик живёт в самом состоянии — `ConnState.Handshaking`
   несёт `authRetries`, — так что «сбросить» его нечем и незачем: выход из
   состояния и есть сброс. Раньше это было поле, которое надо было не
   забыть обнулить в двух местах.

Также по мелочи: `RECONNECT_DELAY = 4_000` у них (у нас 8_000), и есть
`isWifiEnabled()` — явная проверка, что радио WiFi вообще включено, чтобы
сразу отправить райдера в настройки вместо тихого таймаута.

## Заметки / пробелы (кандидаты для прогрессивного reconnect)

- ~~**Нет эскалации интервала.**~~ **Закрыто** (2026-09-21):
  `ReconnectPolicy` — full-jitter экспонента `random(0, min(30s, 2s·2^n))`
  плюс потолок `giveUpAfterMs = 120_000` на длительность самого обрыва.
  Форма взята у AWS («Exponential Backoff And Jitter»), а не придумана:
  ровно она даёт и рост паузы, и отсутствие синхронного такта.
- **Асимметрия «первая попытка» vs «уже была связь».** Единственный
  случай, где Wi-Fi-модуль сдаётся сам — таймаут на самой первой попытке
  в рамках `connect()`. На практике (лог 2026-08-27) дэшу иногда физически
  нужно больше 30 секунд, чтобы точка снова стала видна после разрыва —
  это ошибочно классифицируется как «неверный SSID/пароль» и требует
  ручного вмешательства, хотя ситуация полностью аналогична сценарию A.
- ~~**Полный disconnect()/connect() пересоздаёт `NetworkCallback`**~~ и из-за
  этого показывает системный диалог даже для уже одобренного точного SSID.
  **Подтверждено и закрыто** (2026-09-22) — см. пункт 4 потока D: запрос
  переживает «Отключить» на `LINGER_MS`, и возврат внутри окна не трогает
  `ConnectivityManager`. Остаётся дорогим только возврат ПОЗЖЕ окна и после
  сдачи, где запроса уже нет намеренно.
- **Три уровня не сообщают друг другу о своих находках.** RX-watchdog
  (сценарий C) знает, что дэш замолчал, но не может попросить
  `DashWifiManager` разорвать и поднять Wi-Fi заново — а раз ОС считает
  сеть живой, `DashWifiManager` сам этого не предложит.
  **Частично закрыто** (2026-09-07): `sessionWatchJob` теперь отвечает на
  `DashState.IDLE` при живом WiFi тем же ограниченным ретраем
  `session.connect()`, что и на `ERROR` — то есть после RX-watchdog'а сессия
  поднимается сама, на той же сети. До этого `endLink` ставил `IDLE`, который
  не обрабатывал никто: единственным выходом был `giveupJob` через 120 с →
  `disconnect()` → дальше только руками. Сам WiFi по-прежнему не
  переподнимается — если молчит не сессия, а линк, который ОС считает живым,
  ретраи упрутся в `MAX_AUTH_RETRIES` и дальше в тот же `giveupJob`.
  **Закрыто полностью** (2026-09-21): все три уровня сходятся в одну
  очередь событий `ConnectionFsm`, и решение принимается один раз в одном
  месте. Остаток, который НЕ закрыт: Wi-Fi по-прежнему не переподнимается
  по требованию сессии — сценарий «линк формально жив, дэша за ним нет»
  заканчивается `GaveUp`, а не пересозданием запроса. Осознанно: снятие
  запроса — это и есть разрыв (сценарий D), и оно тянет за собой
  системный диалог.

- **Повторный `connect()` на живом линке больше не рвёт сеть.**
  `DashEngineController.connect()` не имел гарда по состоянию (в отличие от
  `DashSession.connect()`), а `DashWifiManager.connect()` всегда шёл в
  `requestNetwork()`, который начинается с `release()` — а для сети, поднятой
  через `WifiNetworkSpecifier`, снятие запроса и есть разрыв (сценарий D).
  Штатный путь «Главная → Маршрут → Превью → Поехали» на уже подключённом
  дэше проходил ровно здесь. Хуже разрыва было то, что следом
  `findAlreadyConnectedDashNetwork()` успевал увидеть ещё не снесённый SSID и
  делал `markConnected()` **без регистрации коллбэка** — линк умирал, а
  `DashWifiManager` оставался в `CONNECTED` и не мог получить `onLost`
  (полевой лог 2026-09-07 21:38:29: «Requesting WiFi» → «Using
  already-connected matching WiFi» → `ENETUNREACH` на следующей же отправке).
  **Закрыто** (2026-09-07) гардом в обоих местах: `connect()` контроллера
  выходит сразу, если сессия жива и WiFi `CONNECTED`, а
  `DashWifiManager.connect()` сохраняет действующий запрос, если уже держит
  сеть с подходящим SSID.
