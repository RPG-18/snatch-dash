# Home Screen (Главная)

**Файл:** [`lib/screens/home_screen.dart`](../lib/screens/home_screen.dart)
**Виджет:** `HomeScreen` (`ConsumerWidget`)
**Route:** `/home` — начальный маршрут, вкладка «Главная» в нижней панели
(первая ветка `StatefulShellRoute` в
[`lib/router/app_router.dart`](../lib/router/app_router.dart)). Также
родитель для вложенных маршрутов `route`/`dash`/`rides`.

## Назначение

Стартовая вкладка: статус подключения к дэшу с первого взгляда, два быстрых
действия, список сохранённых мест и сводка по истории поездок.

## Читаемое состояние

| Провайдер | Откуда | Для чего |
|---|---|---|
| `dashEngineStateProvider` | `state/dash_engine_state.dart` | `DashStage` → карточка статуса (подключено/поиск/офлайн) и подпись/иконка быстрых действий |
| `savedDestinationsControllerProvider` | `state/saved_destinations_controller.dart` | Список сохранённых мест |
| `ridesControllerProvider` | `state/rides_controller.dart` | Количество поездок + суммарный пробег для сводки |
| `vehicleStoreProvider` | `state/vehicle_store.dart` | `title` активной техники — показывается чипом на карточке статуса |
| `routeControllerProvider.notifier` | `state/route_controller.dart` | `selectSaved(location)` при тапе на сохранённое место |

## Разметка / действия

1. **Карточка статуса** — аватар + цветная точка + подпись/подзаголовок в
   зависимости от `DashStage`:
   - `streaming` → «Подключено» (основной цвет темы)
   - `connecting`/`authenticating` → «Поиск…» (жёлтый)
   - остальное → «Не в сети» (`onSurfaceVariant`)

   Активная техника показывается ниже как `Chip`.
2. **Карточка быстрых действий** (два `ListTile`):
   - «Начать навигацию» → `context.push('/home/route')`
   - «Экран dash» (если `connected`) / «Подключиться к dash» (иначе) →
     `context.push('/home/dash')`
3. **Сохранённые места** — текст-заглушка при пустом списке, либо карточка
   с тапаемыми элементами (название + координаты). Тап вызывает
   `routeControllerProvider.notifier.selectSaved(...)` и переход на
   `/home/route`.
4. **Сводка по поездкам** — заглушка при пустом списке, либо один элемент с
   `l10n.homeRidesSummary(count, totalKm)` (ICU-plural — см. ключ
   `homeRidesSummary` в `lib/l10n/app_ru.arb`), переход на `/home/rides`.

## Заметки

- Заголовок в AppBar «SnatchDash» — это название бренда, и оно намеренно
  **не** локализуется (так же, как карточка «О приложении» в Настройках и
  тема письма при экспорте CSV/HTML).
- На этом экране нет FAB.
- Полностью локализован (RU/EN) через `AppLocalizations` — см. ключи с
  префиксом `home*` в [`lib/l10n/app_en.arb`](../lib/l10n/app_en.arb).
