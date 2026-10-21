# План работ: поддержка мультимедиа на приборке

**Дата создания:** 04.10.2026

**Статус:** предложение; реализация не начата. План подготовлен для поэтапной
работы с автоматическими и полевыми гейтами.

## 0. Как читать этот план

Этапы выполняются по порядку. После каждого этапа исполнитель обязан показать
результат проверки и остановиться на границе этапа: зелёные целевые тесты,
`lintDebug` там, где затронут native-код, и отдельный коммит после ревью. Если
порядок меняется, причина записывается в §0.1.

Метки:

- `[auto]` — проверяется локальными тестами, анализом, сборкой или throwaway
  smoke-сценарием;
- `[ride]` — окончательно закрывается только человеком на физическом дэше;
  автоматические тесты не могут пометить такой пункт выполненным.

Легенда статуса одинакова для всех задач:

- `✅` — сделано и проверено;
- `❌` — отклонено по существу;
- `↩️` — было сделано и откачено по полевому результату, с обязательной причиной;
- без отметки — не начато.

Каждая отметка получает дату и ставится только после соответствующей проверки.
Задача с выполненным пунктом «что опровергнет посылку» снимается, а не
переформулируется.

Нельзя менять без отдельного доказательства с железа: текущий wire-контракт
`05 0D` (`title, album, artist`, ASCII-транслитерация Android ICU с пределом
20 байт после расширения, без завершающего NUL после artist), повтор раз в секунду,
текущий порядок конфликтующих кнопок,
порядок heartbeat TLV и отсутствие отдельного media foreground service. До
hardware gate добавляются новые подтверждённые по декомпиляции пути, но эти
совместимые ветви сохраняются.
Compatibility фиксируется существующими `K1GGoldenTest`/button tests; новые
wire формы проверяются отдельно. Unresolved hardware не снимается успехом auto.

## 0.1. Статус

Где план сейчас (04.10.2026): реализация не начата. ✅ Исследование оригинала
по Java и авторитетному smali завершено 04.10.2026; факты и источники —
[`spec/multimedia.md`](../../spec/multimedia.md). Это не проверка порта или железа.

