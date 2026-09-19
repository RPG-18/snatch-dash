package com.opendash.opendash_dash_engine.dash.protocol

/**
 * K1G commands ported byte-for-byte from better-dash (tripper_app_like_nav.py),
 * which was reconstructed from reference app behavior + packet captures.
 */
object DashCommands {

    // ── Auth ──────────────────────────────────────────────────────────────
    /** q3c.e — "request auth / send me your RSA public key". */
    fun authRequest() = K1GCodec.encode(DashCommand.AuthRequest)

    /** q3c.d — RSA-encrypted (SSID ‖ AES-256 key). Ciphertext must be 128 B. */
    fun authSendKey(ciphertext: ByteArray) = K1GCodec.encode(DashCommand.AuthSendKey(ciphertext))

    /**
     * 06 06 — time-of-day sync (hour, minute, second). The dash has no clock source
     * of its own: it shows whatever the phone last fed it, so the official app keeps
     * feeding it. better-dash replays a hardcoded capture time (0x0e 0x33 0x34 =
     * 14:51:52) once, which left the dash clock wrong AND frozen. Build it from the
     * real clock; DashSession re-sends it every 30 s.
     */
    fun timeSync(): ByteArray {
        val cal = java.util.Calendar.getInstance()
        return K1GCodec.encode(
            DashCommand.TimeSync(
                hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                minute = cal.get(java.util.Calendar.MINUTE),
                second = cal.get(java.util.Calendar.SECOND),
            ),
        )
    }

    /** Bluconnect announce — device name shown on the dash's "Connected to X" screen. */
    fun hostnameAnnounce(hostname: String) = K1GCodec.encode(DashCommand.HostnameAnnounce(hostname))

    // ── Navigation mode ────────────────────────────────────────────────────
    /** q3c.q — nav context. */
    fun navContext() = K1GCodec.encode(DashCommand.NavContext)

    /** q3c.r — empty favourite lists. */
    fun emptyLists() = K1GCodec.encode(DashCommand.EmptyLists)

    /** q3c.z2 — start navigation. Send ONCE, after the route card. */
    fun navStart() = K1GCodec.encode(DashCommand.NavStart)

    /** q3c.t8 placeholder sent between projection-frame and z2 in the phone's capture. */
    fun navPlaceholder() = K1GCodec.encode(DashCommand.NavPlaceholder)

    // ── Projection control ────────────────────────────────────────────────
    /** q3c.g — projection keep-alive; MUST repeat at the encoder frame rate (4 Hz). */
    fun projectionFrame() = K1GCodec.encode(DashCommand.ProjectionFrame)
    fun projectionOn()    = K1GCodec.encode(DashCommand.ProjectionOn)
    fun projectionStop()  = K1GCodec.encode(DashCommand.ProjectionStop)
    fun projectionOff()   = K1GCodec.encode(DashCommand.ProjectionOff)

    // ── Frame-decoded acknowledgements ────────────────────────────────────
    /** q3c.L2 — mandatory reply to the dash's 09 06 55 per-IDR "frame decoded" notify. */
    fun frameDecodedIdr() = K1GCodec.encode(DashCommand.DecoderOpenedAck(keyFrame = true))
    /** q3c.K2 — reply to 09 04 55. */
    fun frameDecodedP()   = K1GCodec.encode(DashCommand.DecoderOpenedAck(keyFrame = false))

    // ── Button / event acknowledgement: echo the code back in 06 80 ───────
    fun buttonAck(code: Byte) = K1GCodec.encode(DashCommand.ButtonAck(code.toInt() and 0xFF))

    // Joystick codes seen in the 09 00 event family (clk.D/E/F/c0/d0/u)
    const val BTN_05: Byte = 0x05
    const val BTN_06: Byte = 0x06
    const val BTN_07: Byte = 0x07
    const val BTN_09: Byte = 0x09
    const val BTN_0A: Byte = 0x0A
    const val BTN_22: Byte = 0x22

    // ── 1 Hz status heartbeat (0049, fixed temp) ──────────────────────────
    /** d.run() heartbeat — on-wire temp byte = °C + 40. */
    fun heartbeat(tempC: Int = 25) = K1GCodec.encode(DashCommand.Heartbeat(tempC))

