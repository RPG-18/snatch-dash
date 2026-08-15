package com.opendash.opendash_dash_engine.dash.protocol

import java.io.ByteArrayOutputStream

/**
 * K1G commands ported byte-for-byte from better-dash (tripper_app_like_nav.py),
 * which was reconstructed from reference app behavior + packet captures.
 */
object DashCommands {

    // ── Auth ──────────────────────────────────────────────────────────────
    /** q3c.e — "request auth / send me your RSA public key". */
    fun authRequest() = "0016000200000000020100054b314720000804000101".hexToBytes()

    /** q3c.d — RSA-encrypted (SSID ‖ AES-256 key). Ciphertext must be 128 B. */
    fun authSendKey(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == 128) { "q3c.d expects 128B RSA ciphertext, got ${ciphertext.size}" }
        return "0095000200000000020100054B3147200008000080".hexToBytes() + ciphertext
    }

    // ── Initial burst (sent right after the socket opens) ─────────────────
    // The dash only answers with its RSA pubkey on UDP/2002 after seeing
    // this burst on UDP/2000. Order and content from INITIAL_BURST_HEX.
    fun initialBurst(hostname: String): List<ByteArray> = listOf(
        authRequest(),
        hostnameAnnounce(hostname),
        timeSync(),
        "0016000200000000020100054b314720030557000155".hexToBytes(),
        "0016000200000000020100054b3147200405560001aa".hexToBytes(),
        "0016000200000000020100054b3147200506050001aa".hexToBytes(),
        "0016000200000000020100054b3147200605170001aa".hexToBytes(),
        "001d000200000000020100054b314720080a020008aa55000000000000".hexToBytes(),
        ("0044000a00000000020100054b3147200906080001ff060300015506040001a2060f0001aa" +
         "0601000101054c000113052d00020000051b0001190521000132054d000132").hexToBytes(),
    )

    /**
     * 06 06 — time-of-day sync (hour, minute, second). The dash has no clock source
     * of its own: it shows whatever the phone last fed it, so the official app keeps
     * feeding it. better-dash replays a hardcoded capture time (0x0e 0x33 0x34 =
     * 14:51:52) once, which left the dash clock wrong AND frozen. Build it from the
     * real clock; DashSession re-sends it every 30 s.
     */
    fun timeSync(): ByteArray {
        val cal = java.util.Calendar.getInstance()
        return K1GPacket.build(
            K1GPacket.tlv(
                0x06, 0x06,
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND),
            )
        )
    }

    /** Bluconnect announce — device name shown on the dash's "Connected to X" screen. */
    fun hostnameAnnounce(hostname: String): ByteArray {
        val raw = hostname.toByteArray(Charsets.UTF_8).let {
            if (it.size > 200) it.copyOf(200) else it
        }
        val out = ByteArrayOutputStream()
        out.write("0021000200000000020100054b314720".hexToBytes())
        out.write(byteArrayOf(0x01, 0x06, 0x0B, 0x00, (raw.size + 1).toByte()))
        out.write(raw)
        out.write(0x00)
        val bytes = out.toByteArray()
        bytes[0] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes[1] = (bytes.size and 0xFF).toByte()
        return bytes
    }

    // ── Navigation mode ────────────────────────────────────────────────────
    /** q3c.q — nav context. */
    fun navContext() = "0016000200000000020100054B31472000052E00011E".hexToBytes()

    /** q3c.r — empty favourite lists. */
    fun emptyLists() =
        "002A000600000000020100054B31472000052F0001000530000100053100010005320001000533000100"
            .hexToBytes()

    /** q3c.z2 — start navigation. Send ONCE, after the route card. */
    fun navStart() = "0016000200000000020100054B31472000068000010B".hexToBytes()

    /** q3c.t8 placeholder sent between projection-frame and z2 in the phone's capture. */
    fun navPlaceholder() = K1GPacket.build(K1GPacket.tlv(0x06, 0x0A, 0x00, 0x00))

    // ── Projection control ────────────────────────────────────────────────
    /** q3c.g — projection keep-alive; MUST repeat at the encoder frame rate (4 Hz). */
    fun projectionFrame() = "0016000200000000020100054B314720000556000155".hexToBytes()
    fun projectionOn()    = "0016000200000000020100054B314720000605000155".hexToBytes()
    fun projectionStop()  = "0016000200000000020100054B3147200005560001AA".hexToBytes()
    fun projectionOff()   = "0016000200000000020100054B3147200006050001AA".hexToBytes()

    // ── Frame-decoded acknowledgements ────────────────────────────────────
    /** q3c.L2 — mandatory reply to the dash's 09 06 55 per-IDR "frame decoded" notify. */
    fun frameDecodedIdr() = "0016000200000000020100054B314720000611000155".hexToBytes()
    /** q3c.K2 — reply to 09 04 55. */
    fun frameDecodedP()   = "0016000200000000020100054B314720000612000155".hexToBytes()

    // ── Button / event acknowledgement: echo the code back in 06 80 ───────
    fun buttonAck(code: Byte) =
        K1GPacket.build(K1GPacket.tlv(0x06, 0x80, code.toInt() and 0xFF))

    // Joystick codes seen in the 09 00 event family (clk.D/E/F/c0/d0/u)
    const val BTN_05: Byte = 0x05
    const val BTN_06: Byte = 0x06
    const val BTN_07: Byte = 0x07
    const val BTN_09: Byte = 0x09
    const val BTN_0A: Byte = 0x0A
    const val BTN_22: Byte = 0x22

    // ── 1 Hz status heartbeat (0049, fixed temp) ──────────────────────────
    private val HB_0049 =
        ("0049000b00000000020100054b3147200006080001050610000139060300015506040001a2060f0001aa" +
         "0601000101054c000113052d00020000051b0001190521000132054d000132").hexToBytes()

    /** d.run() heartbeat — on-wire temp byte = °C + 40. */
    fun heartbeat(tempC: Int = 25): ByteArray {
        val pkt = HB_0049.copyOf()
        val marker = byteArrayOf(0x06, 0x10, 0x00, 0x01)
        val i = indexOf(pkt, marker)
        if (i >= 0 && i + 4 < pkt.size) pkt[i + 4] = ((tempC + 40) and 0xFF).toByte()
        return pkt
    }

    // ── Route card (0x007E) ───────────────────────────────────────────────
    // Template captured from the real app: K1G header + TLV(05,01,title+NUL)
    // + a fixed suffix of sub-TLVs. The byte after marker 06 05 00 01 is the
    // projection flag (0x55 on / 0xAA off).
    private val NAV_TEMPLATE =
        ("007e001100000000020100054b31472025050100145461696c6c65206465204d617320647520477200" +
         "050200013c050300013405050002000a05060001300507000130050800043033303305540001300509" +
         "0002004f0546000110050a000155050c000104050b0006303031303030055500012006050001aa060d0001aa")
            .hexToBytes()

    private val navPrefix: ByteArray  // header up to (not incl.) the seq byte
    private val navSuffix: ByteArray  // everything after the title TLV

    init {
        val magic = indexOf(NAV_TEMPLATE, "4b314720".hexToBytes())
        require(magic >= 0) { "K1G marker missing in nav template" }
        val seqOff = magic + 4
        val titleLen = ((NAV_TEMPLATE[seqOff + 3].toInt() and 0xFF) shl 8) or
                        (NAV_TEMPLATE[seqOff + 4].toInt() and 0xFF)
        navPrefix = NAV_TEMPLATE.copyOfRange(0, seqOff)
        navSuffix = NAV_TEMPLATE.copyOfRange(seqOff + 5 + titleLen, NAV_TEMPLATE.size)
    }

    /**
     * Full 0x007E route card. Must be sent BEFORE z2 (sets the destination the
     * dash needs to open its decoder), then re-sent at ~1 Hz while streaming
     * or the dash's destination watchdog tears the decoder down after ~15 s.
     *
     * The template is a capture of a REAL French route, so its nav fields carry
     * that ride's values — total distance 0x004F (79 km-tenths = "7.9 km"),
     * glyph 0x3C, ETA "03:03". Re-sending them unpatched at 1 Hz stomps the live
     * figures from [activeNavPacket]. Pass the live values to overwrite them
     * (field meanings per better-dash: 0502 glyph t3c.g, 0506 unit t3c.j,
     * 0508 ETA HH:MM ASCII, 0509 total t3c.q, 0546 total unit t3c.r).
     */
    fun routeCard(
        title: String,
        projectionOn: Boolean = false,
        maneuver: Int? = null,
        primaryUnit: Int? = null,
        totalDist: Int? = null,
        totalUnit: Int? = null,
        etaHHMM: String? = null,   // 4 ASCII digits, e.g. "1845"
    ): ByteArray {
        val rt = title.toByteArray(Charsets.UTF_8).let {
            if (it.size > 60) it.copyOf(60) else it
        } + 0x00.toByte()

        val out = ByteArrayOutputStream()
        out.write(navPrefix)
        out.write(0x00)                                  // seq, patched at send
        out.write(byteArrayOf(0x05, 0x01))
        out.write((rt.size shr 8) and 0xFF)
        out.write(rt.size and 0xFF)
        out.write(rt)
        out.write(navSuffix)

        val bytes = out.toByteArray()
        // All patched fields live in the suffix (after the title), so search from
        // the end — the title text can never collide with a marker that way.
        fun patch1(t: Int, s: Int, v: Int) {
            val m = indexOf(bytes, byteArrayOf(t.toByte(), s.toByte(), 0x00, 0x01), fromEnd = true)
            if (m >= 0 && m + 4 < bytes.size) bytes[m + 4] = (v and 0xFF).toByte()
        }
        fun patch2(t: Int, s: Int, v: Int) {
            val m = indexOf(bytes, byteArrayOf(t.toByte(), s.toByte(), 0x00, 0x02), fromEnd = true)
            if (m >= 0 && m + 5 < bytes.size) {
                bytes[m + 4] = ((v shr 8) and 0xFF).toByte()
                bytes[m + 5] = (v and 0xFF).toByte()
            }
        }
        patch1(0x06, 0x05, if (projectionOn) 0x55 else 0xAA)
        maneuver?.let { patch1(0x05, 0x02, it) }
        primaryUnit?.let { patch1(0x05, 0x06, it) }
        // The template carries the captured French ride's figures (total 0x004F =
        // "7.9 km", secondary 0x000A). Zero them by DEFAULT so a card sent with no live
        // route shows 0, not a bogus "7.9 km". Live callers pass real values.
        patch2(0x05, 0x09, totalDist ?: 0)
        patch2(0x05, 0x05, 0)                       // stale secondary distance → 0
        totalUnit?.let { patch1(0x05, 0x46, it) }
        if (etaHHMM != null && etaHHMM.length == 4) {
            val m = indexOf(bytes, byteArrayOf(0x05, 0x08, 0x00, 0x04), fromEnd = true)
            if (m >= 0 && m + 8 < bytes.size) {
                for (i in 0 until 4) bytes[m + 4 + i] = etaHHMM[i].code.toByte()
            }
        }
        bytes[0] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes[1] = (bytes.size and 0xFF).toByte()
        return bytes
    }

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
    private const val NAV_HDR = "00000000020100054B31472000"

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
    ): ByteArray {
        fun u16(v: Int) = "%04X".format(v and 0xFFFF)
        fun u8(v: Int) = "%02X".format(v and 0xFF)
        val tlvs = StringBuilder()
        tlvs.append("05020001").append(u8(maneuver))      // primary maneuver
        tlvs.append("05040002").append(u16(primaryDist))  // primary distance
        tlvs.append("05060001").append(u8(primaryUnit))   // primary unit
        tlvs.append("05090002").append(u16(totalDist))    // total distance
        tlvs.append("05460001").append(u8(totalUnit))     // total unit
        tlvs.append("050A000155")                          // decimal separator = '.'
        tlvs.append("06050001").append(if (projectionOn) "55" else "AA") // projection flag
        tlvs.append("060D0001AA")                          // decimal format off

        val segCount = 8 + 1
        val innerHex = "%04X".format(segCount) + NAV_HDR + tlvs.toString()
        val innerBytes = innerHex.length / 2
        val outerLen = innerBytes + 2
        return ("%04X".format(outerLen) + innerHex).hexToBytes()
    }

    // Captured from reference app behavior. These are additive media/call cards and do not
    // alter auth, projection, route-card, ACK, or RTP packet behavior.
    private const val MEDIA_FIELD_MAX = 20

    private fun mediaField(value: String): ByteArray =
        value.take(MEDIA_FIELD_MAX).toByteArray(Charsets.UTF_8)

    /** 05 0D: title, album, and artist as NUL-separated UTF-8 fields. */
    fun nowPlaying(title: String, album: String, artist: String): ByteArray {
        val value = ByteArrayOutputStream().apply {
            write(mediaField(title))
            write(0)
            write(mediaField(album))
            write(0)
            write(mediaField(artist))
        }.toByteArray()
        return K1GPacket.build(K1GPacket.tlv(0x05, 0x0D, value))
    }

    /** 05 22: NUL-terminated caller display name. */
    fun callNotify(callerName: String): ByteArray =
        K1GPacket.build(
            K1GPacket.tlv(0x05, 0x22, mediaField(callerName) + 0x00.toByte()),
        )

    /** Clears a previously repeated 05 22 call card. */
    fun callClear(): ByteArray =
        K1GPacket.build(K1GPacket.tlv(0x05, 0x22, byteArrayOf(0x00)))

    private fun indexOf(haystack: ByteArray, needle: ByteArray, fromEnd: Boolean = false): Int {
        val range = if (fromEnd) (haystack.size - needle.size downTo 0) else (0..haystack.size - needle.size)
        outer@ for (i in range) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
