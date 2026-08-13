# Settings Screen («Ещё»)

**Файл:** [`lib/screens/settings_screen.dart`](../lib/screens/settings_screen.dart)
**Виджет:** `SettingsScreen` (`ConsumerStatefulWidget`)
**Route:** `/more` — вкладка «Ещё» в нижней панели (заголовок AppBar/подпись
вкладки — `l10n.settingsTitle`/`l10n.navMore`).

## Назначение

Управление подключением/сопряжением с WiFi dash, галерея обоев для режима
простоя, выбор валюты и статичная карточка «О приложении». Раздела
аккаунта/входа нет — Firebase-аутентификация в этом порте полностью
убрана.

## Читаемое/изменяемое состояние

| Провайдер / источник | Для чего |
|---|---|
| `dashEngineStateProvider` | `stage` подключения + `wifiStatus`, показываются как подзаголовок |
| `currencySettingsProvider` (`OpenDashCurrency`) | выбранная валюта, меняется через `select(v)` |
| `dashWallpaperStoreProvider` | до 5 слотов обоев (`_WallpaperGallery`) |
| `DashEngine.instance.getConfig()` | локальное состояние виджета `_config` (`ssid`, `ssidPrefix`, `password`), перечитывается после каждого изменяющего действия |
| `packageInfoProvider` (`PackageInfo`) | текущая версия приложения, карточка «Обновления» |
| `autoUpdateSettingsProvider` (`bool`) | тумблер «Автоматическое обновление» — гейтит и проверку на старте, и видимость остальной карточки |
| `updateChannelSettingsProvider` (`UpdateChannel`) | stable/nightly-канал самообновления, `SwitchListTile` внутри развёрнутой карточки «Обновления» |
| `appUpdateControllerProvider` (`AppUpdateState`) | статус проверки/загрузки/установки обновления |

## Разметка / действия

Вертикальный лайаут, все карточки расположениы в том же порядке, в каком перечислены.

1. **Карточка подключения к dash**:
   - Текущий SSID, либо `l10n.settingsNotPaired(prefix)`, если не сопряжено;
     в подзаголовке — стадия подключения + статус WiFi.
   - «Задать точный SSID dash» → диалог → `DashEngine.instance.setSsid(...)`.
   - «Задать пароль WiFi» (в подзаголовке — заводской пароль по умолчанию
     `12345678`) → диалог → `DashEngine.instance.setWifiPassword(...)`.
   - «Забыть dash» → `DashEngine.instance.forgetDash()` (повторный поиск по
     префиксу при следующем подключении).
2. **Обои dash** (`_WallpaperGallery`): горизонтальная лента миниатюр (тап —
   выбрать слот активным, долгое нажатие — очистить слот), плитка
   «добавить», открывающая `image_picker` (`pickMultiImage(limit: 5)`), и
   кнопка «Очистить всё», как только заполнен хотя бы один слот.
3. **Валюта**: `RadioGroup<OpenDashCurrency>` по всем значениям
   `OpenDashCurrency.values` (**RUB**, INR, USD, EUR, GBP, AUD, CAD, SGD, AED),
    в каждой строке — `currencyDisplayName(l10n, c)` + `(symbol)`. Значение по умолчанию **RUB**
4. **Карточка доступа к уведомлениям медиа/звонков**: статус
   («Доступ предоставлен» / «Доступ не предоставлен — нажмите, чтобы открыть
   настройки») по `DashEngine.instance.isNotificationAccessGranted()`; тап,
   пока доступ не выдан, → `DashEngine.instance.openNotificationAccessSettings()`
   (открывает системный экран `ACTION_NOTIFICATION_LISTENER_SETTINGS`).
   Статус перечитывается при `initState` и при возврате приложения на
   передний план (`AppLifecycleState.resumed`), поскольку выдаётся доступ
   вне приложения.
