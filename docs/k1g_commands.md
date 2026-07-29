# K1G command table, recovered from the official companion app

Reference for the Tripper Dash control protocol (`K1G`), extracted from the
official companion app. It exists to answer two questions the
port keeps running into: *is our packet format actually right?* and *what does
the dash accept that we never send?*

Everything here is **static analysis only** — nothing below has been confirmed
against a physical dash. Treat it the way
[`ON_HARDWARE_VERIFICATION.md`](ON_HARDWARE_VERIFICATION.md) treats the port
itself: a hypothesis list, not a spec.

## Provenance

| | |
|---|---|
| APK | companion app 10.1.22 (versionCode 189), `com.royalenfield.reprime` |
| Source | `re_app/jadx_out/sources/bluconnect/hbg.java` |
| Tooling | `jadx` (already unpacked under `re_app/`) |
| Extracted | 233 hex string constants → 77 distinct `type`/`sub` pairs |

The `bluconnect` package is obfuscated (`hbg`, `r2g`, `n6g`…), but not
usefully so: Kotlin's `@SourceDebugExtension` SMAP annotations and reflection
metadata survived into the APK, so original class names, file names and
property names are recoverable in many places. The manufacturer's *own* classes
(`com.royalenfield.reprime.*`) were never renamed at all, which is what makes
the semantic decodes below possible — the constants are anonymous, but the
activity that sends each one is not.

### Reproducing the extraction

```bash
cd re_app/jadx_out
grep -oE 'String [A-Za-z0-9_]+ = "[0-9a-fA-F]*"' sources/bluconnect/hbg.java
# usage sites, which is where the meaning comes from:
grep -rn "hbg\." --include='*.java' sources/com/royalenfield/
```

## Packet format — confirmed

