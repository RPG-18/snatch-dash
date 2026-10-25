# Настройки голосовых подсказок

## 0. Как читать этот план

Полный план: затронуты Flutter UI, сохранение настроек и асинхронное владение Android TTS/audio focus. Этапы выполняются по порядку; зависимости указаны в §3. `[auto]` означает автоматическую проверку, `[ride]` — проверку человеком на телефоне/дэше; стендовые сценарии отдельно отмечены и не требуют поездки.

Критерии завершения — наблюдаемые результаты из §5, не факт написания кода. Изменения порядка фиксировать в §0.1 с причиной. Старые отклонённые/откаченные задачи не удалять. Инварианты §2 сохраняются на всех этапах.

Организационное отличие от исторического шаблона: автоматические коммиты и остановки между техническими этапами не требуются. Если реализация запрошена целиком, выполнить все доступные шаги; если отдельно — только названный этап. `[ride]` остаётся незавершённым до подтверждения человека.

## 0.1 Статус

`✅` — сделано и проверено; `❌` — отклонено по существу; `↩️` — было сделано и откачено по полевому результату (причина обязательна); без отметки — не начато. Отметку ставит исполнитель с датой и доказательством.

**Где план сейчас (25.10.2026).** Этапы 1–3 выполнены. Пользователь подтвердил ручную проверку экрана; UI дополнительно проверен автоматически на HBN-LX9 с установленной profile-сборкой. Открыт этап 4: реальный звук, взаимодействие с плеером и фоновые сценарии.

✅ **Этап 1 [auto], 25.10.2026:** Pigeon API, plugin-owned TTS/focus и teardown реализованы. `VoiceOutputTest`: 23 теста, 0 ошибок; `lintDebug` и `flutter analyze` прошли. Отдельный JVM smoke подтвердил stop во время init, замещение, изоляцию позднего callback и dispose без удержанного focus. Реальные TTS и аудиополитики на телефоне этим шагом не проверены.

**Исправление по ревью и проверка cleanup на Android [auto], 25.10.2026.** `AndroidVoiceFocusAdapter` теперь освобождает request и при отказе focus: Android регистрирует listener до обращения к аудиосервису. После исправления native unit suite и `lintDebug` прошли; JVM-smoke на production bytecode с моделью AOSP показал 100 удержанных регистраций до исправления и 0 после.

На HBN-LX9, Android 12/API 31, serial `2FK0224A22003381`, исправленный adapter отдельно запущен через `app_process` с настоящими ART и `AudioManager`. Ответы `IAudioService` задавались внутри изолированного процесса, без обращения к настоящему аудиосервису; проверялась реальная `mAudioFocusIdListenerMap`. Контрольный отказ без cleanup оставил 1 регистрацию, явный abandon вернул 0. Затем 100 отказов adapter (поровну duck/pauseAndResume) не оставили ни одного voice listener; посторонняя контрольная регистрация сохранилась. Выдача focus после отказов, повторный release, исключения request/abandon и unchanged прошли; в конце таблица пуста.

Проверочный DEX ZIP: SHA-256 `ab75ce6a291258a9a5d5479becd307278b106865d878bc3110801aaec12e4a98`, хеш на телефоне совпал. Запуск: `adb -s 2FK0224A22003381 shell env CLASSPATH=/data/local/tmp/snatch-focus-device-20261025.zip app_process /system/bin FocusDeviceSmoke`, exit 0. Временные файлы на телефоне и хосте удалены. Приложение не переустанавливалось: ранее установленный profile APK этим исправлением не обновлён. Системные настройки не менялись; reflection-доступ к framework действовал только внутри завершившегося shell-процесса. Это подтверждает cleanup регистраций на Android, **не** реальную выдачу/отказ focus системой, TTS, реакцию плеера или screen-off; этап 4 остаётся открытым.

✅ **Этап 2 [auto], 25.10.2026:** единый settings owner загружается до контроллеров, legacy мигрируется, записи сериализованы, весь голос переведён на native API; `flutter_tts` и `playChime` удалены. Целевые Dart-тесты: 38; полный suite приложения: 171; suite плагина: 2 — прошли. Native suite, `lintDebug`, `flutter analyze` прошли. Отдельное запущенное Flutter web-приложение с настоящим VoiceManager и SharedPreferences восстановило off/low/unchanged в новом manager, оба connection announcement остались молчать; browser storage подтвердил три сохранённых значения. Это проверка persistence, не Android TTS.

✅ **Этап 3 [auto] + [ride, стенд], 25.10.2026:** пользователь сообщил «Проверил вручную, экран работает» и поручил закрыть этап после автоматической проверки на телефоне. Карточка после валюты, root modal sheet, RU/EN и generated локализации реализованы; старые voice-mode ключи удалены после LSP references. `flutter analyze` прошёл. Flutter web smoke ранее проверил RU/EN при 390×844 и 320×640 с text scale 2, прокрутку, dropdown, controls при off, закрытие и восстановление после reload.

