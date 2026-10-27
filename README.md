# snatch-dash

[Русская версия](README_RU.md)

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

**Android 10+ (API 29)**. The UI follows the device language: Russian or
English; there is no in-app language switch.

## ✨ Features

- 🧭 Bike dash navigation with Yandex MapKit driving routes, in-app map
  preview, ETA, remaining distance, GPS status, and off-route recalculation.
- 🗺️ Offline map packs for the dash — per-region `.pmtiles` downloaded over
  the system `DownloadManager`, verified by sha256, and rendered on-device by
  MapLibre. **The dash needs at least one pack**: without it, navigation is
  gated off in the app (routing and destination search still need the network,
  see "Known gaps").
- 🏍️ Vehicle profiles with active-vehicle selection, odometer, PUC/insurance
  dates, and service details.
- 🧰 Garage with spare-part intervals, service history, odometer editing, and
  mileage from fuel fill-ups.
- ⛽ Expenses across fuel, repairs, accessories, riding gear, food, stays,
  transport, and other categories, with monthly/all-time filtering and
  CSV/HTML export through the Android share sheet.
- 🎵 Media/caller cards, album art, PLAYING/PAUSED indicators, and dash-button
  playback, track and music-volume controls. Media integration needs Android
  notification access; support depends on the player and dash firmware.
- 🗣️ Spoken navigation cues at 300/150/60 m, arrival and dash-connection
  announcements. More → Voice guidance provides on/off, speech volume
  25/50/75%, and music policies: duck, pause/resume, or leave unchanged.
  Settings persist across restarts; speech uses Android TTS and audio focus.
- 🔔 Local maintenance-due reminders.
- 🔒 Local-first storage (SQLite via `sqflite`); no cloud sync, no account.

## 📦 Architecture

Two halves, split at the Android-API boundary:

- **Flutter/Dart app** (`lib/`) — all UI, state (Riverpod), local persistence
  (`sqflite`), the in-app map (`yandex_maps_mapkit`), navigation math and
  lifecycle (`lib/nav/`: route progress, off-route detection, ETA and cues).
- **Native Android plugin** (`packages/opendash_dash_engine`) — everything
  that has to stay native: the dash Wi-Fi pairing + K1G protocol session, the
  off-screen map → `Canvas` overlays → `MediaCodec` H.264 → RTP pipeline, GPS
  tracking tuned for the ride, MediaSession/notification-listener and telecom
  integration, and Android TTS/audio focus. Dart sends navigation updates
  without putting the frame loop on the Flutter UI thread.

The plugin boundary is defined by
[`pigeons/dash_engine.dart`](packages/opendash_dash_engine/pigeons/dash_engine.dart):
Pigeon generates the typed Dart/Kotlin commands and state, button and debug-log
streams. Change the schema and regenerate both sides, rather than editing
generated files or maintaining string-keyed channel contracts.

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

Map data is © OpenStreetMap contributors (ODbL); the styles and sprites come
from OpenMapTiles (CC BY 4.0), the glyphs are Noto Sans (SIL OFL 1.1), and
MapLibre Native is BSD-2-Clause. The dash frame itself carries no attribution
overlay — at 526×300 under a round bezel it costs a visible share of the frame
— so the credit lives in Settings → About. Per-file breakdown:
[`ATTRIBUTION.md`](packages/opendash_dash_engine/android/src/main/assets/ATTRIBUTION.md).

## 🚧 Known gaps vs. the original app

- No cloud sync / no sign-in — the original's optional Firebase sync was
  dropped; everything is local-only here.
