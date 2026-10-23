# План работ: поддержка мультимедиа на приборке

**Дата создания:** 04.10.2026

**Статус:** этапы 1–4 реализованы и проверены 23.10.2026; этап 5 не начат.
Исследование обновлено 22.10.2026 по `re_app_new` — Royal Enfield 10.1.23(45), versionCode 193.
План сохраняет независимые автоматические и полевые гейты.

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

Состояние на 22.10.2026: реализация не начата. Исследование от
04.10.2026 относилось к 10.1.22; его факты и источники остаются в
[`spec/multimedia.md`](../../spec/multimedia.md). Повторная статическая сверка
10.1.23(45) и границы доказательств записаны ниже. Это не проверка порта
или железа; задачи реализации и `[ride]` не отмечаются выполненными.

Уточнение 20.10.2026: media-автомат этого плана ещё не реализован, но текстовая
baseline изменена отдельным требованием пользователя. После отклонения
windows-1251/ISO-8859-5/KOI8-R/IBM866 выбран ASCII через Android ICU, не
дальнейший поиск кириллической кодировки. Контракт и границы —
[`spec/k1g_protocol.md`](../../spec/k1g_protocol.md#кодирование-текста-на-приборке).
SDK-route/codec проверены на Android-эмуляторе; читаемость новой сборки на
приборке ещё не подтверждена. Остальные hardware-гейты остаются открыты.

Сверка 22.10.2026: основные формы media TLV подтверждены в 10.1.23(45).
Уточнены дефект exact-multiple art, область `IllegalStateException` fallback,
initial dedup, lifecycle и владение Bitmap. Новые anchors — §1.2; намеренные
отличия порта уточнены в §2 и этапах 2/4/7. ICU→ASCII, совместимая карточка,
1 Hz и текущие кнопки не пересматриваются по одной декомпиляции.

Уточнение по ревью 23.10.2026: определены замена незавершённой обложки при
принятии новых metadata, unknown playback state и владелец inactive при stop.
Отмена старой обложки и `mediaPlaying=null` для OTHER/null playback выбраны
как политика порта, не как вывод из декомпиляции. Реализация не начата;
правки уточняют контракт плана и критерии будущих проверок, но не добавляют
автоматических или аппаратных подтверждений. Совместимость прерывания art
остаётся открытым `[ride]` этапа 7.

Состояние после этапа 1 (23.10.2026): wire-команды, scalar encoding,
`AlbumArtFrames` и `VolumeLevel` реализованы; media callbacks, paced writer,
live AudioManager и Pigeon ещё не подключены. Следующий этап — 2.
Проверены полный `:opendash_dash_engine:testDebugUnitTest` (413 тестов,
0 failures/errors/skipped), включая 20 новых тестов, и `lintDebug`
(0 ошибок, 20 предупреждений вне изменённых файлов).
Throwaway JVM smoke: JPEG 75×75, 3879 байт → 4 art packets → побайтно
идентичный декодируемый JPEG; отдельно выведены media markers и heartbeat
с независимыми уровнями громкости. Это не Android Bitmap/MediaSession smoke
и не аппаратное подтверждение; все `[ride]` остаются открыты.
Code review этапа 1: замечаний к production-коду и тестам не найдено.
Отклонение окружения: установленный Android Studio содержит JBR 25.0.3,
загрузка JBR 21 с redirector и прямого CDN завершилась таймаутами. Проверки
выполнены на установленном Temurin 21.0.7 с локальными `JAVA_HOME` и
`-Dorg.gradle.java.home`, без изменения настроек проекта или пользователя.

Состояние после этапа 2 (23.10.2026): provider переведён на
snapshot/events, controller — на snapshot до start; старый provider.nowPlaying
удалён. Реализованы lifecycle/generation guards, callback parity/dedup,
metadata tuples, nullable playback, native controls и безопасный JPEG encoder.
`collectWithSnapshot` регистрирует event collector до чтения revision snapshot;
одна scope-owned coroutine публикует события через `MutableSharedFlow`,
неограниченный FIFO сохраняет их для медленных consumers без дропа.
Provider.stop вызывается до отмены владеющего scope.
`dispatchMediaMetadata` проверяет узкую card-only fallback-границу отдельно;
её подключение к sender, как и paced art/playback/inactive packets, — этап 3.

Проверки этапа 2: полный JVM suite — 446 тестов (33 новых), без failures/errors/skipped;
Android API 36 — 16 успешных сценариев: 9 новых Bitmap/JPEG tests, 6 прежних
ICU text tests и временный live MediaSession smoke. Smoke проверил ART fallback
в JPEG 798 байт 75×75, initial snapshot, pause→null→OTHER→play, null playPause no-op,
skip/volume, metadata parity, stop/late metadata и mute без controller.
Для доступа к реальному MediaSession использованы shell permissions только
в smoke; notification-access UI не проверялся. Временный smoke удалён.
`lintDebug`: 0 ошибок, 20 прежних предупреждений вне изменений; новый WrongConstant
устранён явной проверкой null до `when(state)`, без suppression.
Два code review (state/revision и Android/Bitmap/controller) — без замечаний.
Проверки, как и на этапе 1, выполнены на Temurin 21.0.7 без изменения конфигурации.
Следующий этап — 3; Pigeon, live heartbeat и все `[ride]` ещё открыты.

Состояние после этапа 3 (23.10.2026): media events и initial replay
подключены к single writer. `MediaForwarding` сериализует chrome + enqueue
и STREAMING capture, включая вложенные синхронные callbacks; `Active` различает
bind reset и playback active. Session хранит art transfer/deadline отдельно
от FIFO принятых control/card/marker events; metadata supersession отменяет
неотправленный art, inactive/teardown — весь старый queued media.
Periodic metadata коммитится вместе с invalidation, чтобы тик не отправил
card B до отмены art A. Controller очищает локальное состояние и явно заказывает
inactive до отмены collectors/provider.stop. Обязательный heartbeat supplier
пока возвращает захваченные 25/13/19; live volume — следующий этап 4.

Проверки этапа 3: полный JVM suite — 488 тестов, без failures/errors/skipped;
42 новых packet-level теста (`DashSessionMediaTest` — 23,
`MediaForwardingTest` — 19). Управляемое время подтверждает art spacing ≥10 мс,
projection gap ≤250 мс и receipt→ACK ≤250 мс при 65535 байтах art, A→B/C,
unknown/reconnect, stop/teardown и replay/reentrant races.
Временный executable smoke на реальных localhost UDP-сокетах провёл handshake
и production owner→session→writer: A(65535)→B(JPEG 727 байт) без chunks A после
card B, JPEG B восстановлен побайтно; полный art 65535 дал 66 packets,
минимальный наблюдавшийся интервал 10,390 мс, button→ACK 1,231 мс;
после inactive нет старых card/art через следующий секундный tick.
Это замер локального транспорта, не firmware/rendering и не полевая гарантия cadence.
`lintDebug`: 0 ошибок, 20 прежних предупреждений вне изменений.
Проверки выполнены на Temurin 21.0.7; временный smoke удалён.

Code review writer — без замечаний. Ревью forwarding нашло stop с уже пустым
chrome, но старой queued legacy card после title→null: до исправления новый
packet regression отправлял card/marker вместо единственного inactive.
Исправлено учётом media, заказанного после последнего inactive; повторный stop
идемпотентен. Регрессия проходит, повторное ревью исправления — без замечаний.
Pigeon/Dart media-флаги и direct controls остаются этапу 5; все `[ride]` открыты.

Где план сейчас (23.10.2026, после этапа 4): controller `currentHeartbeat`
подключён к `LiveHeartbeat`, читающему current/min/max системного AudioManager.
Music/call нормализуются через `VolumeLevel` независимо; invalid/null/exception
переходит в captured fallback только своего stream (`13`/`19`).
Supplier вызывается на каждом 1 Гц heartbeat, в том числе до STREAMING,
без `INFOTAINMENT` gate; температура 25, 11 TLV и initial burst сохранены.

Проверки этапа 4: полный JVM suite — 489 тестов, без failures/errors/skipped.
Новый packet-level сценарий проверяет независимые live levels, ошибку music,
null/ошибку call, оба fallback и восстановление на следующем tick, сравнивая
полный heartbeat packet. Android API 36 — 16 успешных сценариев (15 прежних
Bitmap/text и временный real-AudioManager smoke): music=0/call=max → `10/1A`,
music=max/call=min(1) → `1A/11`, отсутствующий/throwing audio service → `13/19`;
во всех случаях 73 байта и неизменные 11 TLV. Уровни громкости после smoke
восстановлены; временный smoke удалён. Это не проверка отображения volume на дэше.
`lintDebug`: 0 ошибок, 20 прежних предупреждений вне изменений.
Проверки выполнены на Temurin 21.0.7. Следующий этап — 5; все `[ride]` открыты.
Code review этапа 4 — без замечаний.

Отложенные решения и отклонённые посылки:

- порядок, ширина `05 0D`, удаление 1 Hz-повтора и mapping кнопок —
  независимые решения после проверки прошивки 11.63; encoding baseline с
  20.10.2026 — ICU-транслитерация, не прежний UTF-8;
- ❌ 04.10.2026 универсальный отдельный пустой `FF` после любого art:
  для singleton оригинал добавляет пустой `FF`, при ненулевом остатке финальный
  `FF` несёт данные. Уточнение 22.10.2026: при exact-multiple ≥2000 оригинал
  тоже отправляет пустой `FF`, но теряет последние 1000 байт; этот дефект
  сознательно не переносить.
- ❌ 04.10.2026 поиск firmware-mode boundary `INFOTAINMENT`: это тип
  подключения приложения (`NONE/TRIPPER/INFOTAINMENT`), не входящий UI/media-mode.
  Задача на guessed source снята; взаимодействие media/video проверяется отдельно;
- при недоступном железе завершается только автоматическая часть; план остаётся
  active, `[ride]` явно открыты в спеке и обоих чек-листах.

## 1. Контекст за пять минут

[`spec/multimedia.md`](../../spec/multimedia.md) хранит исходные факты
10.1.22, допроверенные 04.10.2026 по Java/smali, но не подтверждённые на
железе. Для реализации использовать также сверку 10.1.23(45) в §1.1:
обфусцированные имена старой версии нельзя искать в новом APK буквально.
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

### 1.1 Источник и границы повторной сверки — 22.10.2026

Исследуемый APK: `re_app_new/base.apk`, пакет `com.royalenfield.reprime`,
версия **10.1.23(45)** / **193**, minSdk **31**, targetSdk **36**.
SHA-256: `889b9c42f21ef426c114cadac788c9c6220a29dc686e8a5b0e3fbd07a0e4f404`.

Выполнено REA **6.7.0**:

- `rea inspect-artifact re_app_new/base.apk --format json` — инвентарь APK
  и отпечаток; не доказательство семантики DEX;
- `REA_APKTOOL_COMMAND=/opt/homebrew/bin/apktool rea decode-android-resources
  re_app_new/base.apk --no-strings --format json` — Apktool **3.0.3**,
  подтверждены пакет/версия/SDK; декодирование идёт с `--no-src`;
- `rea inspect-android-package re_app_new/base.apk --format json` —
  `capability_unavailable`: отсутствует отдельно поставляемый
  `jadx-headless-mcp 0.7.1` (`REA_JADX_MCP_JAR`). Методная декомпиляция и
  xrefs через REA в этой сверке **не выполнены**.

Поведение проверяется по уже имеющимся `re_app_new/jadx_out/sources/` и
`re_app_new/apktool_out/`; спорные условия — по smali, не по псевдокоду
неудачно декомпилированного метода. `re_app_new/overview.md` — навигационная
заметка, не доказательство неизменности media-автомата. Приложение не
запускалось, wire capture и наблюдение приборки этой сверкой не получены.

### 1.2 Что перепроверено и где искать в новом APK

Старые имена ниже взяты из исследования 10.1.22; соответствие семантическое,
не утверждение побайтной идентичности двух версий. Корни: Java —
`re_app_new/jadx_out/sources/`, smali — `re_app_new/apktool_out/`.
Локальные выгрузки не входят в git; для повторения нужен APK с SHA-256 из §1.1.

| Узел | Источник 10.1.23(45) и anchors |
|---|---|
| Service path | [`REForeGroundService.java`](../../re_app_new/jadx_out/sources/com/royalenfield/reprime/ui/home/navigation/service/REForeGroundService.java): `x/J/y/S`, `A`, callback `b`, timer `d.run`; одноимённые `.smali`, `$b.smali`, `$d.smali` в `smali_classes8/com/royalenfield/reprime/ui/home/navigation/service/` |
| Metadata bytes | [`bluconnect/l3d.java`](../../re_app_new/jadx_out/sources/bluconnect/l3d.java), `n` (259–282), вместо `pbh.n`; service `A` (671–740) задаёт порядок/обрезку |
| Art framing / очередь | [`bluconnect/c3c.java`](../../re_app_new/jadx_out/sources/bluconnect/c3c.java), `a/c/d/e/f`, вместо `nbg`; `bluconnect/y2c.java:o` → `bluconnect/sw2.java:d/run`, вместо sender `zx4` |
| Bitmap / альтернативный metadata path | [`bluconnect/djf.java`](../../re_app_new/jadx_out/sources/bluconnect/djf.java), `e1/h1/z1` (272–308), вместо `x9k`; `djf.A1` — другой metadata path, не service `A` |
| Кнопки и TLV | [`bluconnect/clk.smali`](../../re_app_new/apktool_out/smali_classes8/bluconnect/clk.smali), `S` (1697–2070), вместо `aaq.S`; Java `S` повреждён декомпилятором. `bluconnect/w2c.java` — constants вместо `hbg`; `bluconnect/fpd.java` — listener вместо `igi` |
| Android actions / тип подключения | [`NavigationRootFragment.java`](../../re_app_new/jadx_out/sources/com/royalenfield/reprime/ui/home/navigation/fragment/NavigationRootFragment.java): `B` mute, `S1/q0` call-volume, `Y/i9` missed calls, `t9` connection setter; `bluconnect/zif.java:b` вместо `t9k.b` |

Выводы, влияющие на исполнение плана:

- **Карточка подтверждена, compatibility не отменена.** Service сериализует
  album/title/artist без последнего NUL; normal fields режет до 19 UTF-16 units,
  `l3d.n` превращает char ≤255 в байт, остальные — в `FF`. Это не ICU/ASCII20
  порта и не основание вернуть снятый charset-эксперимент.
- **Lifecycle оригинала нельзя переносить целиком.** `x()` (1057–1091)
  регистрирует listener до notification grant, берёт первую сессию; пустой
  список не очищает старый token/controller, а на холодном старте даёт два
  inactive. `J()` (811–822) работает только при null-controller: повторный
  start с новым token не гарантирует rebind. `y()` (1094–1107) снимает callback,
  но не counter/cache; `S()` (885–904) не снимает callback/command listener,
  `onDestroy()` (990–997) вызывает только super. Исправления — этап 2.
- **Initial и callback null различаются.** `J()` вызывает `A(metadata)`
  напрямую: counter обходится, dedup остаётся. `A(null)` даёт inactive;
  второй callback null сбрасывает counter, но не вызывает `A`.
  All-null проверяется до advertisement; whitespace не равен empty.
  Cache raw title/id обновляется после thumbnail, до отправки; ошибка отправки
  не откатывает cache. Callback `b`: Java 211–237, smali `$b`:49–152.
- **Fallback узкий.** Service smali `A`:598–729 охватывает assembly/enqueue
  карточки, marker, art conversion/send и recycle; getters, thumbnail,
  dedup/cache и выбор tuple находятся вне catch. Ветка 733–800 добавляет
  только `05 0D ("NowPlaying","","")`, без active/marker/clear-art и без
  отмены уже поставленных в очередь карточки и обложки.
- **Art encoder порта безопаснее оригинала.** Service берёт только
  `ALBUM_ART`; `djf.h1` recycle-ит вход до компрессии scaled Bitmap, `e1`
  игнорирует boolean результата `compress`, затем есть повторные recycle.
  Риск alias входа/scaled — статический, crash APK не наблюдался. Владение
  и обработка ошибок задаются отдельно в этапе 2. `c3c.f` спит 10 ms после
  enqueue, включая последний; независимый `sw2.run` не доказывает wire spacing.
- **Heartbeat оригинала условный.** Timer `d.run` (Java 275–460;
  smali `$d`:896–1037) выбирает header count 9/10/11 по наличию cell signal
  и температуре-сентинелу 200. Float-шкала не вычитает min из current и
  может вообще опустить volume TLV; выбор header эту потерю не учитывает.
  Фиксированные 11 TLV и fallback13/19 порта сохраняются, остальные поля
  heartbeat здесь не динамизируются.
- **Коды кнопок подтверждены, gates различаются.** `03/04` call-volume,
  `05` play/pause, `06/07` volume, `08` mute, `09/0A` next/previous.
  Новый play/pause — `fpd.H` / service `H` (785–805), не `I`: `I` теперь setter
  token. Пять helpers `clk.c0/D/E/F/d0` проверяют listener `zif.V()`, не
  title/controller/mediaActive, затем action и ACK. Null-controller допускает
  ACK после no-op; null-listener — нет ACK; исключение action прерывает ACK.
  `18` → `NavigationRootFragment.Y/i9` (5216–5219,6115–6118) отправляет
  missed-call count `06 0A`, а не запускает музыку; нового media API для него нет.
- **INFOTAINMENT не стал firmware mode.** Это enum `zif.b`, устанавливаемый
  `SsidScanActivity.G0` при сетевом подключении. `clk.Q/Z/a0` разбирают
  `09 04/06` отдельными навигационными путями, не устанавливают media mode
  и не служат основанием останавливать media collector. Совместный рендер
  и переходы media/video по-прежнему требуют этапа 7.

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
   `05 1B`. Это контракт порта: условные 9/10/11 TLV и omission громкости
   оригинала (§1.2) не переносить.
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
   initial metadata snapshot обходит counter, но проходит общий dedup.
   Каждый callback, включая null, увеличивает counter; первый пропускается,
   второй обрабатывается и сбрасывает counter. Обработанный null очищает state
   (отклонение от оригинала); прямой initial null также очищает state.
9. Dedup: nonnull mediaId без учёта регистра + nonnull raw title с учётом регистра,
   до art; late art/artist/album/advertisement changes могут быть потеряны.
   Только все три поля null означают inactive, даже при advertisement≠0;
   пустые строки — не null, whitespace — не empty. Advertisement отсутствует
   или равен 0 → normal; любой numeric nonzero → advertisement.
   Оригинальные tuple `(album,title,artist)`: normal; no-title
   `("", "Now Playing", "")`; advertisement `("Advertisement","","")`.
   Узкий IllegalStateException fallback отправки — `("NowPlaying","","")`
   в album slot, только карточка, без active/marker/clear-art (§1.2).
   Не распространять эту ветку на ошибки getters/thumbnail: они вне catch
   оригинала. Safe art encoder result и legacy fallback проверяются отдельно.
10. Единственный authoritative nullable playback — `DashChrome.mediaPlaying`;
    NowPlaying не содержит второго playing. Переходы:

    | Вход | mediaActive | mediaPlaying | Новая wire-последовательность playback |
    |---|---|---|---|
    | PLAYING | true | true | active + playing indicator |
    | PAUSED | true | false | active + paused indicator |
    | OTHER/null playback callback | true | null | active без `05 19` |
    | Inactive / media stop | false | null | inactive по правилам остановки; без `05 19` |

    Unknown callback очищает прежнее true/false, а не сохраняет последнее
    известное значение; `mediaPlaying=null` для OTHER/null — политика порта,
    не вывод из декомпиляции. Null playback callback не равен null metadata:
    counter/очистка metadata заданы отдельно в §2.8. Initial known playback
    replay — требование порта, не доказанный initial replay оригинала.
    Представление unknown не меняет control-контракт `playPause()` этапа 2:
    любой другой nonnull Android playback state вызывает play, null
    controller/playback даёт no-op.
11. Один writer планирует следующий art chunk не ранее чем через 10 ms после
    предыдущего, но обслуживает pending projection/buttons/heartbeat между chunks;
    не держит весь transfer внутри delay-loop, не использует Thread.sleep.
    4 Hz и ACK latency проверяются по timestamps с max-size art, не по count.
    Это scheduling-контракт порта: оригинал задерживает producer после enqueue,
    а не гарантирует 10 ms между отправками отдельного sender.
    Новое metadata, прошедшее dedup (§2.9), заменяет предыдущую передачу art:
    уже начатая запись одного packet A заканчивается, все ещё не отправленные
    chunks A отменяются до card B, включая запланированный отложенный chunk.
    После card B ни одного chunk A быть не должно. Для B с валидным JPEG:
    card B → present marker → передача B с первого кадра; без валидного JPEG:
    card B → absent marker, без art frames. Повторная смена B→C действует так же.
    Подавленный dedup callback ничего не отменяет; поздняя обложка при прежней
    паре id/title отдельно не принимается. Это политика порта, не вывод из
    декомпиляции; отдельная очередь/поток для замены art не вводится.
12. Call answer/reject precedence и текущие `_isLooseNextButton`/
    `_isLoosePreviousButton` не меняются до hardware gate. Новые direct API
    доступны независимо от текущего remap.
    ACK-before-forward — намеренный существующий контракт порта, сохраняется,
    хотя оригинал выполняет action-before-ACK. Будущий mapping использует
    stateful `mediaActive` для `05/06/07/09/0A`, не непустой title; это политика
    порта вместо original listener gate. Mute и call-volume этим media gate
    не ограничиваются; mute не требует controller.

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

- ✅ 23.10.2026 `[auto]` Добавить `DashCommand.MediaActive(active: Boolean)`,
  `MediaPlayback(playing: Boolean)`, `AlbumArtMarker(present: Boolean)`,
  логический `AlbumArt(jpeg: ByteArray)` и scalar `AlbumArtChunk(value: ByteArray)`;
  `Heartbeat(tempC: Int = 25, musicVolume: Int = 0x13, callVolume: Int = 0x19)`.
  **Что опровергнет посылку:** если wire capture или существующий golden
  показывает, что один из новых TLV имеет другую форму, не закреплять форму
  типом без отдельного разбора.
- ✅ 23.10.2026 `[auto]` Scalar `K1GCodec.encode(cmd: DashCommand): ByteArray` кодирует
  MediaActive → `05 17 55/AA`, MediaPlayback → `05 19 55/AA`,
  AlbumArtMarker → `05 58 55/AA`, AlbumArtChunk → `05 40 value`.
  Логический AlbumArt раскрывается writer до scalar encoder; не выдавать
  несколько packets из старого ByteArray API. Сохранить текущий `05 0D`
  порядок, ICU-транслитерацию с лимитом 20 ASCII-байт и отсутствие финального NUL.
  **Что опровергнет посылку:** если существующие `05 0D` goldens меняются без
  hardware gate, реализация нарушила compatibility branch.
- ✅ 23.10.2026 `[auto]` `AlbumArtFrames.encode(jpeg: ByteArray): List<DashCommand.AlbumArtChunk>`
  реализует контракт §2.6 без Android типов и без marker; marker заказывает caller.
  **Что опровергнет посылку:** independent capture/screen отвергает исправленный
  exact-multiple framing — фиксировать отдельную branch, не подгонять golden.
- ✅ 23.10.2026 `[auto]` Реализовать nullable `VolumeLevel.encode` строго по §2.5,
  отдельно от оригинальной Float-шкалы, проверить widened arithmetic.
  **Что опровергнет посылку:** hardware отвергает нормализованную шкалу —
  записать отклонение/evidence, не выдавать её за формулу оригинала.
- ✅ 23.10.2026 `[auto]` Добавить literal goldens для media markers, art frame cases и
  heartbeat с независимыми music/call levels; существующие `05 0D` goldens не
  переписывать.
  **Что опровергнет посылку:** если golden получается вызовом production
  encoder, тест не считается независимым и должен быть переделан.

**Готово:** protocol tests зелёные, старые goldens не изменились, новые helper
boundary cases покрыты literal expected bytes.
Проверено 23.10.2026: `K1GGoldenTest` без изменения старых ожиданий,
`K1GMediaGoldenTest`, `AlbumArtFramesTest`, `VolumeLevelTest`; результаты
полного JVM suite, lint и smoke — §0.1. Раскрытие логического `AlbumArt` и pacing
в session writer остаются этапу 3; scalar codec уже отклоняет такой вход.

### Этап 2 — Native MediaSession state и controls [auto]

**Цель:** заменить nullable metadata flow на событийную модель с последним
snapshot и безопасными Android adapters.

Задачи:

- ✅ 23.10.2026 `[auto]` В `MediaModels.kt` добавить `MediaPlayback`, metadata/media
  snapshots и `MediaEvent` (`Active`, `Inactive`, `Metadata`, `Playback`).
  **Что опровергнет посылку:** если reducer не может выразить разницу между
  отсутствием медиа и неизвестным playback state, модель недостаточна.
- ✅ 23.10.2026 `[auto]` Provider публикует `SharedFlow<MediaEvent>` и
  `StateFlow<MediaSnapshot>` с atomic snapshot/subscription revision seam (§2.7).
  В этом же зелёном этапе мигрировать ВСЕ реальные `DashEngineController`
  callers с nowPlaying на snapshot: минимально forward metadata в нынешнюю
  periodic card, collectors до start; full wire events остаются этапу 3.
  Удалить старый nowPlaying API, не оставлять shim.
  **Что опровергнет посылку:** caller остался на удалённом API или snapshot
  невозможно получить без следующего callback — cutover не завершён.
- ✅ 23.10.2026 `[auto]` Реализовать bind/rebind/destroy/stop transitions, выбор
  `sessions.firstOrNull()`, active + current metadata/playback публикацию и
  тихий no-op без notification access. Не переносить stale token/controller
  при пустом списке, двойной inactive холодного старта и отсутствие unregister
  при stop (§1.2); старый callback после switch/stop не меняет новый snapshot.
  **Что опровергнет посылку:** если старый callback способен публиковать после
  смены token, порядок unsubscribe/register считается сломанным.
- ✅ 23.10.2026 `[auto]` Реализовать callback/caches/null-vs-blank и точные fallback tuples
  по §2.8–9; не смешивать `bluconnect.djf.A1` (старое имя `x9k.A1`)
  с service `REForeGroundService.A`. Metadata event: card→marker→art при наличии,
  без дополнительного active; bind/playback публикуют active отдельно.
  Playback reducer следует §2.10: PLAYING→OTHER и PAUSED→null playback
  очищают прежнее true/false до `mediaPlaying=null`, сохраняя `mediaActive=true`.
  **Что опровергнет посылку:** direct initial snapshot теряется из-за counter
  или null и empty имеют один результат — reducer не соответствует контракту.
- ✅ 23.10.2026 `[auto]` `AlbumArtEncoder.encode(bitmap: Bitmap?): ByteArray?` через seam:
  thumbnail/scaled75×75 с filter=true, JPEG quality50;
  null/failed-compress/empty/exception/>65535 → null, metadata сохраняется.
  Сохранить текущий fallback порта `ALBUM_ART → ART → DISPLAY_ICON`;
  оригинал читает только `ALBUM_ART`, это не основание сужать поддержку.
  Не recycle-ить заимствованный metadata Bitmap; собственные временные bitmap
  освобождать с учётом alias входа/thumbnail/scaled, только после компрессии.
  Framework failure до send catch, safe encoder result и узкий legacy
  IllegalStateException fallback не объединять в одну ветку.
  **Что опровергнет посылку:** если Android framework не позволяет стабильно
  тестировать bitmap conversion на JVM, оставить framework call behind seam и
  тестировать reducer/result contract.
- ✅ 23.10.2026 `[auto]` Provider `playPause(): Unit`, `volumeUp(): Unit`,
  `volumeDown(): Unit`, `mute(): Unit`: null controller/playback → playPause no-op;
  PLAYING → pause, любой другой nonnull → play; volume controller.adjustVolume
  ±1 с FLAG_SHOW_UI=1; mute через AudioManager STREAM_MUSIC, isStreamMute →
  ADJUST_UNMUTE(100), иначе ADJUST_MUTE(-100), flags=0, без controller gate.
  Ошибки adapters → тихий no-op (защита порта, не гарантия оригинала);
  сохранить эту защиту и для existing skipNext/skipPrevious. Не добавлять media FGS.
  **Что опровергнет посылку:** mute без controller не работает или exception
  выходит наружу — adapter нарушает контракт.

**Готово:** provider/reducer tests покрывают no access, bind/rebind, пустой
список после bound-controller, late callback после switch/stop; initial null
и второй callback null; обход counter начальным snapshot; all-null + ad≠0,
empty/whitespace title, null/empty id, case-only id/title и late art/artist/album/ad
при прежней паре id/title. Отдельно проверены границы fallback/ошибок encoder,
PLAYING/PAUSED/OTHER, next/previous вне PLAYING и mute без controller.
Переходы PLAYING→OTHER и PAUSED→null playback проверяются отдельно: active=true,
playing=null, новый playback event без `05 19`; null metadata остаётся
отдельным сценарием counter/очистки (§2.8).
Android live `MediaSession` не нужен для JVM tests; bitmap alias/ownership
проверяется Android smoke, а не mock-echo.
Проверено 23.10.2026: `MediaInfoProviderTest`, `MediaEventStreamTest`,
`MediaMetadataDispatchTest`, Android `AlbumArtEncoderTest` и временный live
MediaSession smoke; полные результаты — §0.1. Native snapshots пока обновляют
только прежнюю periodic card и nowPlayingTitle, не wire indicators/Pigeon media-флаги.

### Этап 3 — Single-writer session и media forwarding [auto]

**Цель:** провести events через существующий session writer, сохранить reconnect
semantics и 1 Hz fallback.

Задачи:

- ✅ 23.10.2026 `[auto]` В `DashChrome.NowPlaying` добавить `mediaId`, `artJpeg`;
  в chrome `mediaActive` и единственный nullable `mediaPlaying`, без дублирования
  playing в NowPlaying; сохранить `NowPlaying("Song")` совместимым.
  **Что опровергнет посылку:** две копии playback расходятся — модель неверна.
- ✅ 23.10.2026 `[auto]` `DashSession.sendMedia(commands: List<DashCommand>): Unit`
  принимает wire events только в STREAMING; controller всегда сначала обновляет
  current chrome даже до STREAMING. startStreaming атомарно получает revision
  snapshot и replay active→card→marker→art→known playback один раз; последующие
  revisions передаются ровно один раз, concurrent update не теряется/не дублируется.
  Replay посылает `05 19` только при ненулевом текущем `mediaPlaying`;
  после unknown callback старое known playback не восстанавливается.
  **Что опровергнет посылку:** before/during/after transition interleaving теряет
  update или повторяет initial replay — ordering seam недостаточен.
- ✅ 23.10.2026 `[auto]` Writer раскрывает AlbumArt в scalar chunks и incrementally
  назначает earliest-next-send +10 ms (§2.11). Между chunks выбирает pending
  projection/button ACK/heartbeat, один coroutine/socket writer, без Thread.sleep
  и без блокировки всей очереди transfer-ом. Cancel pending art при принятом
  после dedup metadata, inactive, stop и session teardown; следующая сессия
  не получает хвост старого art. При замене A→B закончить уже начатый packet A,
  отменить оставшиеся chunks A (включая отложенный) до card B; затем для B
  card→present marker→art с первого кадра либо card→absent marker без art.
  После card B chunks A запрещены; повторная смена B→C действует так же.
  Dedup-suppressed callback не отменяет art (§2.11). Использовать существующий
  writer без отдельной очереди/потока и вне frame/encoder loop.
  **Что опровергнет посылку:** timestamp tests max65535 показывают projection gap
  >250 ms или ACK ожидает весь transfer — scheduling не подходит.
  Scalar codec не принимает логический AlbumArt (precondition failure).
  Тестировать реальную последовательность packets и её отмену/порядок, а не
  копии аргументов или forwarding между helpers.
- ✅ 23.10.2026 `[auto]` В `DashEngineController.startMediaForwarding()` сначала поднять
  collectors, затем вызвать `mediaInfo.start()`; обновлять chrome, inputs,
  publishState и queue event sequences по правилам плана.
  **Что опровергнет посылку:** если initial snapshot теряется при старте
  collector, порядок запуска доказанно неверен.
- ✅ 23.10.2026 `[auto]` В `stopMediaForwarding()` владельцем остановки и отправки
  inactive является controller:
  1. Прекратить принимать callbacks остановленного media-поколения и очистить
     current snapshot/chrome: metadata отсутствует, `mediaActive=false`,
     `mediaPlaying=null`; очистить соответствующие `DashInputs` и публикуемый
     Dart state. Поздний callback не восстанавливает состояние.
  2. Для ещё живой STREAMING-сессии явно заказать `05 17 AA` через существующий
     single writer. Доставка не зависит от обработки provider Inactive
     отменённым collector.
  3. Writer при обработке перехода отменяет pending art и старые periodic/event
     cards: после inactive нет старых карточек и chunks. Уже начатый packet
     может закончиться до inactive; очищенное текущее состояние не позволяет
     последующим ticks снова породить старую карточку.
  4. Отменить collector jobs, затем вызвать provider stop. Повторный stop не
     порождает второй переход inactive для уже очищенного media-состояния.
  5. После завершённого transport teardown финальная доставка inactive не
     гарантируется; локальное состояние и pending art всё равно очищаются.
     Старые packets/state не переносятся в следующую сессию. Не добавлять
     reconnect или retry ради финального inactive.
  Обычный reconnect активной поездки сохраняет snapshot по §2.7; media/active-ride
  stop очищает его. Отключение сокета само по себе не означает media stop.
  Периодическую `05 0D` до hardware gate сохранить для действующего media.
  **Что опровергнет посылку:** inactive зависит от уже отменённого collector,
  late callback восстанавливает состояние, повторный stop даёт второй inactive
  или следующая сессия получает старую карточку/playback — cleanup неполон.
- ✅ 23.10.2026 `[auto]` Обязательный `heartbeat: () -> DashCommand.Heartbeat` в
  `DashSession.open`: мигрировать все production/test factories в этом этапе.
  Supplier пока возвращает captured `Heartbeat(25,0x13,0x19)`; этап 4 заменяет
  его live provider. Initial burst остаётся captured.
  **Что опровергнет посылку:** tick создаёт heartbeat сам или factory не
  передаёт supplier — seam не внедрён сквозным образом.

**Готово:** tests покрывают before/during/after STREAMING races, genuine later
updates, reconnect сохранение и active-ride teardown очистку, старую 1 Hz card,
timestamp projection interval ≤250 ms и button receipt→ACK ≤250 ms при 65535 art
на virtual clock/неблокирующем fake transport; art intervals ≥10 ms.
Отдельные packet-level сценарии на управляемом времени: первый chunk A →
принято B с JPEG и первый chunk A → принято B без JPEG. Проверять содержимое
и порядок: начатый packet A завершается до card B; после card B только
present marker и новая передача B с первого кадра либо absent marker без art,
ни одного оставшегося/отложенного chunk A. Подавленный dedup callback не
отменяет transfer A; повторная замена B→C соблюдает тот же порядок.
Для PLAYING→OTHER→reconnect и PAUSED→null playback→reconnect проверить
`mediaActive=true`, `mediaPlaying=null`: unknown callback даёт active без
`05 19`, replay не повторяет прежний playing/paused indicator.
Stop при ещё живой STREAMING-сессии: metadata очищены, active=false/playing=null
в snapshot/chrome, inputs и публикуемом state; controller явно заказывает
inactive независимо от отменённого collector. Начатый packet завершается до
inactive, после него нет старых periodic/event cards и chunks, включая
отложенные; последующие ticks не восстанавливают карточку. Повторный stop
не даёт второго inactive, late callback не восстанавливает state.
Stop после transport teardown: доставки в закрытый transport не требуется,
pending art/state очищены, новая сессия не получает старых packets/state.
Отдельный обычный reconnect без media stop сохраняет snapshot активной поездки.
Проверено 23.10.2026: packet-level `DashSessionMediaTest` и `MediaForwardingTest`,
полный JVM suite, `lintDebug`, executable UDP smoke и два code review;
результаты и исправление по ревью — §0.1. Старые literal wire goldens не менялись.

### Этап 4 — Live volume heartbeat [auto]

**Цель:** читать системные уровни громкости только в controller seam, а codec
оставить Android-independent.

Задачи:

- ✅ 23.10.2026 `[auto]` Controller `currentHeartbeat(): DashCommand.Heartbeat` с
  AudioManager: STREAM_MUSIC→05 4C, STREAM_VOICE_CALL→05 1B, temp25;
  nullable VolumeLevel/exception каждого stream независимо fallback13/19.
  Подключить live supplier вместо captured этапа3.
  Не переносить original omission volume TLV или условный header count;
  фиксированный состав heartbeat проверять также при ошибке одного stream.
  **Что опровергнет посылку:** если `AudioManager` отсутствует или getter
  бросает exception, tick loop не должен падать и должен отправить fallback.
- ✅ 23.10.2026 `[auto]` Live supplier работает на каждом streaming heartbeat; не вводить
  firmware INFOTAINMENT gate (снятая посылка §0.1).
  **Что опровергнет посылку:** доказанное ограничение протокола по реальному типу
  подключения требует отдельного решения, не guessed incoming mode.

**Готово:** focused heartbeat/session tests видят независимые live levels,
статические TLV не изменились, invalid AudioManager path не роняет tick loop.
Проверено 23.10.2026: `DashSessionMediaTest`, старые heartbeat/burst goldens,
полный JVM suite, Android AudioManager smoke и `lintDebug`; результаты — §0.1.

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
  OTHER/null playback callback передаёт через Pigeon/Dart именно
  `mediaPlaying=null` при `mediaActive=true`, без подстановки false или
  последнего известного значения (§2.10).
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
- [ ] `[auto]` Обновить `spec/multimedia.md`: источники и уточнения оригинала
  в §1–7 по сверке §1.2 этого плана; §8/§9 — только фактами текущей реализации.
  Не выдавать 10.1.23-статику за проверку порта; hardware-dependent claims
  явно оставить открытыми.
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
  В последнем опыте фиксировать входящие `09 04/06`, продолжение media packets
  и фактическую картинку, не объявляя эти команды guessed media-mode boundary.
  Для art отдельно сменить A→B после начала, но до завершения multichunk A:
  B с art и B без art. После card B в pcap нет chunks A; приборка показывает
  B без зависания/возврата A. Без железа опыт остаётся открытым.
  **Что опровергнет посылку:** marker/art игнорируется или ломает карту,
  либо приборка не переносит прерывание art — отклонить соответствующий path
  по screen+pcap, не менять соседние branches и не подменять выбранную отмену
  A политикой завершения старой обложки.
- [ ] `[ride]` Независимые music/call volume min/max/silence, включая mute без
  controller; max65535 art совместно с projection и button ACK: сравнить с baseline
  без art, timestamp cadence 250 ms, receipt→ACK ≤250 ms плюс baseline jitter,
  heartbeat gap ≤1 s плюс тот же измеренный jitter, art spacing≥10 ms.
  **Что опровергнет посылку:** новая шкала/transfer даёт неправильный volume
  или gaps сверх baseline — отклонить конкретную ветвь, сохранить captured fallback.
- [ ] `[ride]` Official buttons проверять ТОЛЬКО явно gated fixture mapping:
  `03/04` call-volume, `05` play/pause, `06/07` music volume, `08` mute,
  `09/0A` next/previous; active/no-media/ringing call и call precedence.
  Проверять отдельно original code→phone action и портовые ACK-before-forward/
  mediaActive-gate для `05/06/07/09/0A`: это не gate оригинального listener.
  Пустой title не выключает mediaActive; mute/call-volume доступны без него.
  Phone action и ACK проверить вместе с pcap; старый mapping APK не доказывает
  новый mapping. Код `18` не считать play и не добавлять как alias.
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
