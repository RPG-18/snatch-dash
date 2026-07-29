# OpenDash (Flutter port) — On-Hardware Re-Verification Checklist

The dash protocol/video pipeline was ported from the original Kotlin app
(`open-dash`) into `packages/opendash_dash_engine` — mostly byte-for-byte,
same constants, same packet sequencing (see the phase notes in the project's
migration plan). But it now runs across a language/channel boundary
(Dart ⇄ platform channel ⇄ Kotlin) it didn't cross before, and a few pieces
were rebuilt rather than copied (the native tick loop, the Yandex-router-based
nav math). None of that can be confirmed from source alone — it needs a real
ride on a real Tripper Dash, same as the original project's own TODO required.

Firmware target: **11.63** (the only firmware this was ever validated
against). If the rider's dash runs different firmware, treat every item below
as unverified again, not just re-confirmed.

Legend: `[ ]` not yet checked · `[x]` confirmed on hardware · `[!]` checked,
found a regression (note it under **Findings** at the bottom).

---

## 1. Pairing / auth handshake

- [ ] Prefix discovery (`RE_*`) finds the dash and the system Wi-Fi dialog
      appears (`DashWifiManager.connect` with `prefixMatch=true`).
- [ ] Exact-SSID reconnect works after the SSID is learned once
      (`DashConfig.ssid` persisted, `SettingsScreen` → "Set exact dash SSID"
      reflects it).
- [ ] RSA/AES handshake completes (`07 00`/`07 03` → `q3c.d` → `07 01 01`)
      within the 15 s timeout — watch `DashEngineController`/`DashSession`
      logs (`adb logcat -s DashSession`).
- [ ] Auth rejection retry path fires correctly if the dash sends `07 01 !=
      01` (should retry up to 5×, not hang).
- [ ] "Forget dash" (Settings) actually clears the learned SSID and the next
      connect re-discovers by prefix.

## 2. Streaming / video pipeline

- [ ] Dash decoder accepts the stream (map or wallpaper appears on the round
      display, not stuck on the loading spinner) — confirms SPS normalization
      (`NalProcessor.normalizeSpsForDash`) still matches fw 11.63's whitelist.
- [ ] IDR bundling (SPS+PPS+IDR with Annex-B start codes) still satisfies the
      dash decoder — no periodic decoder resets/black frames.
- [ ] Frame rate holds ~4 fps while moving, drops to ~2 fps when
      stationary/idle (`DashEngineController.FPS_MOVING`/`FPS_IDLE`) — no
      visible stutter vs. the original app's feel.
- [ ] Encoder auto-recovers after a forced failure (kill/reconnect Wi-Fi
      mid-stream 3×) without requiring app restart (`startStream`'s
      failure-count rebuild path).

## 3. Navigation (Dart ⇄ native split — new in this port)

This is the part most worth extra scrutiny: `NavEngine`/`Router` moved to
Dart (Phase 2), and the native side only receives a 1 Hz `setNavState` push
from `NavLoop` instead of computing nav math itself like the original
`DashViewModel.tick` did in-process.

- [ ] Route line, destination pin, and ETA pill render correctly on the dash
      during a real route (`MapRenderer` still gets `routePoints`/`remainingM`
      from `DashEngineController.setNavState`).
