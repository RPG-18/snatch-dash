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
- [ ] **Maneuver glyph**: `NavLoop` sends each `Maneuver.dashCode`; confirm
      that the dash shows the matching turn arrow. Unsupported/unknown
      maneuvers use the confirmed neutral straight-ahead glyph `0x09`, not
      the old OpenDash `CONTINUE` value `0x0B`.
- [ ] ETA HH:MM shown on the dash matches wall-clock arrival time
      (`NavLoop`'s `etaHHMM` formatting).

## 4. Joystick / physical controls

Original mapping, confirmed on fw 11.63 — re-confirm it still round-trips
through the plugin's `onButton` → `DashEngine.buttonStream` (a
`DashButtonEvent` on its own Pigeon channel, since 2026-09-30) → whatever
Dart-side listener is wired to it. **Press each one TWICE**: two presses of one
button are equal values, and that is where a repeat gets swallowed.

- [ ] **`0x13` zooms in, `0x14` zooms out** — these are the primary codes and
      the ones that actually arrive in the field (ride logs 2026-09-09,
      `[joystick] code=0x13`). An earlier version of this item named
      `0x09`/`0x0A`, which is incomplete: see `_btnMapZoomIn`/`_btnMapZoomOut`
      in `lib/state/dash_button_controller.dart`.
- [ ] `0x09`/`0x0A` (RIGHT/LEFT) use media next/previous when
      **`nowPlayingTitle != null`, including `''`**; null falls back to zoom.
      This compatibility gate does not use `mediaPlaying` or `mediaActive`
      (`DashButtonController._handle`). Test playing, paused,
      absent media and active media with an empty title.
- [ ] `0x15` and `0x0B` hit **no branch at all**. The 2026-09-05 ride sent them
      nine and six times; `0x15` shows up in the 2026-09-09 logs too. From the
      saddle that is indistinguishable from a broken control. Dart logs
      `dash button 0x… has no action` for them.
      **Meanings resolved from the official app on 2026-09-22**
      (`docs/k1g_commands.md`, "Buttons `09 00`"): `0x15` is recenter (it also
      forces zoom to `16.0` there), `0x0B` is start-navigation. What remains is
      confirming which physical controls these are, and then wiring them to
      `recenter()` and a navigation start.
- [ ] **Recheck corrected `05/06/07` on hardware.** On 2026-10-23 the user
      confirmed up=music volume up, down=volume down, center=pause; the old
      mapping defect is recorded in Findings. Verify the corrected build:
      after incoming `06` answer / active-call `07` hangup precedence,
      `05` play/pause and `06/07` volume± require only `mediaActive`.
      Inactive consumes them without track/zoom action; title/playing do not gate.
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

## 5. Voice guidance

- [ ] In More → Voice guidance, enable Voice announcements and choose the speech
      volume and music policy. Restart the app and confirm those settings were
      restored. Start a route whose first maneuver is more than 300 m away,
      raise media volume, and turn the phone screen off. Expect speech, not a chime;
      restarting the app no longer forces voice on.
- [ ] For one normally approached maneuver, hear exactly three localized
      phrases in order near the 1 Hz thresholds: “In 300 meters …”,
      “In 150 meters …”, then “Now …”. No duplicate within a range and no
      late replay of a skipped farther cue.
- [ ] At the destination, hear “You have arrived at your destination”
      exactly once.
- [ ] Disable Voice announcements, restart the app, and confirm it remains off
      with the selected volume and music policy preserved. Maneuvers, arrival,
      and dash connection loss/recovery must stay silent. Re-enable voice before
      continuing audible checks.

## 6. Media / call bridge (native, new plugin code)

Requires notification-listener access granted to snatch-dash (Android special
access → Notification access). Keep call permissions/API restrictions separate
from notification access.

- [ ] With access granted, a playing session's title appears on the dash and
      in the in-app Dash banner; banner next/previous changes the phone track.
- [ ] An incoming call shows the caller on the dash and in-app; Answer/Hangup
      works where permitted (answer API 26+, hangup API 28+, runtime
      `ANSWER_PHONE_CALLS`). Call actions take precedence over media.
