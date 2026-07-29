# Rides Screen (Поездки)

**Файл:** [`lib/screens/rides_screen.dart`](../lib/screens/rides_screen.dart)
**Виджет:** `RidesScreen` (`ConsumerWidget`)
**Route:** `/home/rides` — открывается с тайла сводки поездок на Главной.

## Назначение

Выбор маршрута между текущей точкой и выбранным пунктом назначения.

## Читаемое/изменяемое состояние

| Провайдер | Для чего |
|---|---|
| `ridesControllerProvider` | список поездок (`distanceKm`, `durationSec`, `startMs`, `avgSpeedKmh`) |
| `ridesControllerProvider.notifier` | `deleteRide` |

## Разметка / действия

- Пустое состояние: «Поездок пока нет» (использует общий с Главной ключ
  локализации `homeNoRidesYet`).
- Иначе — `ListView.separated` из карточек, в каждой:
  - Заголовок: `{distanceKm} км · {minutes} мин` (`l10n.rideDurationSummary`)
  - Подзаголовок: `{date} · ср. {speed} км/ч`, дата форматируется через
    `DateFormat.yMd(locale)` (`l10n.rideDateAvgSpeed`)
  - Иконка удаления в трейлинге → `deleteRide(ride)`

## Заметки

- Поездки заполняются нативным рекордером поездок по завершении сессии —
  на самом экране нет ручного управления «начать/остановить запись».
