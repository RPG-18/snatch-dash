# Мультимедиа на приборке — оригинальный автомат и текущий порт

§1–7 описывают статически установленное поведение Royal Enfield App,
§8 — реализованный порт, §9 — ещё не закрытые аппаратные вопросы.
Исследование 10.1.22 от 04.10.2026 сохранено; источники и уточнения
сверки 22.10.2026 для **10.1.23(45), versionCode 193** перенесены из
[§1.1–1.2 плана](../plans/active/2026-10-04-multimedia-support.md#12-что-перепроверено-и-где-искать-в-новом-apk).
Новой декомпиляции или аппаратной проверки при обновлении этой спеки не было.
APK: `re_app_new/base.apk`, пакет `com.royalenfield.reprime`;
SHA-256: `889b9c42f21ef426c114cadac788c9c6220a29dc686e8a5b0e3fbd07a0e4f404`.
Это **чужое** поведение, а не требования к переносу его дефектов.

**Источники 10.1.23:** корни Java — `re_app_new/jadx_out/sources/`,
smali — `re_app_new/apktool_out/`; локальные выгрузки не входят в git.

| Узел | Текущий anchor (старое имя 10.1.22) |
|---|---|
| Service | `com/royalenfield/reprime/ui/home/navigation/service/REForeGroundService.java`: `x/J/y/S/A`, callback `b`, timer `d.run`; smali и `$b/$d` в `smali_classes8/com/royalenfield/reprime/ui/home/navigation/service/` |
| Metadata bytes | `bluconnect/l3d.java:n` (`pbh.n`), service `A` |
| Art / очередь | `bluconnect/c3c.java:a/c/d/e/f` (`nbg`, MetaDataUtils); `y2c.o` → `sw2.d/run` (sender `zx4`) |
| Bitmap / другой metadata path | `bluconnect/djf.java:e1/h1/z1/A1` (`x9k`) |
| Кнопки / constants / listener | `bluconnect/clk.smali:S,c0/D/E/F/d0` (`aaq.S`; Java `S` повреждён декомпилятором); `w2c` (`hbg`), `fpd` (`igi`) |
| Android actions / тип | `NavigationRootFragment.B,S1/q0,Y/i9,t9`; `bluconnect/zif.b` (`t9k.b`) |

Имена без полного пути ниже относятся к этим Java/smali.
Соответствие старых и новых имён семантическое, не побайтная идентичность.
`djf.A1` (раньше `x9k.A1`) — другой metadata path, не service `A`;
его условия нельзя смешивать с сервисом.

**На железе эти выводы не проверены.** Прошивка оригинала неизвестна;
наша проверенная версия — 11.63, но это не подтверждение её медиа-поведения.
Статически доказаны действия приложения, а не отображение, удержание карточки
или допустимые форматы конкретной приборки. Текущий порт отдельно описан в §8;
аппаратные решения — в [плане поддержки мультимедиа](../plans/active/2026-10-04-multimedia-support.md).

Формат пакетов, таблица TLV и полная карта кодов джойстика —
[`docs/k1g_commands_RU.md`](../docs/k1g_commands_RU.md). Здесь только медиа и
только поведение.

## 1. Что сюда не относится

Полезнее начать с вычитания, потому что тут наша документация ошибалась.

Раз в секунду оригинал шлёт на дэш блок статуса телефона. В нём есть поля,
которые выглядят как медийные и таковыми не являются:

| TLV | Что на самом деле | Источник |
|---|---|---|
| `06 01` | есть ли сотовый сигнал (`01`/`00`) | `TelephonyManager.getAllCellInfo()`, уровень > 0 |
| `06 03` | включён ли GPS (`55`/`AA`) | `LocationManager.isProviderEnabled(GPS)` |
| `06 04` | **заряд батареи + 100** | `BatteryManager.BATTERY_PROPERTY_CAPACITY` |
| `06 0F` | заряжается ли телефон (`55`/`AA`) | `BATTERY_CHANGED`, статус `CHARGING`/`FULL` |

`06 04` — это батарея, а не громкость. Прежняя редакция
[`docs/k1g_commands_RU.md`](../docs/k1g_commands_RU.md) называла его громкостью
медиа и приводила как подтверждение расчёт «`0xA2` = 162 → громкость 62».
Арифметика верна, вывод нет: 62 — это процент заряда. `06 03` и `06 0F` там же
числились «булевыми флагами медиа» — они про GPS и зарядку.

Громкость на дэш тоже уходит, но другими TLV — см. §4.

## 2. Участники

| Роль | Оригинал | Наш аналог |
|---|---|---|
| Держатель гранта на уведомления | `NotificationService` — **пустой** `NotificationListenerService` | `OpenDashNotificationListener` |
| Владелец сессии и отправитель | `REForeGroundService` | `MediaInfoProvider` → `MediaForwarding` → single writer `DashSession` |
| Интерфейс команд плеера | `fpd` (`H`, volume, next/previous) | методы `MediaInfoProvider`, public Dart API |
| Нарезка обложки | `c3c` (раньше `nbg`) | `AlbumArtEncoder` + `AlbumArtFrames` |
| Диспетчер кнопок | `clk.S()` → helpers | ACK в `DashSession`, действия `DashButtonController` (Dart) |

`NotificationService` не содержит ни одного метода. Он существует ровно затем,
чтобы у приложения был компонент, которому пользователь выдаёт доступ к
уведомлениям, — без этого `MediaSessionManager.getActiveSessions(component)`
кидает `SecurityException`. Тот же приём у нас.

## 3. Сессия, метаданные и воспроизведение

### 3.1 Привязка и жизненный цикл

`REForeGroundService.x()` требует тип подключения `INFOTAINMENT` (§7),
регистрирует слушатель команд **до** проверки доступа к уведомлениям
и получения **первой** сессии из `getActiveSessions(NotificationService)`.
Без доступа `x()` возвращается без media-пакета. Пустой список не очищает
старый token/controller; на холодном старте получается два `05 17 AA`.
`J()` работает только при null-controller: регистрирует callback, отправляет
`05 17 55`, затем непосредственно вызывает `A(currentMetadata)`.
Этот начальный снимок обходит counter, но не dedup; `A(null)` даёт inactive.
Явного воспроизведения текущего `PlaybackState` при привязке здесь нет.

`y()` снимает callback, очищает контроллер, токен, `AudioManager` и слушатель
команд, затем повторяет инициализацию. Его вызывает глобальный `erg.a()`
(«Music event received from REbaseActivity»). Это не доказательство автоматического
обнаружения смены сессии: у оригинала нет `OnActiveSessionsChangedListener`
и обработчика `onSessionDestroyed`, аналогичных нашему провайдеру.
`x()` также вызывается при каждом `onStartCommand`, но новый token при
ненулевом controller не гарантирует rebind из-за условия `J()`.

Фаза счётчика callback и кэш дедупликации переживают перепривязку.
`S()` не снимает callback/command listener; `onDestroy()` вызывает только super.
Повторный start может завести дублирующие таймеры. Это дефекты жизненного цикла
оригинала, не требуемый контракт порта; наш provider отписывает старый controller,
сбрасывает per-token cache/counter и отвергает callbacks старого generation (§8).

### 3.2 Метаданные

Callback увеличивает счётчик на **каждом** `onMetadataChanged`, включая `null`:
первый вызов пропускается, на втором счётчик сбрасывается **до** обработки.
Обрабатываемый `null` игнорируется, а не очищает состояние.
В `A(MediaMetadataCompat)`:

- Только если `title`, `artist` и `album` **все null**, отправляется
  `05 17 AA` и обработка заканчивается. Пустая строка не равна null.
- Дедупликация пропускает карточку, когда ненулевой `mediaId` совпадает
  без учёта регистра и ненулевой **сырой** `title` совпадает с учётом регистра.
  Проверка выполняется до получения обложки: поздняя обложка, новый исполнитель,
  альбом или признак рекламы при той же паре могут остаться незамеченными.
  Кэш не сбрасывается при stop/rebind оригинала.
- Обычный tuple: `(album, title, artist)`. При null/empty title fallback —
  `('', 'Now Playing', '')`; whitespace остаётся текстом.
  Ненулевой `METADATA_KEY_ADVERTISEMENT` со значением не 0 даёт
  `('Advertisement', '', '')`; all-null проверяется раньше advertisement.
- Raw title/id cache обновляется после thumbnail, но **до** отправки;
  ошибка отправки не откатывает cache.
- `IllegalStateException` fallback **узкий**: service smali `A`:598–729 —
  assembly/enqueue карточки, marker, art conversion/send и recycle.
  Getters, thumbnail, dedup/cache и выбор tuple находятся вне catch.
  Ветка 733–800 добавляет только карточку `('NowPlaying', '', '')`:
  текст **в поле альбома**, без пробела; без active/marker/clear-art и без
  отмены уже поставленных в очередь карточки и обложки.

Успешная обработка отправляет карточку `05 0D`, затем маркер `05 58 AA`
без обложки либо `05 58 55` и чанки `05 40` с обложкой. Она **не**
предваряется `05 17 55`; active отдельно отправляет привязка или playback callback.
Это событийная отправка с двумя фильтрами, а не гарантия «один пакет на трек».

Wire-карточка: `album \0 title \0 artist`, **без завершающего NUL**.
Каждое поле обрезается до **19 UTF-16 code units**, а не 19 UTF-8 байт.
`l3d.n` (раньше `pbh.n`) кодирует каждый Java `char`: `<=255` — один байт,
иначе `FF`. Это не UTF-8: `Я` превращается в `FF`, emoji из surrogate pair —
в `FF FF`; граница обрезки может разделить пару.

### 3.3 Воспроизведение

`onPlaybackStateChanged` всегда сначала отправляет `05 17 55`, **включая null**.
Только `STATE_PLAYING (3)` добавляет `05 19 55`, а `STATE_PAUSED (2)` —
`05 19 AA`. Остальные состояния не обновляют индикатор. Код не доказывает,
что прошивка сохраняет его прежний вид.

## 4. TLV медиа и громкость

| TLV | Значение | Когда в оригинале |
|---|---|---|
| `05 0D` | `album \0 title \0 artist`, §3.2 | начальный снимок / обработанные метаданные |
| `05 17` | `55` активно · `AA` не активно | привязка, playback callback, пустая сессия/все поля null |
| `05 19` | `55` играет · `AA` пауза | playback callback |
| `05 58` | `55` далее обложка · `AA` нет | после карточки |
| `05 40` | чанк JPEG | после маркера обложки |
| `05 4C` | `STREAM_MUSIC` (3) | блок статуса, 1 Гц |
| `05 1B` | `STREAM_VOICE_CALL` (0) | блок статуса, 1 Гц |

Точная шкала в `REForeGroundService$d.run`:
`D = (max - min) / 10f`; **min не вычитается из current**.
При `current <= 0` получается `10`. Затем проверяются по порядку включительные
интервалы `[0D,1D] → 11`, `[1D,2D] → 12`, …, `[8D,9D] → 19`,
последний `[9D,11D] → 1A`. На общей границе выигрывает первый интервал.
Это Float-сравнения без clamp; положительное значение вне интервалов
(в том числе при `D=0`) оставляет соответствующий TLV **отсутствующим**.
Ненулевой min и верхняя граница `11D` — особенности оригинала, не нормализованная
формула порта и не требование воспроизводить его ошибки.
Header count оригинала условный: timer `d.run` выбирает 9/10/11 по наличию
cell signal и температуре-сентинелу 200. Этот выбор не учитывает возможную
потерю volume TLV. Порт сохраняет фиксированные 11 TLV и fallback (§8).

`05 0D`, `05 40`, `05 22` собираются побайтово вне таблицы констант `w2c`.
Полный инвентарь команд принадлежит [`docs/k1g_commands_RU.md`](../docs/k1g_commands_RU.md).

## 5. Обложка альбома

`c3c.c` режет данные, `a` строит первый чанк, `e` — продолжения;
`d` упаковывает BE16, `f` ставит пакет в очередь и спит.
Service читает только `ALBUM_ART`, затем использует
`ThumbnailUtils.extractThumbnail(art, 75, 75)`,
`Bitmap.createScaledBitmap(…, 75, 75)`, JPEG quality 50.
Вспомогательный `djf.h1` recycle-ит вход до компрессии scaled Bitmap;
`e1` игнорирует boolean `compress`, затем есть повторные recycle.
Alias входа/scaled — статический риск, не наблюдавшийся crash APK.
Безопасное владение Bitmap порта описано отдельно (§8).

Значение первого `05 40`: `00 | total_hi total_lo | до 1000 байт JPEG`.
Размер всего JPEG — BE16 только в первом значении. Продолжения:
`idx | до 1000 байт`, индексы с 1; `FF` обозначает последний.
Для размера 1…1000 оригинал после первого отправляет пустой `FF`.
Для нескольких чанков с неполным последним `FF` **несёт остаток**,
дополнительного пустого терминатора нет.

Ошибки оригинального slicer: пустой массив приводит к выходу за границы;
при размерах ≥2000, кратных 1000, теряются последние 1000 байт;
размер >65535 обрезается в BE16. Порт исправляет эти дефекты (§8);
приемлемость исправленного framing для физической приборки ещё не доказана (§9).

`c3c.f` делает `Thread.sleep(10)` после **каждого** enqueue, включая последний.
Отдельный отправитель очереди `y2c.o` → `sw2.d/run` работает независимо:
это не доказательство 10 мс между пакетами **на проводе** и не измерение
длительности передачи. Кадровый цикл порта здесь не участвует.

В NorthStar `DashCommands.albumArtFrames` помечен как неподтверждённый и
не вызывается: его комментарий о двухбайтовом префиксе не описывает разобранный
первый чанк (три служебных байта) и продолжения (один).

## 6. Управление с джойстика

Статическая карта `clk.S()` (раньше `aaq.S()`); физическая карта 11.63 — отдельный вопрос.

| код | действие оригинала |
|---|---|
| `03` / `04` | `AudioManager`: громкость voice call ±1, `FLAG_PLAY_SOUND` |
| `05` | `fpd.H()` / service `H`: playing → pause, любое другое non-null playback state → play |
| `06` / `07` | controller.adjustVolume(±1, `FLAG_SHOW_UI=1`) |
| `08` | mute/unmute `STREAM_MUSIC` через `AudioManager`, flags 0 |
| `09` / `0A` | transport controls: skipToNext / skipToPrevious |

В ветках `05/06/07/09/0A` диспетчер проверяет **слушатель команд**, не
MediaController. При наличии слушателя сначала вызывается действие, затем
отправляется ACK `06 80 <код>`. Контроллер может быть null: действие будет
no-op, но ACK всё равно уйдёт. При null-слушателе нет ни действия, ни ACK.
Play/pause также no-op при null playback state. Next/previous требуют
контроллер, но не проверяют playing.
Также должен совпасть полный префикс команды; если действие бросит исключение,
следующий за ним ACK не выполняется. Наличие слушателя не означает наличие
notification access или сессии: он регистрируется раньше этих проверок (§3.1).

Mute независим от контроллера: `isStreamMute(STREAM_MUSIC)` выбирает
`ADJUST_UNMUTE=100` либо `ADJUST_MUTE=-100`, flags 0.
Ветка `08` отправляет ACK после действия. Код `18` («start music» в логе)
вызывает `NavigationRootFragment.Y/i9` — отправку счётчика пропущенных звонков `06 0A`, без ACK;
эта надпись не доказывает запуск плеера.

Обработчики громкости не отправляют значение `05 4C`/`05 1B`, лишь ACK.
Обе шкалы опрашивает таймер; обработчика `VOLUME_CHANGED_ACTION` или
`ContentObserver` на `Settings.System.VOLUME_*` в оригинале не найдено.
Следовательно, код предполагает обратную связь следующим тактом, а не сразу;
вне условия запуска таймера новое значение этим путём не отправляется.

## 7. Каденции и тип подключения

| Что | Период | Условие |
|---|---|---|
| Блок статуса, обе громкости | 1 Гц | тип подключения приложения `INFOTAINMENT` |
| Карточка | событие | начальный снимок / callback с фильтрами §3.2 |
| Active/playback | событие | §3.1–3.3 |
| Обложка | событие | вместе с обработанной карточкой |

`INFOTAINMENT` (`zif.b`, раньше `t9k.b`) — **тип подключённого устройства в приложении**
из семейства `NONE/TRIPPER/INFOTAINMENT`, а не входящий режим UI прошивки.
`SsidScanActivity.onNetworkConnected` устанавливает этот тип;
его распространяет `NavigationRootFragment.t9` (раньше setter `y9`).
Этим типом ограничены инициализация оригинала и запуск heartbeat. Искать отдельную входящую
firmware-команду для объяснения этого условия не требуется.
Второй 1 Гц таймер `P()` → задача `e` не имеет найденных вызовов и не считается
действующим путём.

Карточка не повторяется каждую секунду. Код оригинала рассчитывает на
удержание состояния приборкой, но не доказывает его на нашей прошивке.

## 8. Что из этого есть у нас

Текущие источники:
[`MediaInfoProvider`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/media/MediaInfoProvider.kt),
[`MediaEventStream`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/media/MediaEventStream.kt),
[`MediaAdapters`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/media/MediaAdapters.kt),
[`AlbumArtEncoder`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/media/AlbumArtEncoder.kt),
[`MediaForwarding`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/MediaForwarding.kt),
[`DashSession`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/DashSession.kt),
[`LiveHeartbeat`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/LiveHeartbeat.kt),
`DashEngineController`, `K1GCodec.displayText`.
Граница Dart: [`Pigeon schema`](../packages/opendash_dash_engine/pigeons/dash_engine.dart),
[`DashEngine`](../packages/opendash_dash_engine/lib/opendash_dash_engine.dart);
потребитель кнопок — [`DashButtonController`](../lib/state/dash_button_controller.dart).
С 23.10.2026 нативная модель media и событийный single-writer wire-путь реализованы;
физическое отображение остаётся отдельным аппаратным гейтом.
Таблица ниже описывает фактическую отправку из сессии, не весь доступный codec API.

| | оригинал | snatch-dash сейчас |
|---|---|---|
| `05 0D` | событийно с фильтрами | при принятом metadata event + прежний 1 Гц из последнего snapshot |
| поля | album/title/artist | title/album/artist |
| обрезка / кодировка | 19 UTF-16 units / lossy char→byte | ICU-транслитерация в ASCII, до 20 байт после расширения |
| `05 17` | событийно | bind/playback/inactive, snapshot replay и явный media stop |
| `05 19` | playing/paused | known playback event/replay; unknown не отправляет indicator |
| `05 58` / `05 40` | маркер и JPEG | metadata event/replay, paced single writer; hardware support не подтверждён |
| `05 4C` / `05 1B` | опрос AudioManager | live STREAM_MUSIC / STREAM_VOICE_CALL; независимые fallback `13` / `19` |
| play/pause, volume, mute | карта §6 | public Dart direct API; mapping кнопок остаётся совместимым до hardware gate |
| next/previous | transport controls | реализованы |

С 23.10.2026 protocol layer содержит типизированные `MediaActive`,
`MediaPlayback`, `AlbumArtMarker`, `AlbumArtChunk` и параметры music/call volume
в `Heartbeat`. Media events проходят через session writer только в STREAMING;
defaults heartbeat и захваченный стартовый бёрст не изменены.
`AlbumArtFrames` формирует chunks вне Android API: singleton заканчивается
отдельным пустым `FF`, multichunk — `FF` с данными, включая полные 1000 байт
при exact multiple; пустой JPEG и размер >65535 дают no frames.
Логический `AlbumArt` scalar codec отвергает: отправка требует paced writer,
а не пачки packets из одного вызова `encode`.
`VolumeLevel` использует нормализованную целочисленную шкалу с widened arithmetic,
не Float-формулу оригинала. С 23.10.2026 controller `currentHeartbeat` через
`LiveHeartbeat` читает AudioManager current/min/max отдельно для music и call.
Некорректный диапазон/null и exception дают captured fallback только своего
stream: `13` для music, `19` для call; отсутствие audio service даёт оба fallback.
Следующий tick снова читает live levels, не сохраняя ошибку или старое значение.
Silence кодируется `10`, а не fallback. Чтение выполняется в 1 Гц supplier,
не в frame/encoder loop; codec получает только готовые байты уровней.
Нормализованные значения и fallback проверены 23.10.2026 на AudioManager
эмулятора API 36; отображение громкости на приборке этим не подтверждено.
JVM-тесты и smoke JPEG→packets→JPEG проверяют serialization и сохранность данных,
но не отображение на приборке и не cadence передачи.

Provider владеет `StateFlow<MediaSnapshot>` и `SharedFlow<MediaEvent>`.
Snapshot содержит монотонную revision, active, nullable playing и metadata с
готовым JPEG; второй playback в metadata не хранится. Один monitor сериализует
bind/stop/callbacks и публикацию snapshot. Одна coroutine в scope controller
передаёт события из неограниченного FIFO в стандартный `MutableSharedFlow`:
медленный collector задерживает доставку, но не Android callback и не теряет
события; цена — удержание очереди в памяти до чтения или отмены scope.
`collectWithSnapshot` сначала регистрирует collector, затем под monitor читает
snapshot, отдаёт его один раз и пропускает только events с revision не новее
этой границы. Изменения во время приостановленного replay остаются в очереди.
Обычный `events` не replay-ит состояние. Provider останавливается до отмены scope.

Выбирается первая активная сессия; повторный start/same-token bind не регистрирует
дубли. Перед rebind старый callback снимается, token/cache/counter сбрасываются.
Callbacks несут generation: поздний callback после switch, destroy или stop,
включая stop→start, не восстанавливает старое состояние. Android callbacks
приходят через явный main Handler. Нет notification access — нет регистрации.

Initial metadata обходит counter, но проходит dedup. Каждый metadata callback,
включая null, увеличивает counter: первый пропускается, второй обрабатывается
и сбрасывает counter. Обработанный null и all-null title/album/artist очищают
metadata, playback и raw cache и публикуют inactive; all-null проверяется до
advertisement. Empty/whitespace не равны null. Dedup — nonnull raw id без учёта
регистра + nonnull raw title с учётом регистра, до advertisement/art. При той же
паре поздняя обложка, album/artist/ad отдельно не принимаются.
Normal без title или с пустым title получает title `Now Playing`, пустые album/artist;
whitespace остаётся как есть. Numeric advertisement≠0 получает album
`Advertisement`, пустые title/artist. Совместимый wire-порядок полей не меняется.
Metadata не порождает дополнительный Active; bind и playback публикуют его отдельно.
PLAYING/PAUSED дают playing=true/false, OTHER/null playback callback — active=true,
playing=null, без сохранения прошлого known state. Начальный known playback
также попадает в snapshot/events; это политика порта, не доказательство replay оригинала.

`AlbumArtEncoder` сохраняет порядок выбора `ALBUM_ART → ART → DISPLAY_ICON`,
thumbnail/scaled 75×75 и JPEG quality50. Заимствованный Bitmap не recycle-ится;
собственные thumbnail/scaled освобождаются после компрессии, с учётом aliasing.
Null/failed-compress/empty/exception/>65535 дают no art, сохраняя принятую metadata.
Ошибки framework getters до encoder оставляют прежний snapshot и не превращаются
в legacy send fallback. Raw id/title cache коммитится до публикации event.
Граница `dispatchMediaMetadata` подключена к enqueue в session writer:
ловит только `IllegalStateException` внутри enqueue и добавляет card-only tuple
с album `NowPlaying`, не отменяя уже поставленные эффекты или raw cache.

Native controls используют текущий Android controller: PLAYING → pause, любой
другой nonnull playback → play, null controller/playback → no-op.
Volume меняет controller volume на ±1 с FLAG_SHOW_UI; mute переключает
AudioManager STREAM_MUSIC без controller gate. Ошибки adapters дают тихий no-op;
next/previous сохраняют Boolean результат. Новые permission/FGS не добавлены.

`MediaForwarding` — один owner в controller: сначала применяет provider snapshot
и последующие revision events к chrome/inputs, публикует title, затем заказывает
wire sequence. Monitor сериализует обновление media + enqueue и переход STREAMING;
вложенный синхронный callback откладывается до конца текущей транзакции.
Collector регистрируется до provider.start; события сводятся по порядку,
а не чтением более нового provider snapshot при обработке старого event.
Bind Active несёт `clearMetadata`: reset очищает старые metadata/playback,
обычный playback Active их не стирает.

Session при переходе READY→STREAMING ставит snapshot replay перед публикацией
нового состояния, чтобы reentrant observer не мог обогнать его event-пакетом.
Replay: active/inactive → текущая card → marker → art → known playback; только
ненулевой playing даёт `05 19`. Последующие events передаются один раз.
Replay-derived indicator ждёт завершения art; обычный playback event проходит
между chunks сразу. Новый unknown/known event отменяет отложенный прежний replay
indicator; одна лишь замена metadata не теряет ещё актуальный known replay.
Наличие metadata для replay не зависит от active: обработанный metadata callback
сам по себе не обязан повторять Active.

Writer хранит текущий art transfer, не помещая весь transfer в delay-loop.
Следующий chunk планируется не раньше +10 мс от завершения последнего art send,
включая границу A→B; между chunks обслуживаются projection, ACK и heartbeat.
Новое принятое metadata отменяет только неотправленные chunks A, сохраняя FIFO
принятых card/marker events. Уже начатый packet может закончиться, но после card B
ни одного chunk A нет: B с JPEG начинает новый transfer, B без JPEG даёт absent
marker. Callback, подавленный provider dedup, не создаёт события и не отменяет art.
Periodic card читает только metadata, уже принятую session вместе с invalidation,
а не промежуточный chrome B до enqueue: иначе тик мог бы отправить card B раньше
отмены art A. Совместимый 1 Гц и порядок остальных полей тика сохранены.

Media stop сначала инвалидирует owner generation, очищает chrome, inputs и
публикуемый title, затем явно заказывает inactive через ещё живой STREAMING writer;
после этого controller отменяет collectors и вызывает provider.stop. Доставка
inactive не зависит от уже отменённого collector. Writer инвалидирует и queued
event/periodic cards, и art; уже начатый packet заканчивается до inactive.
Даже пустой chrome может иметь старую queued legacy card (title→null), поэтому
owner учитывает media, заказанное после последнего inactive. Повторный stop
не добавляет второго inactive; late callback не восстанавливает state.
Teardown отбрасывает pending art/media; после закрытия transport финальная доставка
inactive не гарантируется и не повторяется. Обычный reconnect активной поездки
provider не останавливает и replay-ит сохранённый snapshot.

`DashSession.open` требует heartbeat supplier: controller передаёт
`::currentHeartbeat`, вызываемый на каждом 1 Гц tick, в том числе до STREAMING.
Меняются только `05 4C` и `05 1B`: температура остаётся 25 °C, порядок/состав
11 TLV и captured initial burst неизменны. Нет `INFOTAINMENT` gate.
Pigeon-схема публикует `mediaActive` и единственный nullable `mediaPlaying` в конце
`DashEngineState`. Controller переносит title/active/playing из chrome вместе
в один `DashInputs` snapshot и публикует их без подстановки false или последнего
known значения. Idle/stop — false/null; OTHER/null playback — true/null, даже
при пустом title. Dart получает эти значения через обычный `stateStream`;
до первого события `idleDashState` также false/null.

Четыре public метода `DashEngine.mediaPlayPause`, `mediaVolumeUp`, `mediaVolumeDown`,
`mediaMute` возвращают `Future<void>` и идут через platform interface,
method-channel implementation и сгенерированный HostApi в native provider.
Они доступны независимо от button mapping. С 23.10.2026 после приоритета
входящего звонка (`06` answer) и активного звонка (`07` hangup) код `05`
вызывает `mediaPlayPause`, `06` — `mediaVolumeUp`, `07` — `mediaVolumeDown`
только при `mediaActive=true`, независимо от title и `mediaPlaying`.
При inactive эти три кода поглощаются без действия: не переключают треки
и не зумируют. Native toggle ставит PLAYING на паузу, возобновляет PAUSED/OTHER;
null playback остаётся no-op. Старые track/zoom aliases `05/06/07` удалены.
Для `09/0A` и next-alias `22` сохранён gate `nowPlayingTitle != null`
(включая `''`): next/previous при title, иначе zoom. `13/14` всегда зумируют.
Mute/call-volume mapping и новый gate для `09/0A` не внедрены.

Основание узкого cutover — подтверждение пользователем 23.10.2026 физического
намерения: вверх увеличивает громкость музыки, вниз уменьшает, центр ставит
на паузу. Это не повторная проверка исправленной сборки на приборке и не
закрытие всего hardware gate; evidence старого mapping — в Findings checklist.
Парные `07→05` не фильтруются по времени: одно это нажатие или два, не установлено.

По зафиксированным результатам этапа 5 в плане, 23.10.2026 на API 36 проверена
граница из публичного Dart façade:
реальный MediaSession, play/pause, remote volume ±1, mute без controller,
active с пустым title, PLAYING→OTHER=null, PAUSED→null=null и stop=false/null.
Smoke включал только media forwarding без подключения к Wi-Fi дэша;
fixture активировал обычный notification-listener access на отдельном пакете.
Это не подтверждение wire-кнопок или отображения на физической приборке:
отправка inactive/marker сама по себе не доказывает очистку или рендер карточки.

По результатам этапа 2 в плане, 23.10.2026 на Android-эмуляторе API 36
проверены реальные Bitmap/JPEG и
привилегированный smoke с настоящим MediaSession: ART fallback, начальный snapshot,
pause/OTHER/play, skip/volume, metadata parity, stop/late metadata и mute без
controller. Это не проверка notification-access UI и не подтверждение приборки.

Порядок, 1 Гц и статическая часть heartbeat сохранены. С 20.10.2026 по требованию
пользователя текст транслитерируется системным Android ICU; границы и error
contract описаны в [протоколе](k1g_protocol.md#кодирование-текста-на-приборке).
Четыре однобайтных charset пользователь отклонил; рабочая кириллица в прошивке
не установлена. Это не разрешение менять media order/FSM/retention по декомпиляции.
ACK-before-forward в порте — существующий намеренный контракт, отличающийся
от action-before-ACK оригинала: `DashSession` заказывает `ButtonAck` single writer
до публикации `SessionEvent.Button`. Это порядок enqueue/forward, а не гарантия,
что UDP ACK уже физически принят приборкой к моменту выполнения Dart action.

## 9. Открытые вопросы для железа

Проверки — [`docs/ON_HARDWARE_VERIFICATION_RU.md`](../docs/ON_HARDWARE_VERIFICATION_RU.md), §6.
Независимые вопросы для прошивки 11.63:

- Порядок album/title/artist против title/album/artist: различимые строки,
  не одинаковое название трека и альбома.
- Допустимая ширина полей и роль разделителей: 19 против 20 ASCII-байт
  после транслитерации. Отсутствие финального NUL у оригинала доказано кодом,
  его необходимость на нашей прошивке — нет.
- Читаемость транслитерации русских имён и расширяющихся сочетаний, Latin-1,
  неподдержанных символов и среза; проверка ширины не заменяет проверку глифов.
- Удержание карточки без 1 Гц, pause/inactive, последней обложки и очищение
  при смене/потере сессии; взаимодействие медиа с видеопотоком и картой.
- Поддержка JPEG/маркера обложки, пустого терминатора для singleton и финального
  чанка с данными, включая полные 1000 байт; предел размера и нужный pacing.
  Отдельно — прерывание multichunk A принятыми metadata B с JPEG и без JPEG:
  отсутствие chunks A после card B на wire не доказывает отсутствие зависания
  или возврата A на экране; нужны pcap и наблюдение приборки.
- Повторная проверка исправленного `05/06/07` mapping на приборке, включая
  call precedence; оставшаяся карта `03/04/08`, новый gate `09/0A` и эффект `18`.

Источник `INFOTAINMENT` установлен статически (§7); это не открытый вопрос
о границе firmware-режима. Проверка взаимодействия media/video всё ещё нужна.