- [ ] Without notification access, media forwarding is a safe no-op, without
      crashes or a stuck loading state; check call permissions independently.

### Media hardware gate — still open

The native event/art/live-volume paths and direct media API are implemented;
unit tests and emulator smoke are **not** firmware 11.63 evidence. Static
original-app facts (10.1.22, reconciled with 10.1.23) and current port behavior:
[`spec/multimedia.md`](../spec/multimedia.md), §8–9. Detailed experiments and
independent compatibility decisions:
[`multimedia plan`, stage 7](../plans/active/2026-10-04-multimedia-support.md#этап-7--hardware-gate-на-прошивке-1163-ride).
These are instructions, not new confirmations.

For every experiment record APK/build SHA, release/profile variant and fixture
flags, phone, firmware **11.63**, notification access and the phone's
[`device baseline`](devices/). Use controlled A/B tracks with distinct
`TITLE_A / ALBUM_A / ARTIST_A` and B fields, known JPEG bytes and playback state.
Capture a separate pcap using [`PACKET_CAPTURE`](../hack/PACKET_CAPTURE.md),
timestamped dash video/observations and phone actions, plus the matching ride
`diag/`. Correlate action times with packets; pcap alone is insufficient.
Use release/profile for cadence, with profile caveats from
[`ride-verification`](../.claude/skills/ride-verification/SKILL.md); debug does
not prove performance. Preserve the baseline **title/album/artist, ICU→ASCII,
20 bytes after transliteration, 1 Hz card repeat, 11-TLV heartbeat**. Label
candidate builds and change only one variable, restoring the baseline after each.

- [ ] **Order.** Compare title/album/artist with album/title/artist at fixed
      width20, ICU encoding and repeat. Record which labels the dash shows
      for A and B, not just the packet order.
- [ ] **Width.** At fixed order/encoding/repeat compare width19/20 with
      18/19/20/21 ASCII-byte fields after transliteration, distinct last
      characters and adjacent fields. Do not assume the twentieth byte eats NUL.
- [ ] **ICU baseline.** Observe transliterated Russian names, expanding
      combinations, Latin-1, unsupported characters and truncation; match bytes
      to readability. The old UTF-8 versus original lossy-char experiment is
      withdrawn; no charset change or Cyrillic-glyph claim follows from this.
- [ ] **Retention.** In a labelled variant disable only periodic `05 0D`;
      send A once, wait **30 s without metadata callbacks**, then B,
      pause/resume and reconnect. Compare with the 1 Hz baseline; an ordinary
      periodic APK cannot prove retention without repeats.
- [ ] **Indicators / reconnect.** Observe PLAYING (`05 19 55`), PAUSED
      (`05 19 AA`), PLAYING→OTHER and PAUSED→null playback. Unknown is
      active=true, playing=null, with active but **no new `05 19`**; reconnect
      must not replay the old known indicator. Record the actual display,
      including with calls/navigation, rather than assuming unknown clears it.
- [ ] **Inactive / stop.** Stop media while STREAMING: state becomes
      false/null with metadata cleared, explicit `05 17 AA` through the writer,
      no old cards/art afterwards. Repeat stop and trigger a late callback:
      no second inactive transition or resurrection. Separately stop after
      transport teardown and reconnect: no stale state/packets, but no final
      inactive delivery is promised on a closed socket. Ordinary reconnect
      without stop must replay the current snapshot. Record whether the dash
      actually clears/resumes its display and handles session replacement.
- [ ] **Art framing / absence.** Run no-art→art→no-art with known 75×75
      quality50 JPEGs: singleton (1…1000 bytes, empty `FF` terminator), multichunk
      (data-bearing final `FF`) and **exact2000** (full final 1000 bytes, no extra
      terminator). Match reassembled bytes and visible art. `05 58 AA` must
      have no art frames for that item; never reproduce the original dropped
      final-block bug as a success. After disconnect no old transfer may leak.
- [ ] **Art supersession.** After A's first chunk but before A finishes,
      accept distinct metadata B: separately B with art and B without art.
      After card B there must be **no chunk A**, including delayed chunks;
      expect card B→present→new B from its first chunk, or card B→absent.
      The dash must show B without hanging/reverting to A. A dedup-suppressed
      callback is not accepted B and must not cancel A.
- [ ] **Live volume.** Independently vary music `05 4C` and call `05 1B`:
      silence `10`, min/max, each step boundary and mute, including mute without
      a controller. Record Android current/min/max, packet level and visible
      scale; allow for the next 1 Hz heartbeat, not an immediate volume event.
      Invalid/unavailable stream readings independently retain fallback
      music=`13`, call=`19`; do not treat a silent stream as fallback.
- [ ] **Independent button probes.** Recheck production `05/06/07` twice each
      with active/paused/OTHER/null playback, no media, empty/null title and
      ringing/active calls. Native toggle pauses PLAYING, resumes PAUSED/OTHER,
      null playback no-ops. Keep `09/0A` and next alias `22` title-based
      track/zoom routing and `13/14` zoom. No paired-code timing debounce.
      Separately, only in a gated fixture probe `03/04` call volume, `08` mute
      and a candidate `mediaActive` gate for `09/0A`: these are not implemented
      or accepted production changes. Separate code→phone action from
      ACK-before-forward evidence. `18` is not a play alias (missed-call count).
- [ ] **Media + video / cadence.** Compare max65535 art against no-art baseline
      with projection and button presses. Measure timestamp cadence **250 ms**,
      receipt→ACK **≤250 ms + measured baseline jitter**, heartbeat gap
      **≤1 s + the same jitter**, and art spacing **≥10 ms**. Compare diag
      `[stream] wakeLate=`, `poolLate=`, `overrun=`, `drainMiss=` and
      `[map] interval=`, `encode=`, `late=` with the device baseline; these
      explain stalls but do not replace pcap latency measurements. Record
      incoming `09 04/06`, continued media packets and
      actual map/media display during transitions. `INFOTAINMENT` is the
      original app's connection enum, not a firmware-mode boundary.

**Independent outcomes (stage 7):** change only order if it improves rendering;
otherwise keep current order. Accept width19 only with evidence, otherwise20;
keep ICU→ASCII unless a new requirement and separate evidence authorize change.
Remove only periodic repeat if retention succeeds, otherwise keep1 Hz. Accept
or reject each marker/playback/art path separately by screen+pcap; broken map,
ignored art or intolerance of interruption rejects that path, **not** a switch
to finishing old art A. Wrong volume or excess gaps rejects that specific
branch, retaining captured fallback; live-volume scale and cadence are separate
decisions. The remaining official mapping/stateful gate requires separate
evidence; it is not implied by the narrow `05/06/07` cutover. Preserve call
precedence and ACK-first. No firmware-mode branch is introduced. Keep every unresolved item open; only
a human with the above evidence updates both checklists and Findings.

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

- **2026-10-23 — old button mapping defect; corrected-build recheck pending.**
  Stationary multimedia test (not a ride), Huawei HBN-LX9, build
  `93e3f9f3c+dirty`; `ride-20261023-180816.log` and
  `/tmp/snatch-media-20261023-180816.pcapng`, capture 18:08:19.661–18:10:48.192.
  All 64 RX buttons were ACKed (`05=7, 06=21, 07=10, 09=17, 0A=2,
  13=2, 14=4, 0B=1`). The old dispatcher mapped `06/07` to skip and `05`
  to previous; the user confirmed the intended physical up/down/center
  actions are music volume up/down/pause. The narrow production correction
  is documented in [§8](../spec/multimedia.md), not hardware-verified here.
  Four `07→05` pairs 39–224 ms apart caused double previous under the old
  mapping; whether these represent one physical press or two is unknown,
  so no timing debounce is inferred. The capture contains 24 complete,
  decodable 75×75 JPEGs, but no screen proof. Firmware version is not
  independently established by this evidence; the firmware 11.63 gate,
  corrected-build button check, display/art/volume and other open items remain open.
