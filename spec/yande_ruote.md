# Параметры рисования маршрута (Yandex MapKit)

Фиксирует визуальные параметры, которыми маршруты рисуются на карте в
приложении (не путать с рендером на физическом дэше — там свой нативный
движок). Два места рисования:

- [`lib/map/opendash_map.dart`](../lib/map/opendash_map.dart) —
  `OpenDashMap`, один активный маршрут (экран [Dash](dash_screen.md)).
- [`lib/map/route_options_map.dart`](../lib/map/route_options_map.dart) —
  `RouteOptionsMap`, несколько маршрутов-альтернатив (экран
  [Маршруты](reoute_preview_screen.md)).

Оба виджета имеративно работают с `window.map.mapObjects`
(`RootMapObjectCollection`) через `yandex_maps_mapkit` — на каждый
пересчёт `objects.clear()` и перерисовка с нуля.

## Линия маршрута

| Параметр | `OpenDashMap` | `RouteOptionsMap` |
|---|---|---|
| Толщина (`LineStyle.strokeWidth`) | `5.5` | `5.5` |
| Цвет, маршрут выбран/единственный | по пробкам (см. ниже), фолбэк `0xFF4285F4` (синий) | по пробкам (см. ниже), фолбэк `0xFF34A853` (зелёный) |
| Цвет, маршрут не выбран | — | `0xFF9C27B0` (фиолетовый), сплошной, без пробок |
| Порядок отрисовки | — | невыбранные маршруты рисуются первыми, выбранный — последним (поверх остальных) |

Невыбранные маршруты в `RouteOptionsMap` красятся сплошным цветом через
`PolylineMapObject.setStrokeColor(Color)`. Активный маршрут в
`OpenDashMap` и выбранный маршрут в `RouteOptionsMap` красятся по
сегментам (раскраска по пробкам, см. ниже) — общей функцией
`applyJamColors` из [`lib/map/jam_paint.dart`](../lib/map/jam_paint.dart).
Градиентов/пунктира нигде нет.

## Метки (placemarks)

- `OpenDashMap`: метка назначения (`dest`), в `navMode` — метка райдера
  (`objects.addPlacemarkWithPoint`, `direction` = азимут по `riderBearing`).
- `RouteOptionsMap`: метки старта (`origin`) и назначения (`destination`),
  если координаты переданы. Иконки — дефолтные (кастомный `PlacemarkIcon`
  не задаётся).

## Камера / автоподбор зума

`RouteOptionsMap._fitBounds()` (вызывается только когда меняется список
маршрутов, не при смене выбора):

1. Строится bounding box по геометрии **всех** маршрутов сразу
   (`points = routes.expand((r) => r.geometry)`).
2. Bounding box расширяется на **8%** по широте и долготе с каждой
   стороны (`_boundsPadding = 0.08`), чтобы линии не упирались в край
   экрана.
3. `map.cameraPositionForGeometry(Geometry.fromBoundingBox(bounds))` без
   `focusRect` (то есть отступ считается в градусах geo-bbox, а не в
   экранных пикселях/`ScreenRect`).
4. Анимация перемещения камеры: `Animation(type: Smooth, duration: 0.6)`.

`OpenDashMap._moveCamera()` (для сравнения, другой набор режимов):

- `fitRoute: true` — тот же bounding-box подход, но **без** 8%-отступа.
- `navMode: true` — `zoom: 17.5` (`_navZoom`), `tilt: 45.0` (`_navTilt`),
  `azimuth` = `riderBearing`.
- обычный follow-режим — `zoom: 15.5` (`_followZoom`), `tilt: 0`,
  `azimuth: 0`.
- нет ни рута, ни позиции райдера, есть только `dest` — `zoom: 13`, без
  анимации (`map.move` без `animation:`).

## Раскраска по пробкам

Используется в обоих местах:

- [Dash](dash_screen.md) — `OpenDashMap` красит по пробкам весь активный
  маршрут (это единственный маршрут на карте).
- [Маршруты](reoute_preview_screen.md) — `RouteOptionsMap` красит по
  пробкам только выбранный маршрут; невыбранные остаются сплошными
  фиолетовыми (пробки на них не показываем, чтобы не перегружать карту
  сразу несколькими раскрасками).

`nav.Route.jamSegments` (`lib/nav/route.dart`) — `List<JamLevel>`, один
элемент на сегмент геометрии (как и `speedLimits` у SDK), пустой список
если данных о пробках нет. `JamLevel` — свой доменный enum
(`unknown`/`blocked`/`free`/`light`/`hard`/`veryHard`), заведён вместо
прямой зависимости от SDK-типа `JamType`; маппинг из
`ymk.DrivingRoute.jamSegments` происходит в `Router._toRoute`
([`lib/nav/router.dart`](../lib/nav/router.dart)) — то есть у **каждого**
маршрута из `Router.routes(routesCount: N)`, не только у первого.

`applyJamColors()` в [`lib/map/jam_paint.dart`](../lib/map/jam_paint.dart)
красит сегменты `PolylineMapObject` вручную через палитру
(`setPaletteColor` + `setStrokeColors`), а не через SDK-хелпер
`RouteHelper.addJams` — тот принимает нативный `DrivingRoute`, которого у
доменной модели `nav.Route` больше нет. Если `jamSegments.length` не
совпадает с числом сегментов геометрии (данные недоступны/устарели) —
сплошной цвет-фолбэк: синий `0xFF4285F4` в `OpenDashMap`, зелёный
`0xFF34A853` в `RouteOptionsMap`.

Палитра (соответствует дефолтной `RouteHelper.createDefaultJamStyle()`
из SDK):

| `JamLevel` | Цвет |
|---|---|
| `unknown` | `0xFF909090` (серый) |
| `blocked` | `0xFF000000` (чёрный) |
| `free` | `0xFF00FF00` (зелёный) |
| `light` | `0xFFFFFF00` (жёлтый) |
| `hard` | `0xFFFF0000` (красный) |
| `veryHard` | `0xFFA00000` (тёмно-красный) |