Our format in [`K1GPacket.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/protocol/K1GPacket.kt),
reverse-engineered via better-dash, matches the official app **byte for byte**.
A representative constant:

```
0016  0002  00000000  0201  0005  4B314720  00   <TLV…>
 len  segs   reserved  flags const   "K1G "  seq
```

- `len` — total packet size including the field itself
- `segs` — 1 (header segment) + N TLV segments
- `4B 31 47 20` — literal ASCII `"K1G "`; the protocol's name comes from here
- `seq` — rolling byte, patched at send time (our `patchSeq`)
- TLVs — `(type:1)(sub:1)(len:2 BE)(value:len)`

Two of our commands are identical to their constants, character for character:

| Ours | Theirs | Hex |
|---|---|---|
| `DashCommands.authRequest()` | `hbg.e` | `0016000200000000020100054B314720000804000101` |
| `DashCommands.authSendKey()` prefix | `hbg.d` | `0095000200000000020100054B3147200008000080` |

The `0x0080` length on the auth TLV confirms the RSA ciphertext is 128 bytes
(1024-bit key), which the port had inferred rather than read.

## Conventions

- **`55` = on/true, `AA` = off/false.** Literally `hbg.A = "55"` and
  `hbg.B = "AA"`, passed as the boolean argument throughout. The port already
  assumed this; it is now confirmed.
- Some subs use a wider enum instead: `10`/`20`/`30`/`40` (4-way) and
  `55`/`AA`/`BB`/`CC` (4-way).
- `+runtime` in the table below means the constant is a *prefix* — type, sub
  and length only, with the value concatenated as hex at call time. Example
  from `REForeGroundService`: `"06080001".concat(hex(volume))`.

## Coverage

**The port implements 25 of the 77 `type`/`sub` pairs seen here; 52 are
unimplemented.** Per-pair detail is in the table (`in port` column).

Two caveats on that number, both of which cut against reading it too literally:

1. **Absence here ≠ the app doesn't use it.** This table only covers commands
   stored as *string constants*. Anything the app builds dynamically is
   invisible to it — which is why several TLVs the port *does* send (`05 08`,
   `05 22`, `06 0A`, `06 10`, `0A 02`) do not appear below. Those are route-card
   fields patched at runtime, exactly as our `routeCard()` patches them.
2. The pairs were recovered by slicing constants with the TLV walker described
   above. Constants that are header fragments rather than whole packets can
   mis-slice; the `0x03`/`0x04` families in particular are self-consistent but
   unverified.

## Full table

Grouped by type. `n` is how many constants reference that pair — a rough
proxy for how central it is. `hbg` fields are listed so any row can be traced
back to the APK; at most six are shown.

#### Type `0x03`

| sub | values seen | n | in port | `hbg` fields | note |
|---|---|---|---|---|---|
| `03` | `55`, `AA` | 2 |  | `q0, r0` |  |
| `04` | `55`, `AA` | 2 |  | `s0, t0` |  |
| `05` | `55`, `AA` | 2 |  | `u0, v0` |  |
| `06` | `10`, `20`, `30`, `40` | 4 |  | `A0, B0, y0, z0` |  |
| `08` | `55`, `AA` | 2 |  | `C0, D0` |  |
| `09` | `01`, `02`, `03`, `04`, `05`, `06` | 6 |  | `E0, F0, G0, H0, I0, J0` |  |
| `0A` | `55555555`, `5555555555`, `AAAAAAAA`, `AAAAAAAAAA` +runtime | 5 |  | `K0, L0, M0, N0, O0` |  |
| `0B` | +runtime | 1 |  | `P0` |  |

#### Type `0x04`

| sub | values seen | n | in port | `hbg` fields | note |
|---|---|---|---|---|---|
| `01` | `55`, `AA` | 4 |  | `O, P, Q, R` | Temperature unit (°C / °F) — `TemperatureActivity` |
| `02` | `55`, `AA` | 4 |  | `a0, b0, c0, d0` |  |
| `03` | `10`, `20` | 4 |  | `m0, n0, o0, p0` |  |
| `04` | `10`, `20`, `30`, `40` | 8 |  | `S, T, U, V, W, X` |  |
| `06` | `55`, `AA` | 4 |  | `e0, f0, g0, h0` |  |
| `07` | `01`, `02`, `03`, `04`, `05`, `06` | 12 |  | `C, D, E, F, G, H` |  |
| `08` | `5555555555`, `AA55555555`, `AAAAAAAAAA` +runtime | 4 |  | `i0, j0, k0, l0` |  |
| `09` | `AAAAAAAA` +runtime | 2 |  | `Q0, R0` |  |

#### Type `0x05`

| sub | values seen | n | in port | `hbg` fields | note |
|---|---|---|---|---|---|
| `0B` | +runtime | 1 |  | `S2` |  |
| `17` | `55`, `AA` | 2 | yes | `l1, m1` |  |
| `19` | `55`, `AA` | 2 |  | `i1, j1` |  |
| `1A` | +runtime | 1 |  | `k1` |  |
| `1B` | `10`, `11`, `12`, `13`, `14`, `15`, `16`, `17`, `18`, `19`… | 11 | yes | `A1, B1, C1, D1, E1, F1` |  |
| `21` | `0A`, `14`, `1E`, `32` | 4 | yes | `l2, m2, n2, o2` |  |
| `23` | `55`, `AA`, `BB`, `CC` | 4 |  | `h2, i2, j2, k2` |  |
| `2D` | +runtime | 1 | yes | `q2` |  |
| `2E` | `0A`, `0B`, `0C`, `0D`, `14`, `1E` | 6 | yes | `k, l, m, n, p, q` |  |
| `2F` | `00` | 1 | yes | `r` |  |
| `30` | `00` | 1 | yes | `r` |  |
| `31` | `00` | 1 | yes | `r` |  |
| `32` | `00` | 1 | yes | `r` |  |
| `33` | `00` | 1 | yes | `r` |  |
| `4C` | `10`, `11`, `12`, `13`, `14`, `15`, `16`, `17`, `18`, `19`… | 11 | yes | `n1, o1, p1, q1, r1, s1` |  |
| `4D` | `32` | 1 | yes | `f` | Used in `initialBurst` (value `32` = 50) |
| `55` | +runtime | 1 |  | `T2` |  |
| `56` | `55`, `AA` | 2 | yes | `g, h` |  |
| `57` | `55` | 1 | yes | `P2` |  |
| `58` | `55`, `AA` | 2 |  | `g1, h1` |  |

#### Type `0x06`

| sub | values seen | n | in port | `hbg` fields | note |
|---|---|---|---|---|---|
| `01` | +runtime | 1 | yes | `s` |  |
| `03` | +runtime | 1 | yes | `v` | Media flag (bool) — `REForeGroundService` |
| `04` | +runtime | 1 | yes | `u` | Media volume — value is `volume + 100`, `REForeGroundService` |
| `05` | `55`, `AA` | 4 | yes | `w, x, y, z` |  |
| `0C` | `10`, `20`, `30` | 3 |  | `M2, N2, O2` |  |
| `0D` | `55`, `AA` | 2 |  | `i, j` |  |
| `0E` | `55`, `AA` | 2 |  | `w0, x0` |  |
| `0F` | +runtime | 1 | yes | `t` | Media flag (bool) — `REForeGroundService` |
| `11` | `55` | 1 | yes | `L2` | IDR frame decoded ack |
| `12` | `55` | 1 | yes | `K2` | P-frame decoded ack |
| `13` | +runtime | 1 |  | `U2` |  |
| `14` | +runtime | 1 |  | `V2` |  |
| `15` | +runtime | 1 |  | `W2` |  |
| `16` | +runtime | 1 |  | `X2` |  |
| `17` | +runtime | 1 |  | `Y2` |  |
| `18` | +runtime | 1 |  | `Z2` |  |
| `19` | +runtime | 1 |  | `a3` |  |
| `1A` | +runtime | 1 |  | `b3` |  |
| `1B` | +runtime | 1 |  | `c3` |  |
| `80` | `03`, `04`, `05`, `06`, `07`, `08`, `09`, `0A`, `0B`, `12`… | 19 | yes | `A2, B2, C2, D2, E2, F2` |  |

#### Type `0x08`

| sub | values seen | n | in port | `hbg` fields | note |
|---|---|---|---|---|---|
| `00` | +runtime | 1 | yes | `d` | Auth: RSA ciphertext (0x80 = 128 B) |
| `04` | `01` | 1 | yes | `e` | Auth request |

#### Type `0x09`

| sub | values seen | n | in port | `hbg` fields | note |
|---|---|---|---|---|---|
| `00` | `05`, `06`, `07`, `09`, `0A`, `12`, `22` | 7 | yes | `Q2, b1, c1, d1, e1, f1` | Joystick / button event |
| `01` | `0055`, `00AA`, `00BB`, `00CC`, `0155`, `01AA`, `01BB`, `01CC`, `0255`, `02AA`… | 24 |  | `J1, K1, L1, M1, N1, O1` |  |
| `03` | `0A01`, `1401`, `1E01` | 3 |  | `Y0, Z0, a1` |  |
| `07` | `55`, `AA` | 2 |  | `A3, z3` |  |

#### Type `0x0C`

| sub | values seen | n | in port | `hbg` fields | note |
|---|---|---|---|---|---|
| `01` | +runtime | 1 |  | `W0` |  |
| `05` | +runtime | 1 |  | `X0` |  |
| `14` | `00`, `01`, `02`, `03` | 4 |  | `S0, T0, U0, V0` |  |

#### Type `0x0D`

| sub | values seen | n | in port | `hbg` fields | note |
|---|---|---|---|---|---|
| `02` | `55` | 1 |  | `o` |  |

#### Type `0x0E`

| sub | values seen | n | in port | `hbg` fields | note |
|---|---|---|---|---|---|
| `01` | `00` | 3 |  | `d3, o3, p3` | Sent as one 11-TLV block, see *Composite blocks* |
| `02` | `00` | 3 |  | `e3, o3, q3` |  |
| `03` | `00` | 3 |  | `f3, o3, r3` |  |
| `04` | `00` | 3 |  | `g3, o3, s3` |  |
| `05` | `00` | 3 |  | `h3, o3, t3` |  |
| `06` | `00` | 3 |  | `i3, o3, u3` |  |
| `07` | `00` | 3 |  | `j3, o3, v3` |  |
| `08` | `00` | 3 |  | `k3, o3, w3` |  |
| `09` | `00` | 3 |  | `l3, o3, x3` |  |
| `0A` | `00` | 3 |  | `m3, o3, y3` |  |
| `0B` | `00` | 2 |  | `n3, o3` |  |

## Semantic decodes

Confidence is stated per row because it varies a lot.

| TLV | Meaning | Basis | Confidence |
|---|---|---|---|
| `08 04` = `01` | Auth request | Identical to our working command | Confirmed |
| `08 00` + 128 B | Auth, RSA ciphertext | Identical to our working command | Confirmed |
| `06 11` = `55` | IDR-frame-decoded ack | Identical to our working command | Confirmed |
| `06 12` = `55` | P-frame-decoded ack | Identical to our working command | Confirmed |
| `09 00` | Joystick / button event | Same codes we already handle | Confirmed |
| `04 01` = `55`/`AA` | Temperature unit (°C / °F) | Only TLV sent by `TemperatureActivity` | Likely |
| `06 04` + value | Media volume, sent as `volume + 100` | `REForeGroundService` media callback | Likely |
| `06 08` + value | Media, value from `t9k.y().p0()` | Same callback | Plausible |
| `06 03`, `06 0F` | Media booleans | Same callback, `55`/`AA` args | Plausible |
| `04 xx`, `03 xx` | Cluster settings family | Sent from `ui/settings/activity/*` | Plausible |

### Dash settings are protocol commands

This is the most actionable finding. These activities each push a command to
the cluster:

`TemperatureActivity` · `SpeedDistanceActivity` · `FuelConsumptionActivity` ·
`ClockActivity` / `NewClockActivity` · `LanguageActivity` ·
`NotificationActivity` · `BackgroundActivity` · `InfotainmentActivity`

So **units, clock and dash language are configurable over the same transport we
already speak**. [`spec/settings_screen.md`](../spec/settings_screen.md) has
nothing of the sort — our settings cover pairing, wallpaper and currency only.
Only `04 01` (temperature) is pinned down so far; the rest of the `03`/`04`
families are the obvious place to look next.

### The 1 Hz heartbeat ships captured media state

Decoding `06 04` has an immediate consequence for the port. Our heartbeat
template in [`DashCommands.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/protocol/DashCommands.kt)
carries a media block that `heartbeat()` does not patch:

```
06 08 0001 05      ← captured
06 10 0001 39      ← patched at runtime (temperature, °C + 40)
06 03 0001 55      ← captured, "on"
06 04 0001 A2      ← captured
06 0F 0001 AA      ← captured, "off"
```

`heartbeat()` only rewrites the byte after the `06 10` marker. The other four
retain whatever the original packet capture happened to contain — and they go
out once a second for the entire session.

Read through the official app's formula (`06 04` = `volume + 100`), `0xA2` =
162 → **volume 62**. That the arbitrary captured byte decodes to a plausible
volume is itself decent evidence the formula is right.

This is the same class of bug the route card already documents and works around
— *"the template's captured values must never reach the dash once real guidance
is running… the card repeats at 1 Hz and would stomp the activeNavPacket
numbers every second"* — except here nothing patches them. If the dash renders
a volume bar from `06 04`, it has been showing a stranger's volume level.

Unverified on hardware, and harmless if the dash ignores these fields outside
its media view. Worth a look either way.

## Composite blocks

Two constants carry a whole block of TLVs in one packet rather than a single
command. Both look like state-reset or bulk-query blocks:

**`hbg.o3`** — `segs=0x000C`, eleven TLVs `0E 01` … `0E 0B`, each `len=1`,
value `00`:

```
0043000C00000000020100054B31472000
  0E010001 00  0E020001 00  0E030001 00  0E040001 00
  0E050001 00  0E060001 00  0E070001 00  0E080001 00
  0E090001 00  0E0A0001 00  0E0B0001 00
```

**`hbg.r`** — `segs=0x0006`, five TLVs `05 2F` … `05 33`, each `len=1`,
value `00`:

```
002A000600000000020100054B31472000
  052F0001 00  05300001 00  05310001 00  05320001 00  05330001 00
```

The `0E` family appears *only* in this block form plus individual duplicates —
no other value than `00` was ever observed, which is why it reads as a reset or
a request rather than a setter.

## Video pipeline, for comparison

Their encoder is `sources/bluconnect/r2g.java`, our
[`DashEncoder.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/video/DashEncoder.kt).
RTP goes to `192.168.1.1:5000` (`sources/bluconnect/n6g.java`), matching
`DashSocket.RTP_PORT`.

| | Official app | This port |
|---|---|---|
| Profile | Scans `profileLevels` for `profile == 1` (Baseline) | Hardcoded `AVCProfileBaseline` |
| Level | Device's `profileLevels[0].level`, clamped to `0x4000` if `> 0x20000` | Hardcoded `AVCLevel41` |
| Codec choice | `createEncoderByType()` — OS picks | Explicit hardware-encoder scan |

Two things worth taking from that:

- **Their level handling is more defensive than ours.** We hardcode
  `AVCLevel41`; on a device whose AVC encoder doesn't advertise 4.1,
  `configure()` can throw. Deriving it from `profileLevels` would be strictly
  safer.
- **Our codec selection is better than theirs** and should stay. For a project
  whose entire point is power draw, letting the OS hand back a software encoder
  is the wrong default.

Resolution, bitrate and frame rate live in a builder (`x2g`, with `width`,
`height`, `colorFormat`, `bitRate`, `frameRate`, `iFrameInterval` — names
recovered from Kotlin reflection metadata). **The site that fills it with real
numbers was not located**, so their actual stream parameters remain unknown and
our `526×300 @ 2–4 fps, 200 kbps` is still unconfirmed against the original.

## Open questions

- **Incoming telemetry `0C` / `0F` is still undecoded.** This was the reason to
  go looking in the first place (see the field-mapping note in
  [`DashSession.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/DashSession.kt)),
  and it is *not* answered here. The app's incoming-packet parser was not
  found. Note though that `0C` turns out to be **bidirectional** — the app
  sends `0C 14` with values `00`–`03` — so our comment calling `0C` a
  dash→phone type is at best incomplete.
- `09 01` has 24 distinct two-byte values (`00 55`, `00 AA`, `00 BB`, `00 CC`,
  `01 55`, …) — a 2-D enum, structure unknown.
- `06 13` … `06 1B` are nine consecutive runtime-value subs with no observed
  constant payload.
- Stream parameters (see above).

## Note on stale comments in the port

Two identifiers in our source came from an older decompile and do not refer to
what the comments claim in this build:

- `q3c.e` / `q3c.d` / `q3c.L2` / `q3c.K2` — the `L2`/`K2` names are `hbg`
  *field* names, not methods on a class `q3c`. In build 10.1.22, `q3c` is an
  obfuscated `androidx.collection.IndexBasedArrayIterator`.
- `clk.T()` / `clk.U()` — in this build `clk` is a databinding class for
  `RePoiFragment`, not the packet parser.

Both are name collisions from obfuscation, and both currently send a reader to
the wrong file.