- Route maneuver glyphs are sent per step (turns, forks, U-turns, roundabouts
  with rotation direction and exit number, ferries — see `_dashCodeByType` and
  `Maneuver.dashCode` in `lib/nav/route.dart`), but only `0x09` ("straight
  ahead") is **verified against a physical dash**. Anything unmapped falls back
  to it. The rest are read off the firmware's own glyph table and still want a
  ride to confirm.
- Offline is only half-done: the **map** on the dash renders without a
  network, but **routing and destination search still go to Yandex over the
  network**. A ride along an already-built route survives losing connectivity;
  building a new route — or recalculating after going off-route — does not.
  Options for closing that gap are surveyed in
  [`docs/offline_mode_ru.md`](docs/offline_mode_ru.md).
- Offline packs cover zoom 10–15 (rebuilt 2026-09-09; the ladder in the app
  runs 10.00–15.00). A whole-trip overview is still not available offline — the
  widest step puts roughly 20 km across the 526×300 frame, so a 300 km route
  does not fit on one screen.
- Adaptive fps/bitrate switching exists but keys off a different signal than
  the original's. The port drops 4 fps/200 kbps → 2 fps/100 kbps when the map
  stops moving (ground speed with hysteresis, `FrameRatePolicy`), where the
  original keyed off distance to the next maneuver — so a long straight at
  speed still streams at the higher rate here. See
  [`spec/video.md`](spec/video.md).
- Weather is not implemented: the dash currently receives fixed sunny weather
  and +25 °C, not a live forecast.
- Basic media controls, album art, PLAYING/PAUSED and inactive behavior were
  confirmed by the rider on Huawei HBN-LX9 and Xiaomi 2606FRN72Y on 2026-10-26.
  Art-transfer boundaries, media/video interaction, independent volumes and the
  extended button matrix remain open hardware checks; see the
  [multimedia plan](plans/active/2026-10-04-multimedia-support.md).
- RuVoice navigation cues, their distance thresholds, music interaction and
  screen-off announcements were confirmed by the rider on Huawei HBN-LX9
  (Android 12) on 2026-10-27. This does not verify every music policy, speech
  volume, arrival, English TTS or Android 15+ audio-focus behavior; see the
  [voice settings plan](plans/active/2026-10-25-voice-settings.md).

## Installation and updates

Download an APK from [GitHub Releases](https://github.com/RPG-18/snatch-dash/releases)
and approve installation from that source in Android when prompted.
More → Updates supports stable (default) and nightly releases. The automatic
update switch controls checks on startup; manual checks, download and the
Android installer remain available when it is off.

## 🛠️ Build From Source

Requires Flutter (verified with **Flutter 3.44.6 / Dart 3.12.2**),
an Android SDK with API 36 and `ANDROID_HOME`/`sdk.dir` configured, and
**JDK 21** to run Gradle.

Check the Java version rather than assuming Android Studio's bundled JBR is
21: newer Studio installations may ship JBR 25. Use JBR 21 if available, or
another installed JDK 21; the native tests and Android Lint were also verified
with Homebrew `openjdk@21` 21.0.12.1. JDK 25/26 is not the supported build setup.
Set `JAVA_HOME` to the JDK 21 home and point Flutter and Gradle at the same
installation (`flutter config --jdk-dir="$JAVA_HOME"` and
`org.gradle.java.home` in `~/.gradle/gradle.properties`).

```bash
git clone https://github.com/RPG-18/snatch-dash.git
cd snatch-dash
flutter pub get
(cd packages/opendash_dash_engine && flutter pub get)
```

The plugin needs its own `pub get`: the root resolve does not install the
path package's dev dependencies, including Pigeon, on a fresh clone.

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

### Analysis and tests

```bash
flutter analyze
flutter test                                        # app: unit + widget tests
(cd packages/opendash_dash_engine && flutter test)  # plugin's Dart tests
(cd android && ./gradlew :opendash_dash_engine:testDebugUnitTest)
(cd android && ./gradlew :opendash_dash_engine:lintDebug)
```

Android Lint checks API-level compatibility that Dart analysis and the Kotlin
compiler do not. Run it when changing native Kotlin, manifests or Gradle.
For plugin API changes, regenerate the boundary from the plugin directory:

```bash
(cd packages/opendash_dash_engine && dart run pigeon --input pigeons/dash_engine.dart)
```

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
| More | Dash pairing, background access, offline maps, map theme, currency, voice guidance, notification access, logs, updates |

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
- For screen-off rides, review More → Background work and any additional
  manufacturer battery restrictions. Voice guidance needs an installed Android
  TTS engine with voice data for the device's language; missing data does not
  silently fall back to another language or a chime.

## 🤝 Contributing

Issues and pull requests are welcome. Please remove personal data from logs
and screenshots before sharing: coordinates, SSIDs, caller names, account
IDs, tokens, and device identifiers.

## References

- [open-dash](https://github.com/subtlesayak/open-dash) — the original
  native Android app this was ported from.
- [norbertFeron/better-dash](https://github.com/norbertFeron/better-dash) —
  the reverse-engineered dash protocol reference both projects build on.
