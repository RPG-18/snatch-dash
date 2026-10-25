# Голосовое ведение по маршруту

**Файлы:**
[`lib/nav/nav_loop.dart`](../lib/nav/nav_loop.dart),
[`lib/nav/voice_manager.dart`](../lib/nav/voice_manager.dart),
[`lib/l10n/app_ru.arb`](../lib/l10n/app_ru.arb),
[`lib/l10n/app_en.arb`](../lib/l10n/app_en.arb),
[`lib/nav/maneuver_cue_scheduler.dart`](../lib/nav/maneuver_cue_scheduler.dart),
[`lib/main.dart`](../lib/main.dart),
[`lib/nav/voice_settings.dart`](../lib/nav/voice_settings.dart),
[`lib/state/voice_settings.dart`](../lib/state/voice_settings.dart),
[`VoiceOutput.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/voice/VoiceOutput.kt),
[`VoiceAdapters.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/voice/VoiceAdapters.kt)

Подсистема озвучивает манёвры, прибытие и потерю/восстановление связи с дэшем
независимо от открытого экрана. У всех источников один сохранённый выбор:
включено/выключено, громкость 25/50/75% и политика музыки.

## Из чего собрана

| Объект | Чем владеет |
|---|---|
| `NavLoop` | 1-Гц навигационный тик, расстояния до следующего манёвра и назначения; сброс при старте маршрута и успешном перестроении |
| `ManeuverCueScheduler` | чистый автомат порогов, дедупликация и пропуск устаревших подсказок |
| `VoiceManager` | локализованные фразы, загруженный snapshot настроек, миграция preferences и последовательная запись изменений |
| `main()` | загрузка настроек до запуска контроллеров; до завершения загрузки объявления молчат |
| `VoiceSettingsController` | синхронный UI snapshot; изменения делегируются единственному storage owner `VoiceManager` |
| `VoiceOutput` | одна активная операция Android TTS, её terminal result и конкретный audio-focus lease |
| `OpendashDashEnginePlugin` | lifetime владельца речи от engine attach до detach, независимо от Activity и dash-соединения |

`NavLoop` не ждёт TTS: `maybeAnnounce()` запускается как fire-and-forget, чтобы
речь не задерживала следующий навигационный тик. Планировщик отмечает событие
до первого `await`, поэтому следующий тик не дублирует ещё не закончившуюся
речь.

## Последовательность и сброс

Для каждого значения `Maneuver.cumulativeMeters` возможны три события:

1. при `distanceM <= 300` — «Через 300 метров …»;
2. при `distanceM <= 150` — «Через 150 метров …»;
3. при `distanceM <= 60` — «Сейчас …».

Проверяется самый срочный порог. Если GPS перескочил один или несколько
порогов, звучит только актуальная подсказка, а дальние считаются завершёнными:
вход сразу на 140 м даёт только 150 м, вход сразу на 50 м — только «Сейчас».
Уже сменившийся в `NavEngine` манёвр не восстанавливается.

`distanceM` также не меньше прямого расстояния от реальной GPS-точки до
следующего манёвра. Поэтому близкая к финишу проекция не вызывает ложную
подсказку «Сейчас …» для манёвра прибытия, когда сам райдер далеко от него.

При `0 <= remainingM <= 30` прибытие имеет приоритет над манёвром и один раз
произносится «Вы прибыли в пункт назначения». `remainingM` из `NavEngine`
не меньше прямого расстояния от реальной GPS-точки до назначения: одной
проекции на конец маршрута для прибытия недостаточно. Этот же остаток
управляет признаком прибытия навигации и lifecycle MapKit; различаются только
пороги — 30 м для голоса и 25 м для навигации. Выход за 30 м снова разрешает
событие прибытия. Отрицательные расстояния и отсутствие следующего манёвра не
создают подсказку манёвра.

`Maneuver.cumulativeMeters` идентифицирует новый манёвр и разрешает все три
стадии заново. `VoiceManager.resetTrip()` полностью сбрасывает автомат при
старте маршрута и после успешного перестроения; связь перестроения с этим
сбросом описана в [`route_restructuring.md`](./route_restructuring.md).

## Настройки, язык и аудиофокус

Defaults: **включено, 50%, приглушать музыку**. Preferences:
`voice_enabled` (`bool`), `voice_volume` (`low|medium|high`),
`voice_music_policy` (`duck|pauseAndResume|unchanged`). Каждое отсутствующее,
неизвестное или неверно типизированное поле получает свой default без
потери корректных соседних полей. Валидный новый enabled приоритетнее
legacy `voice_mode`; иначе `off` становится false, `full/chime` — true.
Legacy key удаляется только после успешной записи всех трёх полей.
Обычный startup ничего не перезаписывает.

Изменения snapshot применяются до ожидания диска. Off сразу отправляет
`stopVoice`; громкость/политика действуют со следующей фразы. Записи
сериализованы; отказ записи логируется через `talker.error`, не отменяет
runtime выбор и не ломает следующие записи. Off возвращает до scheduler,
изменение настроек не сбрасывает уже отмеченные cues.