    /**
     * Full 0x007E route card. Must be sent BEFORE z2 (sets the destination the
     * dash needs to open its decoder), then re-sent at ~1 Hz while streaming
     * or the dash's destination watchdog tears the decoder down after ~15 s.
     *
     * The card is built field by field in [K1GCodec]; the fields nobody has explained keep
     * the captured values, under `CAPTURED_*` there. Two of them were deliberately NOT kept:
     * the capture's distances (`05 05`, `05 09`) belong to a real French route, and a card
     * re-sent at 1 Hz would keep asserting them over the live figures from [activeNavPacket],
     * so they default to zero. The optional parameters below overwrite the rest (field
     * meanings per better-dash: 0502 glyph t3c.g, 0506 unit t3c.j, 0508 ETA HH:MM ASCII,
     * 0509 total t3c.q, 0546 total unit t3c.r).
     *
     * @param etaHHMM four ASCII digits, e.g. "1845". Anything else is ignored and the
     *   captured "0303" goes out instead — the dash reads the field positionally.
     */
    fun routeCard(
        title: String,
        projectionOn: Boolean = false,
        maneuver: Int? = null,
        primaryUnit: Int? = null,
        totalDist: Int? = null,
        totalUnit: Int? = null,
        etaHHMM: String? = null,   // 4 ASCII digits, e.g. "1845"
    ): ByteArray = K1GCodec.encode(
        DashCommand.RouteCard(
            title = title,
            projectionOn = projectionOn,
            maneuver = maneuver,
            primaryUnit = primaryUnit,
            totalDist = totalDist,
            totalUnit = totalUnit,
            etaHHMM = etaHHMM,
        ),
    )

    // ── Active navigation info (0x007E-family, ~1 Hz while guiding) ───────
    // Ported from better-dash build_active_nav_packet. Drives the dash's
    // instruction bubble: primary maneuver glyph + distance-to-turn + total.
    //
    // Full glyph table for the `maneuver` byte below (`05 02` field), swept
    // and hardware-confirmed byte-by-byte against this port's physical dash
    // — see `spec/glyph.md` for the raw sweep notes this was transcribed
    // from; that file is the source of truth, this object mirrors it into
    // named constants for use from code. Range 0x5B..0xFF showed no glyph
    // at all during the sweep — likely reserved/unused.
    //
    //   0x00  destination, straight ahead (final stretch)
    //   0x01  destination, on the left (final stretch)
    //   0x02  destination, on the right (final stretch)
    //   0x03  merge — left road blinks, 90°
    //   0x04  merge — right road blinks, 90°
    //   0x05  fork into two parallel roads — right arrow blinks
    //   0x06  fork into two parallel roads — left arrow blinks
    //   0x07  exit onto a parallel road, right, 90°
    //   0x08  exit onto a parallel road, left, 90°
    //   0x09  straight ahead                                    [NAV_MANEUVER_STRAIGHT]
    //   0x0A  roundabout, clockwise, no exit number              [ROUNDABOUT_CW_BASE]
    //   0x0B..0x13  roundabout, clockwise, exit 1..9 (base + exit number)
    //   0x14  turn left                                          [NAV_MANEUVER_TURN_LEFT]
    //   0x15  turn right                                         [NAV_MANEUVER_TURN_RIGHT]
    //   0x16  sharp left
    //   0x17  sharp right
    //   0x18  slight left
    //   0x19  slight right
    //   0x1A  U-turn, clockwise (→ right)
    //   0x1B  merge — both roads blink red
    //   0x1C  compass searching for a heading (re-route in progress)
    //   0x1D  merge — left road blinks (no angle given)
    //   0x1E  merge — right road blinks
    //   0x1F  merge — left road blinks, smooth line
    //   0x20  merge — right road blinks, smooth line
    //   0x21  merge — left road blinks, 90° (distinct from 0x03)
    //   0x22  merge — right road blinks, 90° (distinct from 0x04)
    //   0x23  exit onto the main road, left
    //   0x24  exit onto the main road, right
    //   0x25  exit onto the main road, left, 60° between roads
    //   0x26  exit onto the main road, right, 60° between roads
    //   0x27  circular exit, right
    //   0x28  circular exit, left
    //   0x29  exit right, 60°
    //   0x2A  exit left, 60°
    //   0x2B  three lanes — leftmost blinks (keep left)
    //   0x2C  three lanes — rightmost (3rd) blinks (keep right)
    //   0x2D  exit onto a parallel road, right (no angle given)
    //   0x2E  exit onto a parallel road, left (no angle given)
    //   0x2F  exit onto a parallel road, right, 90° (distinct from 0x07)
    //   0x30  exit onto a parallel road, left, 90° (distinct from 0x08)
    //   0x31  roundabout, counterclockwise, no exit number        [ROUNDABOUT_CCW_BASE]
    //   0x32..0x3A  roundabout, counterclockwise, exit 1..9 (base + exit number)
    //   0x3B  straight ahead (second, distinct byte — no known difference from 0x09)
    //   0x3C  map marker (rendered for DEPART in a captured real route card)
    //   0x3D  U-turn, counterclockwise (→ left)
    //   0x3E  ferry crossing
    //   0x3F  train (railway crossing, or train-ferry boarding)
    //   0x40  map marker + 3 dots (walking segment?)
    //   0x41  empty bubble
    //   0x42  wifi symbol with a left arrow (mobile-data indicator?)
    //   0x43  empty bubble
    //   0x44  empty bubble (table position matches a "low battery" notion —
    //         may need real battery telemetry alongside the byte to render)
    //   0x46..0x4F  roundabout, clockwise, exit 10..19 (0x46 + (exit-10))  [ROUNDABOUT_CW_EXIT10_BASE]
    //   0x50..0x59  roundabout, counterclockwise, exit 10..19 (0x50 + (exit-10)) [ROUNDABOUT_CCW_EXIT10_BASE]
    //
    // 0x0B specifically ("clockwise, exit 1") directly contradicts the value
    // inherited from the original open-dash project, which claimed 0x0B was
    // hardware-verified as CONTINUE (a neutral straight-ahead arrow) — that
    // claim is now known-wrong on this dash/firmware. 0x09 is the real
    // neutral glyph, confirmed above.
    const val NAV_MANEUVER_STRAIGHT = 0x09
    const val NAV_MANEUVER_TURN_LEFT = 0x14
    const val NAV_MANEUVER_TURN_RIGHT = 0x15
    const val ROUNDABOUT_CW_BASE = 0x0A         // + exit number, 1..9 (0x0B..0x13)
    const val ROUNDABOUT_CW_EXIT10_BASE = 0x46  // + (exit number - 10), 10..19 (0x46..0x4F)
    const val ROUNDABOUT_CCW_BASE = 0x31        // + exit number, 1..9 (0x32..0x3A) — UNCONFIRMED, assumed by symmetry
    const val ROUNDABOUT_CCW_EXIT10_BASE = 0x50 // + (exit number - 10), 10..19 (0x50..0x59) — UNCONFIRMED
    const val NAV_UNIT_KM_TENTHS = 0x10   // distance field = km × 10
    const val NAV_UNIT_METERS    = 0x30