**Автоматическая проверка Android UI, 25.10.2026.** HBN-LX9, Android 12/API 31, serial `2FK0224A22003381`, установленная profile-сборка с SHA-256 из записи ниже. ADB input + UI Automator проверили вход через настоящую вкладку «Ещё», карточку между валютой и доступом к уведомлениям, switch, все три громкости и все три политики, редактирование громкости/политики при off. Back, тап вне sheet и свайп вниз закрыли sheet; повторное открытие сохранило выбор. После `am force-stop` и запуска приложения через launcher UI восстановил off/low/unchanged; значения также прочитаны из `FlutterSharedPreferences.xml`. При системном `font_scale=2.0` controls и dropdown остались доступны, длинная RU-подпись перенеслась без обрезания; screenshot проверен визуально. EN на телефоне не переключали — его покрывают предыдущий web smoke и ручная приёмка экрана, без отдельного заявления о ручной проверке EN. Исходные true/medium/duck восстановлены через UI и сверены с preferences; `font_scale=1.0` и `screen_off_timeout=30000` восстановлены и прочитаны обратно. Временный UI Automator XML удалён. Эта проверка подтверждает UI/persistence, не слышимую громкость и audio focus.

**Этап 4 — подготовка [auto], 25.10.2026.** Debug APK собран с локальным Yandex define-файлом: `build/app/outputs/flutter-apk/app-debug.apk`, SHA-256 `3a2bac2fd04b97053871570f804578c1d6b7659f7bc8295e68aeda43ffa483de`. Команда: `JAVA_HOME=/tmp/snatch-voice-jbr21/Contents/Home GRADLE_OPTS=-Dorg.gradle.java.home=/tmp/snatch-voice-jbr21/Contents/Home flutter build apk --debug --dart-define-from-file=android/dart_defines.local.properties`. Сборка прошла; Flutter предупредил о будущем несовместимом KGP в `yandex_maps_mapkit`, зависимость в этой задаче не обновлялась.

**Установка для проверки, 25.10.2026 16:40.** По запросу пользователя собран `--profile` с тем же локальным define-файлом и JBR 21. APK `build/app/outputs/flutter-apk/app-profile.apk` (263855743 байт) установлен с `-r` на HBN_LX9, serial `2FK0224A22003381`, пакет `ru.snatchdash.app`. SHA-256 `a31f5be7aca4a857ea3390835bd96904904c42c5b875d0557cd42bda364a4c9d` совпал с хешем установленного `base.apk`; `lastUpdateTime=2026-10-25 16:40:45`, прежние firstInstallTime/userId сохранены. `DEBUGGABLE` остаётся включённым; это не release-замер. Установка сама по себе не закрывает сценарии `[ride]`.

**Открытый hardware gate — этап 4.** Нужны RU/EN TTS-данные, конкретный музыкальный плеер и дэш для маршрута. Не проверены реальные 25/50/75%, duck/pause/resume/unchanged, музыка уже на паузе, ручная пауза/смена плеера/звонок во время фразы, остановка/перезапуск во время речи, screen-off 300/150/60 м/прибытие и Android 15+ foreground/focus denial. UI и сохранение настроек после перезапуска проверены отдельно выше; отсутствие звука при off на живом маршруте этим не доказано. Воспроизводимые действия и критерии остаются в §5. В debug `app_log.txt` ошибки искать по `[Voice] could not read stored settings`, `could not store settings`, `could not stop speech`, `could not speak announcement` и кодам `VOICE_*`; отсутствие ошибок не доказывает слышимое восстановление музыки. Временные JVM/web smoke-файлы удалены, продуктовой preview-кнопки нет.

**Окружение проверки:** установленный Android Studio JBR оказался 25.0.3. Для Gradle использован изолированный [официальный JBRSDK 21.0.11-b1163.116](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-21.0.11b1163.116) в `/tmp/snatch-voice-jbr21`, SHA-512 сверена с опубликованной JetBrains; глобальные Java/Flutter настройки не менялись.

## 1. Контекст за пять минут

Реализовать [экран голосовых настроек](../../spec/voice_settings_screen.md): включение звука, громкость 25/50/75%, поведение музыки «приглушать / пауза с восстановлением / ничего не менять». Вход — карточка после валюты на вкладке «Ещё»; экран открывается снизу и закрывается свайпом вниз ([Settings Screen](../../spec/settings_screen.md), п. 6).

Пользователь выбрал defaults: **включено, 50%, приглушать музыку**. Настройки относятся ко всему голосу приложения: манёврам, прибытию, потере и восстановлению связи. Выбор сохраняется между запусками; выключение немедленно прерывает речь.

