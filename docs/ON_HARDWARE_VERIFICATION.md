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

- [x] Prefix discovery (`RE_*`) finds the dash and the system Wi-Fi dialog
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

- [x] Dash decoder accepts the stream (the map appears on the round
      display, not stuck on the loading spinner) — confirms SPS normalization
      (`NalProcessor.normalizeSpsForDash`) still matches fw 11.63's whitelist.
- [ ] IDR bundling (SPS+PPS+IDR with Annex-B start codes) still satisfies the
      dash decoder — no periodic decoder resets/black frames.
- [ ] Frame rate holds ~4 fps while moving, drops to ~2 fps when
      stationary/idle (`FrameStreamer.FPS_MOVING`/`FPS_IDLE`) — no
      visible stutter vs. the original app's feel.
- [ ] Encoder auto-recovers after a forced failure (kill/reconnect Wi-Fi
      mid-stream 3×) without requiring app restart (`startStream`'s
      failure-count rebuild path).

## 3. Navigation (Dart ⇄ native split — new in this port)

This is the part most worth extra scrutiny: `NavEngine`/`Router` moved to
Dart (Phase 2), and the native side only receives a 1 Hz `setNavState` push
from `NavLoop` instead of computing nav math itself like the original
`DashViewModel.tick` did in-process.

- [ ] Route line and destination pin render correctly on the dash during a
      real route (`OverlayRenderer` still gets `routePoints`/`remainingM` from
      `DashEngineController.setNavState`). There is no ETA pill on the frame:
      it was never wired to a data source and was removed — remaining distance
      lives on the dash firmware's own widget.
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

- [ ] **`0x13` zooms in, `0x14` zooms out** — these are the primary codes and
      the ones that actually arrive in the field (ride logs 2026-09-09,
      `[joystick] code=0x13`). An earlier version of this item named
      `0x09`/`0x0A`, which is incomplete: see `_btnMapZoomIn`/`_btnMapZoomOut`
      in `lib/state/dash_button_controller.dart`.
- [ ] `0x09`/`0x0A` (RIGHT/LEFT) are media next/previous, and they fall through
      to zoom **only while nothing is playing** (`_isLooseNextButton` /
      `_isLoosePreviousButton`). Test both ways — with media active and without —
      or a swapped branch stays invisible.
- [ ] `0x15` and `0x0B` hit **no branch at all**. The 2026-09-05 ride sent them
      nine and six times; `0x15` shows up in the 2026-09-09 logs too. From the
      saddle that is indistinguishable from a broken control. Dart logs
      `dash button 0x… has no action` for them.
      **Meanings resolved from the official app on 2026-09-22**
      (`docs/k1g_commands.md`, "Buttons `09 00`"): `0x15` is recenter (it also
      forces zoom to `16.0` there), `0x0B` is start-navigation. What remains is
      confirming which physical controls these are, and then wiring them to
      `recenter()` and a navigation start.
- [ ] **The meanings of `0x05`/`0x06`/`0x07` are disputed.** Our mapping came
      from `open-dash` (previous track, answer/reject call); the official app
      uses the same codes for media play/pause and media volume. Press each with
      media playing and with a call ringing, and record what the dash actually
      does — that is the only way to settle it.
- [ ] **Zoom-limit feedback (`06 0C`) — we send none.** The original answers every
      zoom press with `06 0C` carrying `30`/`20`/`10` (normal / at the upper bound /
      at the lower). Check whether firmware 11.63 reacts to that TLV — does it grey
      out its own "+"/"−"? If so, `DashCameraState.stepZoom` should send it: the
      2026-09-05 ride with 110 zoom-out presses into the floor is exactly the case
      where the rider cannot tell a bottomed-out control from a broken one.
- [ ] Manual pan (if wired) reverts to follow mode after ~8 s idle
      (`MANUAL_IDLE_MS`).
- [ ] No joystick codes are silently swallowed — full hex dump still logs
      unknown TLVs (`DashSession.dispatchIncoming`'s "DASH EVENT" log line).

## 5. Voice / chime guidance

- [ ] CHIME mode beeps via the native `playChime()` (ToneGenerator) at the
      far (~450 m) and near (~60 m) announce points, and once on arrival.
- [ ] FULL mode speaks via `flutter_tts` at the same points, in the device's
      default TTS voice/locale.
- [ ] Mode toggle (Route screen app bar icon) actually changes behavior
      immediately, and persists across app restart
      (`VoiceManager`/`SharedPreferences`).

## 6. Media / call bridge (native, new plugin code)

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

Below is what the original sends and the port does not. All four TLVs were
decoded from the decompilation on 2026-09-22 (`docs/k1g_commands.md`, "Media"),
and none of them has been tried on hardware yet:

- [ ] **Field order in `05 0D`.** Play a track whose title clearly differs from
      its album name and see what is drawn where. The original sends
      `album, title, artist`; we send `title, album, artist`. One look settles
      it and nothing else will.
- [ ] **Field length, 19 or 20.** A long title: if the dash takes exactly 19
      characters, our twentieth eats the separator and the fields run together.
- [ ] **`05 17 AA` on music stopping** clears the card from the cluster. Today we
      never clear it — `05 0D` keeps going out at 1 Hz with the last track.
- [ ] **`05 19`** flips the play/pause indicator on the dash.
- [ ] **Album art: `05 58 55` plus `05 40` chunks.** The biggest piece: 75×75
      JPEG quality 50, 1000-byte chunks, housekeeping bytes inside the TLV
      value. Test once sending exists — today `NowPlaying.art` is read and
      never used.

## 7. Power / thermals (screen-off ride)

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

## 8. Regression sweep (screens that don't touch the dash protocol)

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