    /**
     * @param maneuver  dash glyph code — see the full table above `NAV_MANEUVER_STRAIGHT`.
     *   `lib/nav/route.dart`'s `Maneuver.dashCode` is what actually picks
     *   this value on the Dart side. Hardware-confirmed against this port's
     *   physical dash: the whole clockwise-roundabout row (`0x0A`..`0x13`,
     *   `0x46`..`0x4F`) and the plain straight/left/right/sharp/slight
     *   turns (`0x09`, `0x14`..`0x19`) — see `spec/glyph.md` for the full
     *   sweep. The counterclockwise roundabout row and everything else in
     *   the table above it is still unconfirmed on this dash; use the Dash
     *   screen's debug glyph probe (`ManeuverGlyphProbe`) to verify before
     *   trusting one on a ride.
     * @param primaryDistM  distance to next turn (metres if [primaryUnit]=METERS,
     *                      or km×10 if KM_TENTHS)
     * @param totalDistM    remaining distance, same unit convention via [totalUnit]
     */
    fun activeNavPacket(
        maneuver: Int = NAV_MANEUVER_STRAIGHT,
        primaryDist: Int = 500,
        primaryUnit: Int = NAV_UNIT_METERS,
        totalDist: Int = 500,
        totalUnit: Int = NAV_UNIT_METERS,
        projectionOn: Boolean = true,
    ): ByteArray = K1GCodec.encode(
        DashCommand.ActiveNav(
            maneuver = maneuver,
            primaryDist = primaryDist,
            primaryUnit = primaryUnit,
            totalDist = totalDist,
            totalUnit = totalUnit,
            projectionOn = projectionOn,
        ),
    )

    /** 05 0D: title, album, and artist as NUL-separated UTF-8 fields. */
    fun nowPlaying(title: String, album: String, artist: String) =
        K1GCodec.encode(DashCommand.NowPlaying(title, album, artist))

    /** 05 22: NUL-terminated caller display name. */
    fun callNotify(callerName: String) = K1GCodec.encode(DashCommand.CallNotify(callerName))

    /** Clears a previously repeated 05 22 call card. */
    fun callClear() = K1GCodec.encode(DashCommand.CallClear)
}