На дату исследования `lib/main.dart:34–36` каждый запуск сохраняет `VoiceMode.full`; `lib/nav/voice_manager.dart` пишет `voice_mode`, но не загружает его. `FlutterTts.speak()` вызывается без управления громкостью/фокусом. Локализованные фразы и `ManeuverCueScheduler` сохраняются; исходный контракт — [голосовое ведение](../../spec/voice_guidance.md).

## 2. Инварианты, которые нельзя нарушить

|Инвариант|Механическая проверка|
|---|---|
|Пороги 300/150/60 м, приоритет прибытия и пропуск устаревших cues сохраняются|Существующий `test/nav/maneuver_cue_scheduler_test.dart`|
|Русский/английский язык, левые/правые манёвры и номера съездов не меняются при замене playback|Существующий `test/nav/voice_manager_test.dart`; меняется test seam, не ожидаемые фразы|
|Операция речи получает один terminal result, старый callback не освобождает focus новой операции|Новый `VoiceOutputTest.kt`, вводится в начале этапа 1 вместе с тестируемым владельцем|
|Stop при initialization/речи не оставляет отложенное воспроизведение или удержанный focus|Новый `VoiceOutputTest.kt`, вводится в этапе 1 до подключения владельца к реальным вызовам Dart|

Доказательством громкости/паузы реального плеера эти тесты не являются: для этого нужен этап 4.

## 3. Этапы

Зависимости: 1 → 2 → 3 → 4. Нативные terminal-path тесты входят в этап 1, persistence/manager regression — в этап 2; не оставлять их на конец реализации.

### Этап 1 — Нативный владелец речи и аудиофокуса [auto]

Перенести только воспроизведение из `flutter_tts` в локальный Android-плагин. В установленном `flutter_tts 4.2.5` есть только duck-запрос, результат выдачи фокуса не проверяется, terminal callbacks не передают utterance ID в Dart, modern error callback не освобождает focus. Внешний focus-helper поверх него сохраняет гонки завершения/замещения. Эквивалентного владельца TTS в движке нет.

В `packages/opendash_dash_engine/pigeons/dash_engine.dart` добавить:

```dart
enum VoiceMusicPolicy { duck, pauseAndResume, unchanged }
enum VoiceSpeechResult { completed, cancelled, focusDenied }

// В DashEngineApi:
@async
VoiceSpeechResult speakVoice(
  String text,
  String languageTag,
  double volume,
  VoiceMusicPolicy policy,
);
@async
void stopVoice();
```

Оба метода async, чтобы generated dispatch обрабатывал speak/stop одинаково. `speakVoice` отвечает после окончания/отмены, не после enqueue. `stopVoice` отвечает после очистки владельца, не ожидает callback Android.

Добавить в `packages/opendash_dash_engine/lib/{opendash_dash_engine.dart,opendash_dash_engine_platform_interface.dart,opendash_dash_engine_method_channel.dart}` методы:

```dart
Future<VoiceSpeechResult> speakVoice({
  required String text,
  required String languageTag,
  required double volume,
  required VoiceMusicPolicy policy,
});
Future<void> stopVoice();
```

Оба enum реэкспортировать в существующем `show` фасада. Генерировать из каталога плагина: `dart run pigeon --input pigeons/dash_engine.dart`; generated Dart/Kotlin руками не менять.

Создать **новые** файлы в `packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/voice/`:

- `VoiceOutput.kt`: `internal class VoiceOutput`, единственная активная операция; `fun speak(text: String, languageTag: String, volume: Double, policy: VoiceMusicPolicy): Deferred<VoiceSpeechResult>`, `fun stop()`, `fun dispose()`.
- `VoiceAdapters.kt`: узкие TTS/focus adapters для тестирования без Android runtime. Копировать подход интерфейсов из `media/MediaAdapters.kt`, не создавать общий media framework.

Контракт TTS adapter: `initialize(onResult: (Boolean) -> Unit)`, `setLanguage(languageTag: String): Boolean`, `speak(text: String, volume: Float, utteranceId: String): Boolean`, `stop()`, `shutdown()`; listener получает `done(id)`, `stopped(id)`, `error(id, errorCode: Int?)`. Focus adapter: `request(policy, onLost): VoiceFocusLease?`; null — отказ, lease предоставляет `release()` и хранит конкретный Android request.

В `OpendashDashEnginePlugin.kt` создавать владельца на `onAttachedToEngine` с application context. Сам TTS инициализировать лениво при первой речи без focus на время initialization. State transitions и Android callbacks выполняются на main thread; callbacks с других потоков перенаправлять на main. `VoiceOutput.speak()` регистрирует новую операцию до любого ожидания. Host suspend методы исполнять через plugin-owned `scope.async(start = CoroutineStart.UNDISPATCHED) { ... }.await()`, по причине ownership, уже описанной у `disconnect()`.