**С 25.10.2026 отменена временная принудительная запись `VoiceMode.full`**
при каждом запуске: она была введена для полевой проверки и перетирала
выбор райдера. `VoiceMode`, chime и `flutter_tts` удалены; иначе старые
источники речи могли бы обходить общее выключение и audio focus.

`VoiceManager` выбирает `ru-RU` для русской локали устройства и `en-US` для
остальных и вызывает Pigeon `speakVoice`. Future завершается по окончании
или отмене речи, а не при enqueue. Native error логируется и не выходит в
fire-and-forget навигацию; `cancelled`/`focusDenied` штатны, fallback нет.
Уже отмеченная подсказка после ошибки не повторяется.

| Политика | Audio focus |
|---|---|
| `duck` | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` |
| `pauseAndResume` | `AUDIOFOCUS_GAIN_TRANSIENT` |
| `unchanged` | без запроса focus |

TTS и focus используют `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` /
`CONTENT_TYPE_SPEECH`. Громкость — `KEY_PARAM_VOLUME`, коэффициент речи
относительно системной громкости; приложение не меняет stream volume и
не отправляет чужому плееру play/pause. Восстановление музыки зависит от
focus handler конкретного плеера, не гарантируется для всех приложений.
Android 15+ может отказать в focus без foreground app/service: фраза
пропускается, новый service ради неё не создаётся.

Владелец лениво инициализирует TTS без focus, обслуживает только последнюю
операцию и не держит очередь. Замещение сначала завершает A и освобождает
её request, затем запускает B с новым lease. TTS callbacks проверяют
utterance ID, focus callbacks — операцию и lease; поздние события A не
меняют B. Любая потеря focus отменяет речь, gain не возобновляет её.
Terminal path инвалидирует операцию до cleanup и завершает result ровно
один раз даже при исключениях stop/release. Stop во время initialization
не даёт позднему init произнести отменённую фразу. Init failure кешируется
до пересоздания engine owner; отсутствующие голосовые данные дают ошибку,
а не другой язык, beep или автоматическую установку.

Все переходы и callbacks исполняются на main. Host suspend работа принадлежит
plugin scope, с `UNDISPATCHED` для регистрации до ожиданий и
`Main.immediate` для доставки terminal result во время dispose до
`job.cancel()`. Engine detach останавливает/освобождает/завершает речь и
вызывает shutdown до controller teardown. Activity recreation, закрытие
настроек и разрыв dash-соединения владельца не уничтожают.

Отдельный focus-helper поверх прежнего `flutter_tts` отвергнут 25.10.2026:
его Dart callbacks не идентифицировали utterance, поэтому позднее завершение
старой фразы могло освободить focus новой. Идентичность и cleanup теперь
принадлежат одному нативному владельцу.

Все типы `ManeuverType` озвучиваются собственными RU/EN-фразами из
`AppLocalizations`: прямо, плавные и резкие повороты, развилки, обычные
повороты и развороты, въезд/съезд с круга и парома, съезды, финиш,
промежуточная точка и прибытие. У левых и правых манёвров, включая разворот,
разные направленные фразы; добавление типа требует дополнить исчерпывающий
`switch`, а не попасть в запасную фразу.

SDK-аннотации маршрута запрашиваются на английском для названия следующей
улицы на карточке дэша; они не источник текста для TTS. Русское устройство
сохраняет русские подсказки без второго запроса маршрута, английское —
английские. Ни название улицы, ни SDK-описание манёвра не добавляются к речи.
Для съезда с круга положительный `roundaboutExitNumber` произносится как
«съезд номер N» / «exit number N»: это количественный номер без требующего
склонения порядкового числительного. При отсутствии номера звучит локальная
фраза съезда без номера.

Пороги, дедупликация, скачки GPS и сброс проверяются детерминированно в
[`test/nav/maneuver_cue_scheduler_test.dart`](../test/nav/maneuver_cue_scheduler_test.dart).
Язык речи при английском названии улицы, сохранение левого/правого направления
и озвучивание номера съезда проверяются на границе TTS в
[`test/nav/voice_manager_test.dart`](../test/nav/voice_manager_test.dart).
Миграция, типизированное восстановление и порядок записей проверяются в
[`test/state/voice_settings_test.dart`](../test/state/voice_settings_test.dart);
native terminal paths — в
[`VoiceOutputTest.kt`](../packages/opendash_dash_engine/android/src/test/kotlin/com/opendash/opendash_dash_engine/voice/VoiceOutputTest.kt).
Работа TTS при погашенном экране и фактическое попадание фраз в точки маршрута
требуют заезда; критерии находятся в
[`docs/ON_HARDWARE_VERIFICATION_RU.md`](../docs/ON_HARDWARE_VERIFICATION_RU.md),
раздел 5. До такого заезда поведение на железе не считается подтверждённым.