Уточнение 20.10.2026: media-автомат этого плана ещё не реализован, но текстовая
baseline изменена отдельным требованием пользователя. После отклонения
windows-1251/ISO-8859-5/KOI8-R/IBM866 выбран ASCII через Android ICU, не
дальнейший поиск кириллической кодировки. Контракт и границы —
[`spec/k1g_protocol.md`](../../spec/k1g_protocol.md#кодирование-текста-на-приборке).
SDK-route/codec проверены на Android-эмуляторе; читаемость новой сборки на
приборке ещё не подтверждена. Остальные hardware-гейты остаются открыты.

Отложенные решения и отклонённые посылки:

- порядок, ширина `05 0D`, удаление 1 Hz-повтора и mapping кнопок —
  независимые решения после проверки прошивки 11.63; encoding baseline с
  20.10.2026 — ICU-транслитерация, не прежний UTF-8;
- ❌ 04.10.2026 универсальный отдельный пустой `FF` после любого art:
  оригинал отправляет его только для singleton; при ненулевом остатке финальный
  `FF` несёт данные. Дефект exact-multiple оригинала сознательно не переносить;
- ❌ 04.10.2026 поиск firmware-mode boundary `INFOTAINMENT`: это тип
  подключения приложения (`NONE/TRIPPER/INFOTAINMENT`), не входящий UI/media-mode.
  Задача на guessed source снята; взаимодействие media/video проверяется отдельно;
- при недоступном железе завершается только автоматическая часть; план остаётся
  active, `[ride]` явно открыты в спеке и обоих чек-листах.

## 1. Контекст за пять минут

[`spec/multimedia.md`](../../spec/multimedia.md) хранит исходные факты оригинала,
допроверенные 04.10.2026 по Java/smali, но не подтверждённые на железе.
Текущий порт отправляет media-карточку `05 0D` (`title, album, artist`,
ICU → ASCII, до 20 байт на поле после расширения) раз в секунду при `nowPlaying != null`.
`05 17`, `05 19`, `05 58` и `05 40` не имеют
рабочего event-пути; поле `NowPlaying.art` вычитывается, но не используется.
Heartbeat отправляет захваченные константы `05 4C = 0x13` и `05 1B = 0x19`.

Текущая нативная граница:

- `MediaInfoProvider` отдаёт nullable `nowPlaying` и умеет только skip
  next/previous;
- `DashEngineController` запускает media collector после `mediaInfo.start()`;
- `DashSession` имеет один control writer и общий 250 ms/1 Hz tick, но не имеет
  media-event очереди или snapshot replay;
- Pigeon-схема содержит state без media-флагов и API без прямых media-control
  методов;
- Dart mapping конфликтующих кнопок не меняется до hardware gate.

Выбран гибридный rollout: добавить типизированные media events, wire helpers,
обложку, live-volume heartbeat и прямые native/Dart controls, но сохранить
совместимую карточку до проверки 11.63. Lifecycle остаётся active-ride-only,
а `DashKeepAliveService` остаётся владельцем процесса и foreground-сервиса.

## 2. Инварианты, которые нельзя нарушить

1. Все control packets, включая media events, проходят через единственный
   `DashSession` writer/outbox. Chunking обложки не выполняется в frame/encoder
   loop.
2. `05 0D` до hardware gate кодируется как `title + NUL + album + NUL +
   artist`: ICU-транслитерация до 20 ASCII-байт на поле; после artist NUL нет.
3. Периодический порядок 1 Hz не меняется: route/nav, call notify/clear,
   совместимая `05 0D`, heartbeat. Media events вставляются в момент события и
   не обходят writer.
4. `Heartbeat` сохраняет 11 TLV, их порядок, `segCount = 11`, статические
   `06 01/03/04/0F` и захваченные burst-значения. Динамичны только `05 4C` и
   `05 1B`.
5. `VolumeLevel.encode(current: Int, min: Int, max: Int): Int?`: invalid
   `min < 0 || max <= min` → null; иначе `current <= 0` → `0x10`.
   Для положительного current: clamp в `min..max`, корзина
   `ceil(10 * (clamped-min)/(max-min))`, clamp корзины `1..10`, результат
   `0x10 + bucket`. Вычислять расширенной целочисленной арифметикой.
   Это защитная нормализованная шкала, не Float-формула оригинала. Null/exception
   getter → captured fallback соответствующего stream `0x13/0x19`, не silence.
6. Art: пустой JPEG или >65535 → no frames; first value
   `00 + BE16(total) + до 1000 image bytes`. Singleton 1..1000 получает отдельный
   пустой `FF`; multichunk получает финальный `FF + последние image bytes`,
   включая полные 1000 при exact multiple, без дополнительного пустого `FF`.
   Индексы промежуточных chunks возрастают от 1. Empty/exact-multiple/overflow
   дефекты оригинала исправляются намеренно; физическая совместимость ещё открыта.
7. Current snapshot и подписка/replay линеаризуются одним owner или revision seam:
   initial replay один раз при STREAMING; events до границы уже входят в snapshot,
   events после неё передаются ровно один раз, включая concurrent updates во время
   replay. Не подавлять все последующие events. Reconnect сохраняет snapshot
   активной поездки; active-ride stop очищает его и pending art.
8. Старый controller отписывается до rebind; collector существует до start.
   Per-token counter/cache сбрасываются при switch/stop (отклонение от оригинала);
   initial metadata snapshot обходит counter. Каждый callback, включая null,
   увеличивает counter; первый пропускается, второй обрабатывается и сбрасывает
   counter. Обработанный null очищает state (отклонение от оригинала).
9. Dedup: nonnull mediaId без учёта регистра + nonnull raw title с учётом регистра,
   до art; late art/artist/album/advertisement changes могут быть потеряны.
   Только все три поля null означают inactive; пустые строки — не null.
   Оригинальные tuple `(album,title,artist)`: normal; no-title
   `("", "Now Playing", "")`; advertisement nonzero `("Advertisement","","")`;
   IllegalStateException `("NowPlaying","","")` (album slot, не обязательно clear art).
   Framework exceptions проверять через seam отдельно от безопасного art encoder.
10. Единственный authoritative nullable playback — `DashChrome.mediaPlaying`;
    NowPlaying не содержит второго playing. PLAYING/PAUSED → active + indicator;
    OTHER/null callback → active без нового indicator. Initial known playback
    replay — требование порта, не доказанный initial replay оригинала.
11. Один writer планирует следующий art chunk не ранее чем через 10 ms после
    предыдущего, но обслуживает pending projection/buttons/heartbeat между chunks;
    не держит весь transfer внутри delay-loop, не использует Thread.sleep.
    4 Hz и ACK latency проверяются по timestamps с max-size art, не по count.
12. Call answer/reject precedence и текущие `_isLooseNextButton`/
    `_isLoosePreviousButton` не меняются до hardware gate. Новые direct API
    доступны независимо от текущего remap.
    ACK-before-forward — намеренный существующий контракт порта, сохраняется,
    хотя оригинал выполняет action-before-ACK. Будущий official mapping использует
    stateful `mediaActive`, не непустой title; mute не требует controller.

Механическая проверка: `K1GGoldenTest`, pure tests helpers, `DashSessionTest`,
provider/reducer tests, Dart button tests, `flutter analyze`,
`:opendash_dash_engine:lintDebug` и полный JVM test suite. Смысл байтов,
рендер карточки, удержание состояния и официальная таблица кнопок дополнительно
проверяются только `[ride]`.

## 3. Этапы

### Этап 1 — Wire-контракт и чистые helpers [auto]

**Цель:** расширить типизированный protocol layer без изменения совместимого
`05 0D` и periodic ordering.

Задачи:

- [ ] `[auto]` Добавить `DashCommand.MediaActive(active: Boolean)`,
  `MediaPlayback(playing: Boolean)`, `AlbumArtMarker(present: Boolean)`,
  логический `AlbumArt(jpeg: ByteArray)` и scalar `AlbumArtChunk(value: ByteArray)`;
  `Heartbeat(tempC: Int = 25, musicVolume: Int = 0x13, callVolume: Int = 0x19)`.
  **Что опровергнет посылку:** если wire capture или существующий golden
  показывает, что один из новых TLV имеет другую форму, не закреплять форму
  типом без отдельного разбора.
- [ ] `[auto]` Scalar `K1GCodec.encode(cmd: DashCommand): ByteArray` кодирует
  MediaActive → `05 17 55/AA`, MediaPlayback → `05 19 55/AA`,
  AlbumArtMarker → `05 58 55/AA`, AlbumArtChunk → `05 40 value`.
  Логический AlbumArt раскрывается writer до scalar encoder; не выдавать
  несколько packets из старого ByteArray API. Сохранить текущий `05 0D`
  порядок, ICU-транслитерацию с лимитом 20 ASCII-байт и отсутствие финального NUL.
  **Что опровергнет посылку:** если существующие `05 0D` goldens меняются без
  hardware gate, реализация нарушила compatibility branch.
- [ ] `[auto]` `AlbumArtFrames.encode(jpeg: ByteArray): List<DashCommand.AlbumArtChunk>`
  реализует контракт §2.6 без Android типов и без marker; marker заказывает caller.
  **Что опровергнет посылку:** independent capture/screen отвергает исправленный
  exact-multiple framing — фиксировать отдельную branch, не подгонять golden.
- [ ] `[auto]` Реализовать nullable `VolumeLevel.encode` строго по §2.5,
  отдельно от оригинальной Float-шкалы, проверить widened arithmetic.
  **Что опровергнет посылку:** hardware отвергает нормализованную шкалу —
  записать отклонение/evidence, не выдавать её за формулу оригинала.
- [ ] `[auto]` Добавить literal goldens для media markers, art frame cases и
  heartbeat с независимыми music/call levels; существующие `05 0D` goldens не
  переписывать.
  **Что опровергнет посылку:** если golden получается вызовом production
  encoder, тест не считается независимым и должен быть переделан.

**Готово:** protocol tests зелёные, старые goldens не изменились, новые helper
boundary cases покрыты literal expected bytes.

### Этап 2 — Native MediaSession state и controls [auto]

**Цель:** заменить nullable metadata flow на событийную модель с последним
snapshot и безопасными Android adapters.

Задачи:

- [ ] `[auto]` В `MediaModels.kt` добавить `MediaPlayback`, metadata/media
  snapshots и `MediaEvent` (`Active`, `Inactive`, `Metadata`, `Playback`).
  **Что опровергнет посылку:** если reducer не может выразить разницу между
  отсутствием медиа и неизвестным playback state, модель недостаточна.
- [ ] `[auto]` Provider публикует `SharedFlow<MediaEvent>` и
  `StateFlow<MediaSnapshot>` с atomic snapshot/subscription revision seam (§2.7).
  В этом же зелёном этапе мигрировать ВСЕ реальные `DashEngineController`
  callers с nowPlaying на snapshot: минимально forward metadata в нынешнюю
  periodic card, collectors до start; full wire events остаются этапу 3.
  Удалить старый nowPlaying API, не оставлять shim.
  **Что опровергнет посылку:** caller остался на удалённом API или snapshot
  невозможно получить без следующего callback — cutover не завершён.
- [ ] `[auto]` Реализовать bind/rebind/destroy/stop transitions, выбор
  `sessions.firstOrNull()`, active + current metadata/playback публикацию и
  тихий no-op без notification access.
  **Что опровергнет посылку:** если старый callback способен публиковать после
  смены token, порядок unsubscribe/register считается сломанным.
- [ ] `[auto]` Реализовать callback/caches/null-vs-blank и точные fallback tuples
  по §2.8–9; не смешивать альтернативный старый `x9k.A1` с service path.
  **Что опровергнет посылку:** direct initial snapshot теряется из-за counter
  или null и empty имеют один результат — reducer не соответствует контракту.
- [ ] `[auto]` `AlbumArtEncoder.encode(bitmap: Bitmap?): ByteArray?` через seam:
  thumbnail/scaled75×75, JPEG quality50; null/empty/exception/>65535 → null,
  metadata сохраняется. Framework failure не смешивать с оригинальным
  IllegalStateException tuple; safeencoder result тестировать отдельно.
  **Что опровергнет посылку:** если Android framework не позволяет стабильно
  тестировать bitmap conversion на JVM, оставить framework call behind seam и
  тестировать reducer/result contract.
- [ ] `[auto]` Provider `playPause(): Unit`, `volumeUp(): Unit`,
  `volumeDown(): Unit`, `mute(): Unit`: null controller/playback → playPause no-op;
  PLAYING → pause, любой другой nonnull → play; volume controller.adjustVolume
  ±1 с FLAG_SHOW_UI=1; mute через AudioManager STREAM_MUSIC, isStreamMute →
  ADJUST_UNMUTE(100), иначе ADJUST_MUTE(-100), flags=0, без controller gate.
  Ошибки adapters → тихий no-op; не добавлять media FGS.
  **Что опровергнет посылку:** mute без controller не работает или exception
  выходит наружу — adapter нарушает контракт.

**Готово:** provider/reducer tests покрывают no access, no session, bind/rebind,
dedup, advertisement, fallback, PLAYING/PAUSED/OTHER, destruction и stop;
Android live `MediaSession` не нужен для JVM tests.

### Этап 3 — Single-writer session и media forwarding [auto]

**Цель:** провести events через существующий session writer, сохранить reconnect
semantics и 1 Hz fallback.

Задачи:

- [ ] `[auto]` В `DashChrome.NowPlaying` добавить `mediaId`, `artJpeg`;
  в chrome `mediaActive` и единственный nullable `mediaPlaying`, без дублирования
  playing в NowPlaying; сохранить `NowPlaying("Song")` совместимым.
  **Что опровергнет посылку:** две копии playback расходятся — модель неверна.
- [ ] `[auto]` `DashSession.sendMedia(commands: List<DashCommand>): Unit`
  принимает wire events только в STREAMING; controller всегда сначала обновляет
  current chrome даже до STREAMING. startStreaming атомарно получает revision
  snapshot и replay active→card→marker→art→known playback один раз; последующие
  revisions передаются ровно один раз, concurrent update не теряется/не дублируется.
  **Что опровергнет посылку:** before/during/after transition interleaving теряет
  update или повторяет initial replay — ordering seam недостаточен.
- [ ] `[auto]` Writer раскрывает AlbumArt в scalar chunks и incrementally
  назначает earliest-next-send +10 ms (§2.11). Между chunks выбирает pending
  projection/button ACK/heartbeat, один coroutine/socket writer, без Thread.sleep
  и без блокировки всей очереди transfer-ом. Cancel pending art при inactive,
  stop и session teardown; следующая сессия не получает хвост старого art.
  **Что опровергнет посылку:** timestamp tests max65535 показывают projection gap
  >250 ms или ACK ожидает весь transfer — scheduling не подходит.
  Scalar codec не принимает логический AlbumArt (precondition failure).
  Тестировать реальную последовательность packets и её отмену/порядок, а не
  копии аргументов или forwarding между helpers.
- [ ] `[auto]` В `DashEngineController.startMediaForwarding()` сначала поднять
  collectors, затем вызвать `mediaInfo.start()`; обновлять chrome, inputs,
  publishState и queue event sequences по правилам плана.
  **Что опровергнет посылку:** если initial snapshot теряется при старте
  collector, порядок запуска доказанно неверен.
- [ ] `[auto]` В `stopMediaForwarding()` отменять jobs, вызывать provider stop,
  очищать chrome, `DashInputs` и Dart state. Периодическую `05 0D` до hardware
  gate сохранить; `05 17 AA` прекращает новые карточки после остановки.
  **Что опровергнет посылку:** если после teardown следующий session получает
  старую карточку или playback state, cleanup неполон.
- [ ] `[auto]` Обязательный `heartbeat: () -> DashCommand.Heartbeat` в
  `DashSession.open`: мигрировать все production/test factories в этом этапе.
  Supplier пока возвращает captured `Heartbeat(25,0x13,0x19)`; этап 4 заменяет
  его live provider. Initial burst остаётся captured.
  **Что опровергнет посылку:** tick создаёт heartbeat сам или factory не
  передаёт supplier — seam не внедрён сквозным образом.

**Готово:** tests покрывают before/during/after STREAMING races, genuine later
updates, reconnect сохранение и active-ride teardown очистку, старую 1 Hz card,
timestamp projection interval ≤250 ms и button receipt→ACK ≤250 ms при 65535 art
на virtual clock/неблокирующем fake transport; art intervals ≥10 ms.

### Этап 4 — Live volume heartbeat [auto]

**Цель:** читать системные уровни громкости только в controller seam, а codec
оставить Android-independent.

Задачи:

- [ ] `[auto]` Controller `currentHeartbeat(): DashCommand.Heartbeat` с
  AudioManager: STREAM_MUSIC→05 4C, STREAM_VOICE_CALL→05 1B, temp25;
  nullable VolumeLevel/exception каждого stream независимо fallback13/19.
  Подключить live supplier вместо captured этапа3.
  **Что опровергнет посылку:** если `AudioManager` отсутствует или getter
  бросает exception, tick loop не должен падать и должен отправить fallback.
- [ ] `[auto]` Live supplier работает на каждом streaming heartbeat; не вводить
  firmware INFOTAINMENT gate (снятая посылка §0.1).
  **Что опровергнет посылку:** доказанное ограничение протокола по реальному типу
  подключения требует отдельного решения, не guessed incoming mode.

**Готово:** focused heartbeat/session tests видят независимые live levels,
статические TLV не изменились, invalid AudioManager path не роняет tick loop.

### Этап 5 — Pigeon boundary и Dart/native façade [auto]

**Цель:** расширить единственную схему границы и провести media state/controls
до публичного Dart API без ручного редактирования generated files.

Задачи:

- [ ] `[auto]` Schema: добавить `required bool mediaActive`, `bool? mediaPlaying`
  в конец state, idle false/null. Ровно четыре HostApi methods:
  `void mediaPlayPause(); void mediaVolumeUp(); void mediaVolumeDown(); void mediaMute();`
  Native overrides `fun mediaPlayPause(): Unit` и аналогично три остальных;
  public façade/platform/method-channel: `Future<void> mediaPlayPause()` и
  одноимённые `mediaVolumeUp()`, `mediaVolumeDown()`, `mediaMute()` без аргументов.
  **Что опровергнет посылку:** generated-first изменение запрещено; начать со schema.
- [ ] `[auto]` Запустить `dart run pigeon --input pigeons/dash_engine.dart` из
  package directory; generated Dart/Kotlin outputs только регенерировать.
  **Что опровергнет посылку:** если генератор меняет несвязанные части, сначала
  проверить версию/конфигурацию Pigeon, а не править generated output вручную.
- [ ] `[auto]` Обновить `DashInputs`, `publishState()`, plugin implementation,
  public Dart façade, platform interface и method-channel implementation;
  state fields отражают chrome, methods делегируют provider.
  **Что опровергнет посылку:** если один из generated method channels не имеет
  реализации на обеих сторонах, boundary считается незавершённой.
- [ ] `[auto]` Мигрировать ВСЕ implements/extends-platform fakes, включая
  `packages/opendash_dash_engine/test/opendash_dash_engine_test.dart`
  `MockOpendashDashEnginePlatform`, root button fake и error-reporting fake;
  direct API проверять throwaway smoke через публичный façade независимо от
  принятого mapping, без постоянных mock-echo тестов. Stateful mediaActive gate
  для будущего official mapping покрыть behavioral tests отдельно; текущий
  mapping/precedence оставить до ride. Duplicate events/disposal сохраняют смысл.
  **Что опровергнет посылку:** если текущие duplicate-event/disposal tests
  меняют смысл или два одинаковых события дают одну action, регрессия должна
  быть устранена до дальнейших этапов.

**Готово:** schema/generated outputs компилируются, state/API доступны через
public Dart façade, fake-platform tests зелёные; generated source text не
закрепляется отдельными хрупкими тестами.

### Этап 6 — Полное автоматическое покрытие и документация [auto]

**Цель:** закрыть поведение, которое можно доказать без физического дэша, и
синхронизировать текущую документацию после реализации.

Задачи:

- [ ] `[auto]` Literal art tests: 0, 1, 1000, 1001, 2000, 2001, 3000, 65535,
  65536 bytes, headers/indexes/full final chunks/no extra terminal. Volume:
  invalid/null fallback, silence, nonzero min, exact boundaries, clamp, Int limits.
  **Что опровергнет посылку:** если тест проверяет только размер/не-throw, он
  не доказывает wire invariant и должен быть переписан на bytes.
- [ ] `[auto]` Добавить literal media/heartbeat goldens и `DashSessionTest`
  assertions, сохранив button-ack-before-forward assertion.
  **Что опровергнет посылку:** если тесты проходят при обходе outbox или frame
  loop, они не проверяют single-writer invariant и не принимаются.
- [ ] `[auto]` Добавить native provider/reducer tests и Dart button tests для
  call precedence, active/paused/no-media и пустого title; direct API проверить
  throwaway smoke. Проверки не зависят от live `MediaSession` или source text.
  **Что опровергнет посылку:** mock echo/wiring-only tests не считаются покрытием
  поведения и удаляются.
- [ ] `[auto]` Обновить `spec/multimedia.md` §8/§9 только фактами текущей
  реализации и явно оставить hardware-dependent claims открытыми.
  **Что опровергнет посылку:** если факт известен только из codec/unit test,
  его нельзя записывать как hardware support.
- [ ] `[auto]` Обновить русскую и английскую копии hardware checklist только
  инструкциями/ожиданиями, не отмечая пункты как подтверждённые.
  После ride обновить findings/evidence в обеих копиях (этап 7); сверить
  `docs/k1g_commands_RU.md`/`docs/k1g_commands.md` со spec.
  **Что опровергнет посылку:** отсутствие evidence с firmware 11.63 запрещает
  `[x]`.

**Готово:** focused tests, full native tests, Dart tests, analyze и lint
проходят; spec/checklists различают автоматически доказанное и непроверенное на
железе.

### Этап 7 — Hardware gate на прошивке 11.63 [ride]

**Цель:** выбрать заранее определённые compatibility branches по наблюдению на
физическом дэше, а не по codec tests.

Подготовка:

- собрать release или profile APK с local defines; записать SHA, fixture flags,
  телефон, firmware 11.63, notification access и baseline из `docs/devices/`.
  Profile timings читать с оговорками `ride-verification`; debug не доказывает
  производительность. Снимать отдельный pcap по `hack/PACKET_CAPTURE.md`;
- подготовить контролируемый media fixture с разными треками A/B, явно различными
  album/title/artist (`ALBUM_A`, `TITLE_A`, `ARTIST_A`), известным art и playback;
  variants меняют ровно одну переменную, не являются production cutover;
- каждый опыт сохраняет pcap плюс наблюдаемую картинку дэша/действие телефона
  (видео/датированная запись), ride diag и timestamps; pcap alone недостаточен.

Задачи:

- [ ] `[ride]` Field order: при неизменных width20/ICU→ASCII/periodic сравнить
  title,album,artist с album,title,artist на unambiguous A/B и записать подписи.
  **Что опровергнет посылку:** original order не улучшает/портит отображение —
  оставить нынешний order независимо от других опытов.
- [ ] `[ride]` Width: при фиксированных order/encoding сравнить 19/20 ASCII-байт
  после транслитерации, длины 18/19/20/21 в каждом поле, отличимые финальные символы.
  **Что опровергнет посылку:** 19 теряет допустимый символ без улучшения —
  сохранить20, не связывать решение с order/retention.
- ❌ 20.10.2026 `[ride]` Снят прежний опыт Encoding: сравнить UTF-8 и original
  char≤255 → byte, иначе FF, с ASCII/Latin1/кириллицей/emoji.
  Причина: пользователь изменил требование после четырёх отрицательных charset
  опытов — нужна библиотечная транслитерация. Это не доказательство работоспособности
  lossy encoder оригинала и не подтверждение русских глифов. Новая baseline —
  ICU→ASCII; её проверка на приборке не разрешает менять order/width/retention.
- [ ] `[ride]` Retention: fixture ЯВНО отключает periodic `05 0D`, отправляет
  одну карточку A, выдерживает 30 s без metadata callbacks, затем B,
  pause/resume и reconnect. Сравнить с periodic variant.
  **Что опровергнет посылку:** card исчезает/устаревает без repeat — оставить1 Hz.
  Обычный APK с 1 Hz не доказывает retention.
- [ ] `[ride]` Отдельно проверить inactive `05 17 AA`, PLAYING/PAUSED indicator,
  no-art→art→no-art, singleton/multichunk/exact2000 art и media/video interaction.
  **Что опровергнет посылку:** marker/art игнорируется или ломает карту —
  отклонить соответствующий path по screen+pcap, не менять соседние branches.
- [ ] `[ride]` Независимые music/call volume min/max/silence, включая mute без
  controller; max65535 art совместно с projection и button ACK: сравнить с baseline
  без art, timestamp cadence 250 ms, receipt→ACK ≤250 ms плюс baseline jitter,
  heartbeat gap ≤1 s плюс тот же измеренный jitter, art spacing≥10 ms.
  **Что опровергнет посылку:** новая шкала/transfer даёт неправильный volume
  или gaps сверх baseline — отклонить конкретную ветвь, сохранить captured fallback.
- [ ] `[ride]` Official buttons проверять ТОЛЬКО явно gated fixture mapping:
  `03/04` call-volume, `05` play/pause, `06/07` music volume, `08` mute,
  `09/0A` next/previous; active/no-media/ringing call и call precedence.
  Phone action и ACK-before-forward проверить вместе с pcap; mediaActive
  определяет gate, пустой title не выключает его. Старый mapping APK не
  доказывает новый mapping.
  **Что опровергнет посылку:** любое расхождение оставляет текущий mapping,
  direct API доступны независимо; не принимать alias как доказательство.
- [ ] `[ride]` Записать findings и обновить обе checklist copies, не объявляя
  codec behavior hardware verified.
  **Что опровергнет посылку:** недоступное железо оставляет все ride items
  unresolved и не блокирует завершение automatic work, но запрещает закрывать
  весь план.

Предопределённые branches независимы:

- order подтверждён → изменить только order, иначе сохранить текущий;
- width19 подтверждена → только `MEDIA_FIELD_MAX=19`, иначе20;
- encoding — сохранить ICU→ASCII baseline; новый charset допустим только по новому требованию и отдельному evidence;
- retention без periodic подтверждён → удалить только repeat, иначе1 Hz;
- каждый marker/playback/art path принят или отклонён отдельно по evidence;
  best-effort не означает hardware verified;
- official buttons подтверждены → применить mapping/stateful gate; иначе
  сохранить нынешний mapping; call precedence и ACK-before-forward неизменны;
- live volumes и cadence принимаются отдельно; нет firmware-mode ветви.

**Готово:** ВСЕ `[ride]` оценены: приняты либо явно отклонены с evidence,
отдельными pcap + screen/phone observation, firmware/phone/fixture SHA. Unresolved
можно перечислить при завершении automatic work, но не при закрытии всего плана.

## 4. Правила работы для исполнителя

- Изменять Pigeon сначала в schema; generated `messages.g.dart` и
  `Messages.g.kt` руками не править.
- Не добавлять новый foreground service, разрешение или `INFOTAINMENT`-источник
  без evidence.
- Не переносить album-art chunking в frame/encoder loop и не использовать
  `Thread.sleep` в control writer.
- Не менять текущие button aliases, call precedence или `05 0D` fallback до
  hardware gate.
- Сохранять KDoc с датами и объяснениями полевых находок; не заменять их
  пересказом очевидного кода.
- Перед каждым commit запускать `/code-review` по diff; находки либо исправить,
  либо явно отклонить с причиной в ответе.
- Для Kotlin использовать JDK 21 JBR из Android Studio, не Homebrew OpenJDK 26.
- Если тест просит изменить literal expected bytes, сначала проверить wire
  contract и независимость теста; менять ожидание можно только по evidence.
- `[ride]` закрывает человек. Исполнитель готовит сборку, pcap-инструкцию,
  критерии успеха/провала и обновляет документы только по наблюдаемому факту.

## 5. Что считать успехом всего плана

План считается полностью завершённым только если:

1. native protocol, helpers, provider, session writer, reconnect replay,
   dynamic heartbeat и Pigeon/Dart controls реализованы и покрыты независимыми
   автоматическими тестами;
2. root `flutter test`, package `flutter test`, `flutter analyze`, focused/full
   JVM tests и `:opendash_dash_engine:lintDebug` проходят на JDK21 JBR;
3. release/profile phone smoke показывает отсутствие crash/stale state и
   timestamp cadence/ACK bounds при max art, с измеренным baseline jitter;
4. каждый `[ride]` принят либо явно отклонён по evidence firmware11.63
   (pcap И наблюдение screen/phone); unresolved означает план всё ещё active,
   даже если automatic work завершена;
5. независимые branches order/width/encoding/retention/markers/art/buttons/live
   volumes/media-video совпадают с evidence. Решения, spec и обе checklist copies
   обновлены; без hardware нельзя объявить полный план завершённым.

## Критические файлы и anchors

- `packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/protocol/DashCommand.kt`
- `packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/protocol/DashMessage.kt`
- `packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/media/MediaInfoProvider.kt`
- `packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/DashSession.kt`
- `packages/opendash_dash_engine/pigeons/dash_engine.dart`
- `spec/multimedia.md`
- `docs/ON_HARDWARE_VERIFICATION_RU.md`
- `docs/ON_HARDWARE_VERIFICATION.md`
- `docs/k1g_commands_RU.md`, `docs/k1g_commands.md`

## Проверки и команды

```sh
cd packages/opendash_dash_engine
flutter pub get
dart run pigeon --input pigeons/dash_engine.dart
flutter test

cd ../../android
./gradlew :opendash_dash_engine:testDebugUnitTest \
  --tests '*K1GGoldenTest*' --tests '*DashSessionTest*'

cd ..
flutter test test/state/dash_button_controller_test.dart
flutter test
flutter analyze

cd android
./gradlew :opendash_dash_engine:lintDebug
./gradlew :opendash_dash_engine:testDebugUnitTest
```

Для performance smoke: из корня `flutter build apk --profile
--dart-define-from-file=android/dart_defines.local.properties` либо `--release`
вместо `--profile`; fixture variants собрать тем же способом и записать flags.
Debug допустим для диагностического доступа, не для performance conclusions.
Автотесты доказывают serialization/order, не firmware interpretation,
retention, art rendering или buttons; hardware evidence оформляется на этапе 7.
