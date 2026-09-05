# snatch-dash

**snatch-dash** is an open-source companion for a compatible bike dash:
navigation, ride history, garage tracking, expenses, and media/call cards in
one lightweight app.

It renders its own dash view off-screen, encodes it as H.264, and streams it
over Wi-Fi so the phone screen can stay off during a ride.

This is a **Flutter port of [subtlesayak/open-dash](https://github.com/subtlesayak/open-dash)**
(the original native Kotlin/Compose app), rebuilt on **Yandex map and
navigation services** (Yandex MapKit) in place of Google Maps — so it works
reliably **within the Russian Federation**, where Google's mapping/location
services are unavailable or unreliable. 

## ✨ Features

- 🧭 Bike dash navigation with Yandex MapKit driving routes, in-app map
  preview, ETA, remaining distance, GPS status, and off-route recalculation.
- 🗺️ Offline map packs for the dash — per-region `.pmtiles` downloaded over
  the system `DownloadManager`, verified by sha256, and rendered on-device by
  MapLibre. **The dash needs at least one pack**: without it, navigation is
  gated off in the app (routing and destination search still need the network,
  see «Known gaps»).
- 🏍️ Vehicle profiles with active-vehicle selection, odometer, PUC/insurance
  dates, and service details.
- 🧰 Garage with spare-part intervals, service history, odometer editing, and
  mileage from fuel fill-ups.
- ⛽ Expenses across fuel, repairs, accessories, riding gear, food, stays,
  transport, and other categories, with monthly/all-time filtering and
  CSV/HTML export through the Android share sheet.
- 🎵 Media and caller cards projected to the dash while streaming.
- 🗣️ Turn-by-turn voice guidance (off / chime / spoken).
- 🔔 Local maintenance-due reminders.
- 🔒 Local-first storage (SQLite via `sqflite`); no cloud sync, no account.

## 📦 Architecture

Two halves, split at the Android-API boundary:

- **Flutter/Dart app** (`lib/`) — all UI, state (Riverpod), local persistence
  (`sqflite`), the in-app map (`yandex_mapkit`), and the pure navigation math
  (`lib/nav/`: route progress, off-route detection, ETA).
- **Native Android plugin** (`packages/opendash_dash_engine`) — everything
  that has to stay native: the dash Wi-Fi pairing + K1G protocol session, the
  off-screen map → `Canvas` overlays → `MediaCodec` H.264 → RTP pipeline, GPS
  tracking tuned for the ride, and the notification-listener/telecom
  media-and-call bridge. Dart drives it over a `MethodChannel`/`EventChannel`
  pair (`opendash_dash_engine`), pushing a compact "nav state" only when it
  changes so the power-critical render loop stays entirely native and
  jitter-free.

The off-screen renderer streamed to the physical dash does **not** use Yandex
map tiles — Yandex MapKit has no public, redistributable raw-tile API for
compositing outside its own `MapView` (the same restriction as Google Maps).
It draws with **MapLibre Native off-screen over locally stored `.pmtiles`
packs** built from OpenStreetMap data by [`tools/planetiler`](tools/planetiler)
and served from S3; route, rider arrow, destination pin and status pills are
composited on top with `Canvas`. The frame pipeline, style assembly and the
multi-pack source model are specified in
[`spec/drawing_from_local_tiles.md`](spec/drawing_from_local_tiles.md); the
corpus format and download protocol in
[`spec/remote_map_server.md`](spec/remote_map_server.md).

This adds MapLibre Native to the APK — 7.5 MiB of `libmaplibre.so` on
`armeabi-v7a`, 10.3 MiB on `arm64-v8a`.

Map data is © OpenStreetMap contributors (ODbL); the styles and sprites come
from OpenMapTiles (CC BY 4.0), the glyphs are Noto Sans (SIL OFL 1.1), and
MapLibre Native is BSD-2-Clause. The dash frame itself carries no attribution
overlay — at 526×300 under a round bezel it costs a visible share of the frame
— so the credit lives in Settings → About. Per-file breakdown:
[`ATTRIBUTION.md`](packages/opendash_dash_engine/android/src/main/assets/ATTRIBUTION.md).

## 🚧 Known gaps vs. the original app

- No cloud sync / no sign-in — the original's optional Firebase sync was
  dropped; everything is local-only here.
- Route maneuver glyphs: only `CONTINUE` is ever sent to the dash (unchanged
  from the original — the Yandex driving router used here doesn't expose
  per-step maneuvers either, and the original's OSRM-derived glyph codes were
  never verified against the dash beyond `CONTINUE`).
- Offline is only half-done: the **map** on the dash renders without a
  network, but **routing and destination search still go to Yandex over the
  network**. A ride along an already-built route survives losing connectivity;
  building a new route — or recalculating after going off-route — does not.
  Options for closing that gap are surveyed in
  [`docs/offline_mode_ru.md`](docs/offline_mode_ru.md).
- Offline packs cover zoom 11–14, so a whole-trip overview (a 300 km route on
  one screen) is not available offline — at the lowest step the 526×300 frame
  shows a city agglomeration.
- No adaptive fps/bitrate switching for the dash video stream — the original
  drops from 4 fps/200 kbps to 2 fps/100 kbps on long straights far from a
  maneuver; this port always encodes at the "High" preset (see
  [`spec/video.md`](spec/video.md)).

## 🛠️ Build From Source

Requires the Flutter SDK (this was built against Flutter 3.44 / Dart 3.12)
and an Android SDK with `ANDROID_HOME`/`sdk.dir` set up.

```bash
git clone <this-repo>
cd snatch-dash
flutter pub get
```

### Yandex API key (required for the map and destination search)

1. Generate a key at <https://developer.tech.yandex.ru/services/>. Yandex
   issues one API key shared across its map products, covering both the
   MapKit Mobile SDK and the Geosuggest API this app uses.
2. Add it to `android/dart_defines.local.properties` (gitignored):

   ```properties
   YANDEX_API_KEY=your-key-here
   ```

   See `android/dart_defines.defaults.properties` for the placeholder/template.
   This is a separate file from `android/local.properties` because Flutter's
   `--dart-define-from-file` parser rejects dotted keys such as `sdk.dir`.

Without a real key the app builds and runs, but the map stays blank and the
Route screen's destination search returns no suggestions.

```bash
flutter build apk --debug --dart-define-from-file=android/dart_defines.local.properties
flutter run --dart-define-from-file=android/dart_defines.local.properties  # install + run on a connected device/emulator
```

### Tests

```bash
flutter test                                        # app: unit + widget tests
(cd packages/opendash_dash_engine && flutter test)  # plugin's Dart tests
(cd android && ./gradlew :opendash_dash_engine:testDebugUnitTest)  # plugin's Kotlin tests
```

The Kotlin tests need **JDK 21 — the JBR shipped with Android Studio**, not
Homebrew's `openjdk` (that is 26, and AGP fails on it: `jlink` cannot transform
`core-for-system-modules.jar`). See CLAUDE.md, «Сборка и тесты».