5. **Секция «Обновления»**: лейбл `l10n.settingsUpdatesTitle` над карточкой
   (тот же паттерн, что у секций «Обои dash»/«Валюта»), карточка —
   `_UpdatesCard` элементы расположены в вертикалном лайауте:
   - Текущая версия (`PackageInfo`)
   - Кнопка «Проверить обновления» выравнена по левой границе с текстом "Текущая версия". Справа спиннер, пока
     идет проверка; работает всегда, независимо от тумблера ниже.
   - Тумблер «Автоматическое обновление» (`autoUpdateSettingsProvider`).
   - Пока тумблер выключен, карточка на этом заканчивается. При включении —
     `AnimatedSize`-раскрытие, добавляются:
     - переключатель «Ночные сборки (nightly)»;
     - статус, зависящий от `AppUpdateState.status`: up to date / доступна
       версия N (+ «Скачать и установить») / прогресс загрузки / нужно
       разрешение «установка из неизвестных источников» (+ кнопка, ведущая в
       системные настройки) / ошибка проверки.
6. **Карточка «О приложении»**: статичные заголовок «SnatchDash» и подзаголовок,
   не локализуются (название бренда — как и заголовок AppBar на Главной).
   Карточка сожерит ссылку: [Условия использования Яндекс Карт](https://yandex.ru/legal/maps_api)
   Весь текст выравнен по иконке.

## Обновление приложения

Самообновление вне Google Play — источник: GitHub Releases репозитория
`RPG-18/snatch-dash`. Два канала:

- **stable** (по умолчанию) — `GET /releases/latest`, релиз публикует
  [`release.yml`](../.github/workflows/release.yml) по тегу `vX.Y.Z`, версия
  сравнивается по semver с `PackageInfo.version`.
- **nightly** — `GET /releases` (ищем первый тег `nightly-YYYYMMDD`), релизы
  публикует [`nightly.yml`](../.github/workflows/nightly.yml) каждую ночь;
  тег не semver, поэтому «новее» определяется сравнением с тегом последней
  установленной через это же приложение nightly-сборки
  (`SharedPreferences` ключ `installed_nightly_tag`, пишется только в момент
  установки — см. `lib/util/github_release.dart`).

Автопроверка на старте (`AppShell.initState`, после первого кадра) выполняется,
только если включён тумблер «Автоматическое обновление» (по умолчанию — да;
`AppUpdateController.checkOnLaunch` читает флаг сразу из `SharedPreferences`,
а не через `autoUpdateSettingsProvider` — тумблер ещё может не успеть
гидратироваться из хранилища к этому моменту). При найденном обновлении
выскакивает диалог («Скачать» ведёт на `/more` и запускает
`downloadAndInstall()`; «Позже» — просто закрывает, повторно спросит на
следующем запуске приложения, ничего не персистится). Сетевая ошибка при
автопроверке проглатывается молча — явную ошибку показывает только ручная
кнопка «Проверить обновления», которая работает независимо от тумблера.

Установка: APK скачивается в `cache/updates/` (см. `apk_downloader.dart`),
дальше `ApkInstaller`/`MainActivity.kt`'s `ru.snatchdash.app/updater`-канал
превращает путь в `content://` URI через уже существующий `FileProvider`
(`res/xml/file_paths.xml`) и запускает системный установщик
(`ACTION_VIEW` + `application/vnd.android.package-archive`). Требует
`REQUEST_INSTALL_PACKAGES` в манифесте плюс одноразовое согласие
пользователя «Установка из неизвестных источников» для этого конкретного
приложения — если не выдано, поток останавливается на
`AppUpdateStatus.needsInstallPermission`; `_SettingsScreenState`'s
`didChangeAppLifecycleState` (уже был нужен для доступа к уведомлениям)
повторяет `downloadAndInstall()` при возврате из системных настроек.

## Заметки

- Локализованные названия валют формируются через `switch` в
  `currencyDisplayName(l10n, currency)` в
  [`lib/state/currency_settings.dart`](../lib/state/currency_settings.dart),
  а не хранятся полем в самом enum `OpenDashCurrency` — так enum остаётся
  чистым описанием данных/формата.
- Доступ Notification Listener — не runtime-разрешение с диалогом, а тумблер
  в системных настройках Android; манифест/сервис
  (`OpenDashNotificationListener`) уже объявлены в
  [`packages/opendash_dash_engine`](../packages/opendash_dash_engine), эта
  карточка — единственная точка входа в приложении, которая ведёт туда
  пользователя.