На engine detach вызвать `voiceOutput.dispose()` **до** controller teardown и `job.cancel()`: остановить речь, освободить focus, завершить pending result, вызвать `shutdown()`. Activity detach/recreation, закрытие sheet и разрыв dash-соединения владельца не уничтожают. После detach — существующий error code `ENGINE_GONE`. В existing `<queries>` манифеста плагина добавить intent `android.intent.action.TTS_SERVICE`.

|Политика|Перед речью|После речи|
|---|---|---|
|`duck`|`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`|Освободить тот же request; Android снимает duck|
|`pauseAndResume`|`AUDIOFOCUS_GAIN_TRANSIENT`|Освободить request; плеер восстанавливает playback по своему focus handler|
|`unchanged`|Не запрашивать focus|Ничего восстанавливать не нужно|

TTS и focus используют одинаковые `AudioAttributes`: `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, `CONTENT_TYPE_SPEECH`. Для request: `setAcceptsDelayedFocusGain(false)`, main-thread listener, `setWillPauseWhenDucked(true)` для реакции самой речи на чужой focus. Начинать речь только при `AUDIOFOCUS_REQUEST_GRANTED`; отказ → `focusDenied`, без fallback и отложенного воспроизведения.

Volume — `TextToSpeech.Engine.KEY_PARAM_VOLUME` с `0.25f/0.50f/0.75f`; `TextToSpeech.speak` — `QUEUE_FLUSH` и уникальный монотонный utterance ID. Не использовать `setStreamVolume`, `mediaMute`, `mediaPlayPause` или прямой play/pause чужого плеера.

**Конечный автомат:**

1. Одна активная операция, latest-wins. Новая речь отменяет предыдущую, в том числе пока TTS инициализируется. Очереди подсказок нет.
2. Замещение сначала инвалидирует A, останавливает её и освобождает её focus; затем запускает B с новым request. Не переносить lease между операциями: это исключает владение новым focus из старого callback.
3. `onDone` → `completed`; `onStop`, explicit stop, replacement, disposal и любая потеря focus → `cancelled`. На gain старую фразу не возобновлять. TTS callback проверяет utterance ID, focus callback — идентичность lease. Поздние события A не затрагивают B.
4. Один idempotent terminal path обнуляет active **до** stop/release, завершает result ровно один раз. Cleanup failure не пропускает остальные действия и не оставляет pending Future. Explicit stop/dispose не ждут callback.
5. Init failure, отрицательный `setLanguage`, immediate `TextToSpeech.ERROR`, оба overload `onError` и Android exceptions заканчивают операцию с освобождением ресурсов. Коды `DashPigeonError`: `VOICE_TTS_INIT`, `VOICE_TTS_LANGUAGE`, `VOICE_TTS_PLAYBACK`, `VOICE_AUDIO_FOCUS`. Denied/cancelled — обычные enum outcomes. Init failure сохраняется до пересоздания plugin owner; retries не добавлять.
6. Stop при initialization сразу отменяет pending operation; поздний init не запускает A. После обычного stop готовый TTS может обслужить новую B; после dispose callback ничего не запускает и не создаёт ресурсы.
7. Пустой/пробельный text → `completed` без focus/TTS. Нефинитный volume/значение вне `[0,1]` → `VOICE_ARGUMENT` до Android вызовов. Отсутствующий язык/голосовые данные — ошибка языка, не речь на другом языке или beep.

Готово: новый Pigeon путь и owner реализованы с terminal-path тестами; старый Dart playback пока сохранён до шага 2. Посылку опроверг бы уже существующий владелец с тремя политиками и идентифицированными callbacks; проверенные flutter_tts/media toggles этим требованиям не отвечают.

### Этап 2 — Настройки, startup и переключение всей речи [auto]

Создать **новую** типизированную модель `lib/nav/voice_settings.dart`:

```dart
enum VoiceVolume { low, medium, high }