The `sqflite`-backed repository tests
(`test/data/sqlite_garage_repository_test.dart`) use `sqflite_common_ffi`
against isolated temp-file databases — no device/emulator needed.

## 🧭 Main Tabs

| Tab | What it does |
| --- | --- |
| Home | Connect status, saved destinations, recent rides |
| Vehicles | Add/edit vehicles and choose the active vehicle |
| Expenses | Add, filter, review, and export expenses |
| Garage | Odometer, mileage, spare parts, service logging |
| More | Dash pairing, offline maps, map theme, currency, logs, updates |

## 🔐 Privacy

- App data is local-first; no analytics, no cloud sync, no account.
- Dash Wi-Fi credentials are stored via `EncryptedSharedPreferences`
  (native side, `DashConfig.kt`).
- Expense exports are created locally and shared only when you choose to
  share them.

## ⚠️ Notes

- Real dash behavior depends on firmware (validated against **11.63** only)
  and needs hardware testing — see
  [`docs/ON_HARDWARE_VERIFICATION.md`](docs/ON_HARDWARE_VERIFICATION.md)
  before trusting this on a ride.
- Yandex MapKit driving routes and destination search need internet access.
  The dash map does not: it renders from downloaded packs, which the rider
  picks explicitly in Settings → Offline maps.
- Media/call behavior can vary by Android version, dialer, and media app.

## 🤝 Contributing

Issues and pull requests are welcome. Please remove personal data from logs
and screenshots before sharing: coordinates, SSIDs, caller names, account
IDs, tokens, and device identifiers.

## References

- [open-dash](https://github.com/subtlesayak/open-dash) — the original
  native Android app this was ported from.
- [norbertFeron/better-dash](https://github.com/norbertFeron/better-dash) —
  the reverse-engineered dash protocol reference both projects build on.
