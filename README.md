# snatch-dash

**snatch-dash** is an open-source companion for a compatible bike dash:
navigation, ride history, garage tracking, expenses, wallpapers, and
media/call cards in one lightweight app.

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
- 🏍️ Vehicle profiles with active-vehicle selection, odometer, PUC/insurance
  dates, and service details.
- 🧰 Garage with spare-part intervals, service history, odometer editing, and
  mileage from fuel fill-ups.
- ⛽ Expenses across fuel, repairs, accessories, riding gear, food, stays,
  transport, and other categories, with monthly/all-time filtering and
  CSV/HTML export through the Android share sheet.
- 🖼️ Idle dash wallpapers — up to five local images/GIFs with crop/fit
  controls (video wallpaper isn't ported yet, see below).
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
  off-screen `Canvas` → `MediaCodec` H.264 → RTP pipeline, GPS tracking tuned
  for the ride, and the notification-listener/telecom media-and-call bridge.
  Dart drives it over a `MethodChannel`/`EventChannel` pair
  (`opendash_dash_engine`), pushing a compact "nav state" only when it
  changes so the power-critical render loop stays entirely native and
  jitter-free.

The off-screen renderer that gets streamed to the physical dash does **not**
use Yandex map tiles — Yandex MapKit has no public, redistributable raw-tile
API for compositing outside its own `MapView` (same restriction as Google
Maps). It uses its own pluggable raster tile source instead (see
`TileProvider.kt`'s doc comment for the reasoning and current source).

## 🚧 Known gaps vs. the original app

- No cloud sync / no sign-in — the original's optional Firebase sync was
  dropped; everything is local-only here.
- No video idle-wallpaper support (image/GIF only) — `image_picker`'s
  image/GIF flow has no single-call equivalent that also offers video.
- No in-app wallpaper crop-bias editor yet (wallpapers default to centered
  crop); the store's `updateCurrentOptions` API already supports it.
- No in-app shortcut to the notification-listener permission screen for the
  media/call bridge (open it manually: Settings → Apps → Special access →
  Notification access).
- Route maneuver glyphs: only `CONTINUE` is ever sent to the dash (unchanged
  from the original — the Yandex driving router used here doesn't expose
  per-step maneuvers either, and the original's OSRM-derived glyph codes were
  never verified against the dash beyond `CONTINUE`).

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
flutter test                                    # app: unit + widget tests
(cd packages/opendash_dash_engine && flutter test)  # plugin's Dart tests
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
| More | Dash pairing, idle wallpaper, currency |

## 🔐 Privacy

- App data is local-first; no analytics, no cloud sync, no account.
- Dash Wi-Fi credentials are stored via `EncryptedSharedPreferences`
  (native side, `DashConfig.kt`).
- Wallpaper media stays in app-private storage.
- Expense exports are created locally and shared only when you choose to
  share them.

## ⚠️ Notes

- Real dash behavior depends on firmware (validated against **11.63** only)
  and needs hardware testing — see
  [`docs/ON_HARDWARE_VERIFICATION.md`](docs/ON_HARDWARE_VERIFICATION.md)
  before trusting this on a ride.
- Yandex MapKit driving routes need internet access; the dash-stream tile
  source is cache-first for offline riding once prefetched.
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