class VoiceSettings {
  const VoiceSettings({
    this.enabled = true,
    this.volume = VoiceVolume.medium,
    this.musicPolicy = VoiceMusicPolicy.duck,
  });
  final bool enabled;
  final VoiceVolume volume;
  final VoiceMusicPolicy musicPolicy;
  VoiceSettings copyWith({
    bool? enabled,
    VoiceVolume? volume,
    VoiceMusicPolicy? musicPolicy,
  });
}
```

Добавить `VoiceVolume.gain` (`double`): 0.25/0.50/0.75. `VoiceMusicPolicy` импортировать из engine facade, не дублировать enum.

В `VoiceManager` заменить mode на `VoiceSettings get settings`; добавить `Future<void> initialize()` и `Future<void> applySettings(VoiceSettings settings)`. Менеджер — единственный владелец preferences и runtime snapshot. Сохранить `VoiceManager.instance`; обычный конструктор `VoiceManager()` разрешить для независимых экземпляров тестов.

- В `main()` заменить `setMode(full)` на `await VoiceManager.instance.initialize()` на том же месте, до контроллеров. Пока initialization не завершена, announcements ничего не произносят.
- Ключи: `voice_enabled` (bool), `voice_volume` (`low|medium|high`), `voice_music_policy` (`duck|pauseAndResume|unchanged`). Чтение через `prefs.get` с проверкой типа. Missing/unknown/wrong-type поле получает свой default; корректные соседние поля сохраняются.
- Legacy `voice_mode`: если валидного нового enabled нет, `off → false`, `full/chime → true`, неизвестное → default true. Новые валидные значения приоритетнее. Chime исчезает, потому что UI имеет on/off, а нынешний startup уже принудительно меняет chime на full. При наличии legacy key сохранить получившиеся три поля и удалить `voice_mode` только после успешных записей. На ошибке оставить legacy key для следующего запуска. При обычном startup не записывать defaults поверх сохранённого.
- `applySettings` обновляет snapshot синхронно до первого await; при off сразу отправляет `stopVoice`, не ожидая диска. Volume/policy действуют со следующей фразы, текущую не перезапускают.
- Записи snapshot-ов сериализовать одной Future-цепочкой менеджера: быстрые изменения не сохраняют старое последним; ошибка записи не отравляет следующие. Как в `map_theme_settings.dart`, читать с defaults при ошибке, при ошибке сохранения оставить runtime выбор, логировать через `talker.error`. `false` от setter также считать ошибкой. UI не блокировать.
- `_speak` передаёт snapshot gain/policy, язык `ru-RU` для ru, `en-US` для остальных в `DashEngine.speakVoice`. Между enabled check и отправкой не делать await, после которого уже выключенный голос всё-таки отправится. Native error перехватывать и логировать, не выпускать из fire-and-forget навигации. `cancelled` штатный; `focusDenied` пропускается без fallback. Уже отмеченный cue после ошибки не повторять.
- `maybeAnnounce`, `announceConnectionLost`, `announceConnectionRestored` используют один enabled gate/одну `_speak`. Off по-прежнему возвращает до scheduler.next. Настройки scheduler не сбрасывают. Существующие reset при старте/перестроении и независимость nav tick от речи сохраняются.

Создать **новый** `lib/state/voice_settings.dart`: `VoiceSettingsController extends Notifier<VoiceSettings>`, `VoiceSettings build()`, `Future<void> setEnabled(bool enabled)`, `Future<void> setVolume(VoiceVolume volume)`, `Future<void> setMusicPolicy(VoiceMusicPolicy policy)` и `voiceSettingsProvider = NotifierProvider<VoiceSettingsController, VoiceSettings>(VoiceSettingsController.new)`.

`build()` синхронно читает уже загруженный `VoiceManager.instance.settings`. Setters сначала меняют state, затем делегируют `applySettings`. Второй storage owner/lazy hydration не создавать: здесь отложенная загрузка могла бы опоздать после первого announcement.

**Чистое переключение:** удалить `VoiceMode`, `setMode`, `mode`, `voiceModeProvider`, chime branches и import flutter_tts. В `test/nav/voice_manager_test.dart` заменить `flutter_tts` channel mock на fake `OpendashDashEnginePlatform`, сохранив RU/EN/left-right/roundabout ожидания и восстановление platform instance в teardown.

Удалить `flutter_tts` из `pubspec.yaml`, пересчитать lock через `flutter pub get`. Удалить неиспользуемый `playChime` из Pigeon, трёх Dart-слоёв, plugin override и `DashEngineController.kt`; там же удалить его `toneGenerator`, release и ставшие неиспользуемыми imports. Повторно сгенерировать Pigeon. Обнаруженные места возвращает `playChime|toneGenerator|ToneGenerator` в `lib;test;packages/opendash_dash_engine`; перед удалением повторить LSP references публичных Dart symbols. Сейчас у `DashEngine.playChime` только три вызова из VoiceManager.

Готово: settings загружены до первого announcement; off останавливает текущую/инициализируемую речь; все источники используют новый путь; старого playback нет. Если пользователь уже внёс часть изменений, адаптировать её к этому контракту, не возвращать старую реализацию.

**Что опровергнет посылку:** при повторном чтении кода уже существует единая загрузка всех трёх настроек до первого announcement и все источники речи ей подчиняются. В таком случае не создавать второго владельца, а проверить существующий по §5 и отметить уже выполненную часть с доказательствами.

### Этап 3 — Модальный экран звука [auto] + [ride]

✅ **Закрыт 25.10.2026:** ручная приёмка пользователем и автоматическая проверка Android UI/persistence; доказательства в §0.1. Слышимое действие настроек на речь/музыку остаётся предметом открытого этапа 4, а не результатом UI-автоматизации.

Создать **новый** `lib/screens/voice_settings_screen.dart`: `Future<void> showVoiceSettingsSheet(BuildContext context)` и `class VoiceSettingsScreen extends ConsumerWidget`.

Открывать через `showModalBottomSheet<void>` с `useRootNavigator: true`, `showDragHandle: true`, `enableDrag: true`, `isDismissible: true`, `isScrollControlled: true`, `useSafeArea: true`. Содержимое — SafeArea и scrollable ListView(shrinkWrap: true), отступы 16; копировать `_pickCandidate` из `route_screen.dart`. Без Scaffold, нового route и кнопки Save.

Порядок: заголовок → SwitchListTile → label громкости и три горизонтальных `SegmentedButton<VoiceVolume>` выбора → label поведения музыки и dropdown. SegmentedButton содержит три сегмента, допускает только одно непустое значение. Dropdown: `isExpanded: true`, `itemHeight: null`, длинные подписи переносятся. Sheet не имеет фиксированной высоты.

Controls редактируемы и при off; setter вызывается сразу, закрытие не отменяет изменения/не уничтожает voice owner. Back, tap снаружи и drag down закрывают modal. Проценты 25%/50%/75% добавить в tooltip/semantics сегментов.

В `SettingsScreen` вставить `Card(ListTile(...))` после currency card и до notification access, интервалы 16. Title `settingsVoiceTitle`, leading `Icons.record_voice_over_outlined`, trailing chevron, tap открывает sheet. Убрать устаревший doc comment про настройку на Route tab.

Новые ARB keys (English template); generated локализации получить `flutter gen-l10n`:

|Ключ|RU|EN|
|---|---|---|
|`settingsVoiceTitle`|Голосовые подсказки|Voice guidance|
|`voiceSettingsEnabled`|Звуковое сопровождение|Voice announcements|
|`voiceSettingsVolume`|Громкость|Volume|
|`voiceSettingsVolumeLow`|Низкая|Low|
|`voiceSettingsVolumeMedium`|Средняя|Medium|
|`voiceSettingsVolumeHigh`|Высокая|High|
|`voiceSettingsDuringAnnouncements`|При оповещениях|During announcements|
|`voiceSettingsMusicDuck`|Приглушать музыку и другие звуки|Lower music and other sounds|
|`voiceSettingsMusicPause`|Ставить музыку на паузу|Pause music|
|`voiceSettingsMusicUnchanged`|Ничего не менять|Leave other audio unchanged|

Проценты — literals `25%/50%/75%`. Labels выбирать локализованным switch, не хранить в enum. После LSP reference check удалить неиспользуемые `routeVoiceGuidanceTooltip` (включая metadata), `voiceModeOff/Chime/Full` из ARB; обнаружены только ARB/generated references.

Готово: вход в заданном месте, controls действительно влияют на голос/музыку и переживают перезапуск, swipe down работает, RU/EN не переполняются на узком экране. Уже внесённый пользователем аналогичный sheet адаптировать вместо второго.

**Что опровергнет посылку:** требуемая карточка и sheet уже реализованы со всеми тремя действующими настройками. Тогда не создавать дубликат; проверить существующий UI по §5 и записать результат. Стендовая проверка на телефоне относится к `[ride]`, поездка для неё не нужна.

### Этап 4 — Реальная музыка и фоновая навигация [ride]

**Цель:** проверить, что выбранные настройки меняют реальный звук и не оставляют музыку на паузе/приглушённой.

- Выполнить стендовые сценарии «Телефон: UI, музыка и перезапуск» из §5 с конкретным плеером.
- Подготовить сборку и выполнить с человеком сценарии «Фон на маршруте» из §5.
- Указать устройство, версию Android, плеер, сборку и фактические результаты. Непройденные/недоступные сценарии остаются открытыми.

**Готово:** наблюдены результаты таблицы §5; отдельно подтверждены экран-off и сохранение off после перезапуска. Автоматические тесты не закрывают этот этап.

**Что опровергнет посылку о достаточности audio focus:** выбранный пользователем плеер не исполняет pause/resume либо система не восстанавливает duck после освобождения request. Зафиксировать конкретную несовместимость, применить ограничение §6; не подменять результат командами toggle/play и не отмечать сценарий успешным.

## 4. Правила работы для исполнителя

- Перед изменениями перечитать затронутые участки и LSP references; пользовательские изменения сохранять. Публичные API переключать целиком, без legacy alias/shim.
- Новый этап не означает автоматический коммит. Если коммит запрошен — сначала `/code-review`, исправить или явно отклонить находки; сообщение на русском, в теле указать исправления по ревью. Для отдельно порученного этапа использовать отдельную рабочую ветку; сохранение этого документа не создаёт веток.
- Не удалять датированные полевые наблюдения/причины прежних решений при переносе кода. Generated Pigeon и локализации менять генераторами.
- Не ослаблять анализатор и не включать сознательно исключённый `discarded_futures` ради этой задачи. Тест, требующий сменить ожидаемую фразу/порог, означает сначала разбор регрессии, а не переписывание ожидания.
- `[ride]` завершает человек по наблюдению, исполнитель готовит воспроизводимые действия и сборку. При отсутствии устройства выполнить доступную часть и точно назвать непроверенные сценарии.
### 4.1 Критические файлы и опорные места

- `lib/nav/nav_loop.dart:63,133–139,186`: reset и fire-and-forget; речь не задерживает 1-Гц тик.
- `lib/state/dash_connection_alert_controller.dart:38–62`: общая настройка для обоих alert без маршрута; 12-секундный debounce не менять.
- `packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/OpendashDashEnginePlugin.kt:261–277,421–450`: generated unowned coroutine и teardown order.
- `packages/opendash_dash_engine/android/src/test/kotlin/com/opendash/opendash_dash_engine/media/MediaInfoProviderTest.kt:20–37`: coroutine rig/fake-adapters, пригодные для поздних callbacks.
- `test/state/map_theme_settings_test.dart:9–55`: SharedPreferences/ProviderContainer setup; не копировать проверки, лишь повторяющие defaults/enum.

## 5. Что считать успехом всего плана

План завершён, когда настройки сохраняются до первого announcement, off прекращает весь голос, все три аудиополитики подтверждены описанными проверками и отсутствуют регрессии локализованных подсказок. Стенд и заезд имеют отдельные доказательства; успешный unit test их не заменяет.

### Автоматические сценарии [auto]

Добавить `test/state/voice_settings_test.dart`, расширить `test/nav/voice_manager_test.dart`:

- Сохранить off/low/unchanged, новый manager + initialize: манёвр и оба connection announcement молчат. Включить: актуальная следующая фраза идёт с low/unchanged.
- Legacy off/chime migration, конфликт новых/старых полей, unknown/wrong type поля. После успешной миграции legacy удалён, при ошибке записи сохранён.
- Быстрые изменения enabled/volume/policy и задержанная запись: новый manager восстанавливает последний выбор; отказ одной записи не ломает следующую.
- Off при незавершённом speak вызывает stop до сохранения preferences; новых фраз нет. Повторное включение не воспроизводит уже отмеченный cue.
- Existing RU/EN/left-right/exit-number tests сохраняют ожидаемое поведение. Native error/focusDenied не ломают следующий nav tick и не повторяют старый cue.

Добавить `packages/opendash_dash_engine/android/src/test/kotlin/com/opendash/opendash_dash_engine/voice/VoiceOutputTest.kt` с fake adapters/runTest:

- Granted focus → speech → terminal release; unchanged не запрашивает focus; denied не произносит.
- A→B, затем late done/stop/error/focus loss A: B владеет своим request, результат каждой операции завершается один раз.
- Stop во время init, late success, новая B: A не звучит, B работает. Dispose не оставляет pending result/lease и игнорирует поздние callbacks.
- Completion, оба error overload, immediate failure, missing language, init failure, исключение stop/release: все достижимые ресурсы освобождены, result завершён. Проверять последствия, не только mock command list.
- Focus loss прекращает речь; поздний gain не возобновляет её; новая policy освобождает старую lease.

Команды после согласованного изменения, cwd — корень, кроме явно указанного:

```text
flutter test test/state/voice_settings_test.dart test/nav/voice_manager_test.dart test/nav/maneuver_cue_scheduler_test.dart
flutter test
# cwd: packages/opendash_dash_engine
flutter test
# cwd: android; JAVA_HOME — JBR 21 Android Studio, не Homebrew JDK 26
./gradlew :opendash_dash_engine:testDebugUnitTest --tests 'com.opendash.opendash_dash_engine.voice.VoiceOutputTest'
./gradlew :opendash_dash_engine:testDebugUnitTest
```

Не добавлять тесты source text/локализованных строк/Pigeon forwarding. UI проверять на настоящей поверхности.

### Телефон: UI, музыка и перезапуск [ride, стенд]

Для звуковых сценариев нужны Android 29+, установленные RU/EN TTS-данные, слышимая media volume и музыкальный плеер. UI уже проверен на HBN-LX9, Android 12/API 31, serial `2FK0224A22003381` (результаты в §0.1); наличие TTS-данных и реакция плеера этим не проверены. Notification Listener не требуется; повторить хотя бы один звуковой сценарий без него.

Из корня: `flutter run -d <serial> --dart-define-from-file=android/dart_defines.local.properties`. Открыть `/more`, найти карточку между валютой/медиа-доступом. Открыть sheet, изменить controls, закрыть свайпом, открыть снова; значения сохранены. Проверить RU/EN, узкий экран/увеличенный шрифт: нет overflow, controls доступны прокруткой. Обычный stop/relaunch сохраняет off и остальные значения.

Для звукового smoke без поездки сделать временный entrypoint `tool/voice_settings_smoke.dart` (новый проверочный файл, не продуктовый UI): настоящий VoiceManager.initialize, ProviderScope, локализации, настоящий sheet. Кнопки вызывают `resetTrip(); maybeAnnounce(...)` с right, GeoPoint(0,0), cumulativeMeters=1000, distanceM=50, remainingM=5000 и оба connection announcement. Для удобного interruption-теста отдельная кнопка вызывает настоящий `DashEngine.speakVoice` с «Через триста метров поверните направо, затем продолжайте движение прямо до следующего перекрёстка», `ru-RU` и выбранным gain/policy. Off проверять manager entrypoints, прямой вызов предназначен только для lifecycle/audio smoke. Запуск: `flutter run -d <serial> -t tool/voice_settings_smoke.dart`. Preview-кнопку в продукт не добавлять.

|Сценарий|Ожидаемый результат|
|---|---|
|Играющая музыка + duck|Тише во время фразы, исходный уровень после окончания/выключения; системный volume не меняется|
|Играющая музыка + pauseAndResume|Поддерживающий focus плеер делает паузу на фразу и возобновляется после завершения/отмены|
|Музыка уже на паузе|Подсказка не запускает её|
|unchanged|Музыка продолжает играть без воздействия приложения|
|Off, в том числе во время речи|Текущая фраза останавливается, следующие манёвры/связь молчат, focus освобождён|
|25/50/75% при одном системном уровне|Разная громкость речи, одинаковый уровень музыки между фразами|
|Замещение A новой B|После B нет зависшей паузы/duck и запоздалого вмешательства A|

Во время длинной фразы вручную поставить музыку на паузу, сменить трек/плеер, вызвать входящий звонок. Приложение не посылает play после речи и не возобновляет устаревшую фразу при focus gain. Зафиксировать конкретный плеер и его фактическую реакцию, не обещать универсальную.

### Фон на маршруте [ride]

Сборка: `flutter build apk --debug --dart-define-from-file=android/dart_defines.local.properties`; это поведенческая проверка, не замер производительности. Человек выбирает маршрут с манёвром дальше 300 м, запускает музыку и гасит экран при активном dash-подключении. Проверить 300/150/60 м и одну фразу прибытия; выбранная политика сохраняется, музыка восстанавливается после события. Затем off после перезапуска и отсутствие connection announcements в off.

На Android 15+ проверить foreground app и screen-off при активном foreground service. После окончательного teardown соединения service может отсутствовать: denied focus должен пропустить речь, а не запустить её поверх музыки/оставить pending Future. Lifetime фонового сервиса этой задачей не менять.

Прослушивание нельзя заменить счётчиком отправленных команд/тестами. Если телефон/дэш недоступен, выполнить доступные изменения/проверки и явно перечислить непроверенные device/ride сценарии, не отмечать их успешными.

## 6. Допущения и ограничения платформы

- Выбранные пользователем defaults и общая область настроек обязательны. Обратимые UX-решения: controls доступны в off, volume/policy действуют со следующей фразы, отдельный chime отсутствует.
- Используется стандартный Android audio focus, не принудительный контроль любого плеера. Громкость — коэффициент речи относительно текущего уровня устройства. Duck не гарантирует приглушение будильников/всех notification streams; speech-player может предпочесть паузу.
- Если конкретный плеер не поддерживает pause/resume по focus, не добавлять toggle/таймер/прямой play как скрытый fallback. Зафиксировать несовместимость, оставить off/unchanged и передать результат пользователю.
- В проекте фактически minSdk 29/targetSdk 36 (`android/app/build.gradle.kts`). Android 15+ может отказать в focus без foreground app/service; обрабатывать отказ, не добавлять новый service или API26 compatibility.
- Отсутствующие TTS-данные дают ошибку с освобождением ресурсов, не автоматическую установку/другой язык/beep. Для hardware проверки предварительно установить данные системными средствами.

Основания: [Android audio focus](https://developer.android.com/media/optimize/audio-focus), [ограничения Android 15](https://developer.android.com/about/versions/15/behavior-changes-15#audio-focus). Факты о телефоне/плеере фиксировать только после проверки.
