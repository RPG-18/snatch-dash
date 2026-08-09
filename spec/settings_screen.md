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

## Разметка / действия

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
   `OpenDashCurrency.values` (INR, USD, EUR, GBP, AUD, CAD, SGD, AED,
   **RUB**), в каждой строке — `currencyDisplayName(l10n, c)` + `(symbol)`.
4. **Карточка доступа к уведомлениям медиа/звонков**: статус
   («Доступ предоставлен» / «Доступ не предоставлен — нажмите, чтобы открыть
   настройки») по `DashEngine.instance.isNotificationAccessGranted()`; тап,
   пока доступ не выдан, → `DashEngine.instance.openNotificationAccessSettings()`
   (открывает системный экран `ACTION_NOTIFICATION_LISTENER_SETTINGS`).
   Статус перечитывается при `initState` и при возврате приложения на
   передний план (`AppLifecycleState.resumed`), поскольку выдаётся доступ
   вне приложения.
5. **Карточка «О приложении»**: статичные заголовок «SnatchDash» и подзаголовок,
   не локализуются (название бренда — как и заголовок AppBar на Главной).
   Карточка сожерит ссылку: [Условия использования Яндекс Карт](https://yandex.ru/legal/maps_api)
   Весь текст выравнен по иконке.


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