- [ ] Distance-to-turn and remaining-distance numbers on the dash's own
      turn-by-turn widget look correct at riding speed — confirms the 1 Hz
      Dart→native cadence (`NavLoop`'s `Timer.periodic`) isn't introducing
      visible lag or jitter versus the old in-process tick.
- [ ] Off-route detection + reroute still fires within a few seconds of
      leaving the route (`NavEngine.progress`'s 60 m threshold) and the new
      route reaches the dash.
- [ ] **Maneuver glyph**: still only `CONTINUE` (`0x0B`) — confirm the dash
      doesn't show a wrong turn arrow. (Known, unchanged limitation: the
      Yandex driving router used now doesn't expose per-step maneuvers at
      all, see `nav/Router.dart`'s doc comment — this was already
      effectively CONTINUE-only in the original app too, since its OSRM
      glyph codes were unverified.)
- [ ] ETA HH:MM shown on the dash matches wall-clock arrival time
      (`NavLoop`'s `etaHHMM` formatting).

## 4. Joystick / physical controls

Original mapping, confirmed on fw 11.63 — re-confirm it still round-trips
through the plugin's `onButton` → `DashEngine.stateStream` (`{'button':
code}`) → whatever Dart-side listener is wired to it:

- [ ] `RIGHT` (`0x09`) zooms in, `LEFT` (`0x0A`) zooms out
      (`DashEngineController.zoomIn`/`zoomOut` via the method channel).
- [ ] Manual pan (if wired) reverts to follow mode after ~8 s idle
      (`MANUAL_IDLE_MS`).
- [ ] No joystick codes are silently swallowed — full hex dump still logs
      unknown TLVs (`DashSession.dispatchIncoming`'s "DASH EVENT" log line).

## 5. Idle wallpaper (Dart picker/crop → native renderer — new split)

- [ ] A picked image renders correctly on the dash when idle (no
      destination) — confirms the Dart-side crop/fit render
      (`DashWallpaperStore._renderToDash`, `image` package) produces a PNG
      the native `DashIdleRenderer` displays without distortion.
- [ ] Crop vs. fit-height vs. fit-width all look right for at least one
      portrait and one landscape source image.
- [ ] A GIF wallpaper animates on the dash (native `DashIdleRenderer.drawGif`
      path — Dart just copies the source file for GIFs, no re-encoding).
- [ ] Switching between wallpaper slots (tap a thumbnail in Settings) updates
      the dash within one idle-mode redraw cycle (`wallpaperRevision` bump).
- [ ] **Known gap, not a regression to chase**: video wallpapers aren't
      pickable from this port's UI (see `models/dash_wallpaper.dart`'s doc
      comment) — don't spend time debugging a missing video option.

## 6. Voice / chime guidance

- [ ] CHIME mode beeps via the native `playChime()` (ToneGenerator) at the
      far (~450 m) and near (~60 m) announce points, and once on arrival.
- [ ] FULL mode speaks via `flutter_tts` at the same points, in the device's
      default TTS voice/locale.
- [ ] Mode toggle (Route screen app bar icon) actually changes behavior
      immediately, and persists across app restart
      (`VoiceManager`/`SharedPreferences`).

## 7. Media / call bridge (native, new plugin code)

Requires notification-listener access granted to OpenDash
(Settings → Apps → Special access → Notification access, or wherever the
OEM buries it — no in-app shortcut was built for this yet).

- [ ] With access granted, a playing media session's title shows on the dash
      (`session.updateNowPlaying`) and as a banner on the in-app Dash screen.
- [ ] Skip next/previous from the Dash-screen banner actually changes the
      track (`MediaInfoProvider.skipNext/skipPrevious`).
- [ ] An incoming call shows the caller name on the dash and as a banner
      in-app; Answer/Hangup buttons work where the OS allows it (API-gated:
      answer needs 26+, hangup needs 28+, both need `ANSWER_PHONE_CALLS`
      granted at runtime).
- [ ] Without notification access granted, media/call forwarding no-ops
      silently — no crash, no stuck "loading" state.

## 8. Power / thermals (screen-off ride)

Same measurement the original TODO never got to close out — still open:

- [ ] `dumpsys batterystats` (or equivalent) over a real ride with the
      screen off, dash connected and navigating.
- [ ] Sustained device temperature during a 30+ minute ride — confirm the
      hardware AVC encoder path is actually selected
      (`DashEncoder.selectHardwareEncoder` — check the chosen codec name in
      logs) and not silently falling back to a software encoder.
- [ ] Foreground service (`DashKeepAliveService`) notification stays posted
      and the wake+Wi-Fi locks hold for the whole ride (no silent service
      death under memory pressure — `START_STICKY` should restart it if so,
      confirm it actually does).
- [ ] GPS keeps updating with the screen off (Android 14+ needs the
      `location` foreground-service type, already declared — confirm the
      rider dot doesn't freeze at the first fix).

## 9. Regression sweep (screens that don't touch the dash protocol)

Lower risk (pure Dart/SQLite, no hardware dependency) but worth a quick pass
since this is a full rewrite:

- [ ] Add/edit/delete: fuel fill-ups, expenses, maintenance intervals, saved
      destinations, vehicles — data survives an app restart (`sqflite`).
- [ ] Expense CSV and "Document" (.doc/HTML) export produce openable files
      and share correctly via the Android share sheet.
- [ ] Maintenance due notification fires once when an item newly crosses
      into "due" and not again until it's serviced and re-crosses.
- [ ] Currency switch changes formatting everywhere (Garage, Expenses) — not
      just where it was last set.

---

## Findings

_Record regressions found during verification here, with firmware version,
what was expected (per the original app's behavior) vs. what happened, and
whether it's a Flutter-port bug or a pre-existing unverified item that's
still unverified._

(none logged yet)
