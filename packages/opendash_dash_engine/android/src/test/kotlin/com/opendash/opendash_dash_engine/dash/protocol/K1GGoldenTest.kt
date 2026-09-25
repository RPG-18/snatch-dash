package com.opendash.opendash_dash_engine.dash.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Golden test for every byte this app puts on the K1G control plane.
 *
 * The protocol has no specification. Everything in [DashCommands] came out of packet
 * captures and a decompiled app, so "correct" here means "identical to what the dash was
 * observed to accept" — there is nothing else to check against. That makes this file a
 * ratchet rather than a check of logic: it does not assert that the packets are sensible,
 * it asserts that they have not moved.
 *
 * Every expectation is written out as a literal, transcribed from the capture the command
 * came from — never by calling the function and recording what it returned. A test that
 * records today's output agrees with any future output too, which is the one failure mode
 * this file exists to avoid (network-refactoring.md, этап 1 задача 1).
 *
 * When one of these fails the question is not "is the test stale" — it is "which bytes
 * moved, and does the dash still accept them". That answer needs hardware, so §4 of the
 * plan says to stop and show the byte diff to a human rather than update the expectation.
 */
class K1GGoldenTest {

    // ── Fixed packets: every byte a literal ───────────────────────────────
    //
    // These take no arguments, so the whole packet is the expectation. Header, left to
    // right: outer_len, seg_count, four zero bytes, flags 0201, const 0005, magic "K1G "
    // (4b314720), seq 00 (patched at send time), then TLVs.

    @Test
    fun `auth request is 08 04 with value 01`() {
        assertEquals(
            "0016000200000000020100054b314720000804000101",
            authRequest().toHex(),
        )
    }

    @Test
    fun `nav context is 05 2E with value 1E`() {
        assertEquals(
            "0016000200000000020100054b31472000052e00011e",
            navContext().toHex(),
        )
    }

    @Test
    fun `empty lists is five one-byte TLVs, 05 2F through 05 33`() {
        assertEquals(
            "002a000600000000020100054b31472000" +
                "052f000100" + "0530000100" + "0531000100" + "0532000100" + "0533000100",
            emptyLists().toHex(),
        )
    }

    @Test
    fun `nav start is 06 80 with value 0B`() {
        assertEquals(
            "0016000200000000020100054b31472000068000010b",
            navStart().toHex(),
        )
        // Same TLV as a button ack for code 0x0B — 06 80 is the dash's generic event slot,
        // not a button-specific one. Stated because a reader who assumes otherwise will
        // misread the one packet инвариант 5 says must be sent exactly once per session.
        assertEquals(buttonAck(0x0B).toHex(), navStart().toHex())
    }

    @Test
    fun `nav placeholder is 06 0A with two zero bytes`() {
        assertEquals(
            "0017000200000000020100054b31472000060a00020000",
            navPlaceholder().toHex(),
        )
    }

    @Test
    fun `the four projection commands differ only in TLV sub and the on-off byte`() {
        // 55 = on, AA = off, and each of the two subs has both forms: 05 56 is the frame
        // keep-alive, 06 05 is the projection switch.
        assertEquals(
            "0016000200000000020100054b314720000556000155",
            projectionFrame().toHex(),
        )
        assertEquals(
            "0016000200000000020100054b3147200005560001aa",
            projectionStop().toHex(),
        )
        assertEquals(
            "0016000200000000020100054b314720000605000155",
            projectionOn().toHex(),
        )
        assertEquals(
            "0016000200000000020100054b3147200006050001aa",
            projectionOff().toHex(),
        )
    }

    @Test
    fun `frame-decoded acks are 06 11 for IDR and 06 12 for P`() {
        // Mandatory replies to the dash's 09 06 55 / 09 04 55 notifies (инвариант 7).
        assertEquals(
            "0016000200000000020100054b314720000611000155",
            frameDecodedIdr().toHex(),
        )
        assertEquals(
            "0016000200000000020100054b314720000612000155",
            frameDecodedP().toHex(),
        )
    }

    @Test
    fun `call clear is an 05 22 card holding a single NUL`() {
        assertEquals(
            "0016000200000000020100054b314720000522000100",
            callClear().toHex(),
        )
    }

    // ── Button acks ───────────────────────────────────────────────────────

    @Test
    fun `button ack echoes the code back in 06 80`() {
        assertEquals(
            "0016000200000000020100054b314720000680000122",
            buttonAck(DashGlyphs.BTN_22).toHex(),
        )
        assertEquals(
            "0016000200000000020100054b314720000680000105",
            buttonAck(DashGlyphs.BTN_05).toHex(),
        )
    }

    @Test
    fun `button codes are the ones seen in the 09 00 event family`() {
        assertEquals(
            listOf<Byte>(0x05, 0x06, 0x07, 0x09, 0x0A, 0x22),
            listOf(
                DashGlyphs.BTN_05, DashGlyphs.BTN_06, DashGlyphs.BTN_07,
                DashGlyphs.BTN_09, DashGlyphs.BTN_0A, DashGlyphs.BTN_22,
            ),
        )
    }

    // ── Auth key packet ───────────────────────────────────────────────────

    @Test
    fun `auth send key is a 21-byte header plus exactly the ciphertext`() {
        val ciphertext = ByteArray(128) { (it and 0xFF).toByte() }

        val pkt = authSendKey(ciphertext)

        assertEquals(149, pkt.size, "0x95 = 21 header bytes + 128 of RSA output")
        assertEquals(
            "0095000200000000020100054b3147200008000080",
            pkt.copyOfRange(0, 21).toHex(),
            "outer_len 0095, TLV 08 00 with a declared length of 0x0080",
        )
        assertContentEquals(ciphertext, pkt.copyOfRange(21, pkt.size))
    }

    @Test
    fun `auth send key refuses anything but 128 bytes`() {
        // RSA-1024 output is fixed width. A short array would produce a packet whose
        // outer_len disagrees with its contents and the dash would drop the session without
        // saying why, so this fails at the call site instead.
        for (size in listOf(0, 127, 129, 256)) {
            assertFailsWith<IllegalArgumentException>("$size bytes must be rejected") {
                authSendKey(ByteArray(size))
            }
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────
    //
    // HB_0049 is a captured status frame. Its only live field is the temperature byte after
    // marker 06 10 00 01, encoded as °C + 40.

    @Test
    fun `heartbeat at 25C carries 41 after marker 06 10 00 01`() {
        assertEquals(
            "0049000b00000000020100054b314720" +
                "0006080001050610000141060300015506040001a2060f0001aa" +
                "0601000101054c000113052d00020000051b0001190521000132054d000132",
            heartbeat(25).toHex(),
        )
    }

    @Test
    fun `heartbeat is 25C by default — the value DashSession actually sends`() {
        // Against `DashCommand.Heartbeat()` itself, not against a default re-declared in
        // this file: `DashSession.heartbeat()` calls the no-argument constructor, so the
        // sealed type's own default is what goes on the wire. A helper with its own copy of
        // `= 25` turns this into `25 == 25` and lets the production default be changed with
        // all forty goldens still green. Review, 2026-09-22.
        assertEquals(heartbeat(25).toHex(), K1GCodec.encode(DashCommand.Heartbeat()).toHex())
    }

    @Test
    fun `the temperature offset puts -40C at 00 and 215C at FF`() {
        assertEquals(0x00, tempByteOf(heartbeat(-40)))
        assertEquals(0xFF, tempByteOf(heartbeat(215)))
        // The ends of the encodable range. Past them the byte wraps rather than clamping.
        // Nothing goes there today — DashSession sends the default — and this states what
        // would happen if a real sensor ever fed it.
        assertEquals(0xFF, tempByteOf(heartbeat(-41)))
        assertEquals(0x00, tempByteOf(heartbeat(216)))
    }

    /**
     * The byte after marker `06 10 00 01`, which is where the temperature lives.
     *
     * Searched over bytes rather than over the hex rendering: a hex search can match at an
     * odd character offset — the tail of one byte joined to the head of the next — and
     * `at / 2` would then round down to a byte that is not the marker at all.
     */
    private fun tempByteOf(packet: ByteArray): Int {
        val marker = byteArrayOf(0x06, 0x10, 0x00, 0x01)
        val at = (0..packet.size - marker.size).firstOrNull { i ->
            marker.indices.all { packet[i + it] == marker[it] }
        }
        assertNotNull(at, "marker 06 10 00 01 is gone from the heartbeat template")
        return packet[at + 4].toInt() and 0xFF
    }

    // ── Hostname announce ─────────────────────────────────────────────────

    @Test
    fun `hostname announce wraps the name in 06 0B with a trailing NUL`() {
        assertEquals(
            "001e000200000000020100054b31472001" + "060b0009" + "4f70656e44617368" + "00",
            hostnameAnnounce("OpenDash").toHex(),
            "TLV length 0x0009 is the 8 name bytes plus the NUL",
        )
    }

    @Test
    fun `hostname announce truncates at 200 bytes and still terminates`() {
        val pkt = hostnameAnnounce("A".repeat(250))

        assertEquals(222, pkt.size, "16 header + 1 seq + 4 TLV header + 200 + NUL")
        assertEquals("00de000200000000020100054b31472001060b00c9", pkt.copyOfRange(0, 21).toHex())
        assertContentEquals(ByteArray(200) { 0x41 }, pkt.copyOfRange(21, 221))
        assertEquals(0x00, pkt[221].toInt())
    }

    @Test
    fun `hostname truncation counts bytes, so a multibyte name is cut mid-character`() {
        // Documented, not endorsed. The cut is copyOf(200) over UTF-8 output, so 67 arrows
        // (201 bytes) lose the last one's trailing byte and leave a dangling e2 86 before
        // the NUL. Nothing sends a name like this — the announce carries "OpenDash" — and a
        // future fix should make this test fail rather than change behaviour in silence.
        val pkt = hostnameAnnounce("→".repeat(67))

        assertEquals(222, pkt.size)
        assertEquals("e28600", pkt.copyOfRange(219, 222).toHex(), "half a character, then the NUL")
    }

    // ── Time sync ─────────────────────────────────────────────────────────

    @Test
    fun `time sync is 06 06 with three bytes of wall clock`() {
        val pkt = timeSync()

        assertEquals(
            "0018000200000000020100054b3147200006060003",
            pkt.copyOfRange(0, 21).toHex(),
            "header and TLV are fixed; only the three time bytes vary",
        )
        assertEquals(24, pkt.size)
        assertTrue(pkt[21].toInt() in 0..23, "hour")
        assertTrue(pkt[22].toInt() in 0..59, "minute")
        assertTrue(pkt[23].toInt() in 0..60, "second, leap second included")
    }

    // ── Initial burst ─────────────────────────────────────────────────────

    /**
     * The burst as it goes on the wire. The time step takes a fixed clock rather than the
     * real one: a script is data, and data that reads the clock cannot be compared with a
     * capture. The two tests below still cover the packet built from it.
     */
    private fun burstBytes(): List<ByteArray> =
        Scripts.initialBurst("OpenDash", DashCommand.TimeSync(hour = 1, minute = 2, second = 3))
            .map { K1GCodec.encode(it.cmd) }

    @Test
    fun `the initial burst is nine packets in a fixed order`() {
        val burst = burstBytes()

        assertEquals(9, burst.size)
        assertEquals(authRequest().toHex(), burst[0].toHex(), "#0 auth request")
        assertEquals(
            hostnameAnnounce("OpenDash").toHex(),
            burst[1].toHex(),
            "#1 hostname announce",
        )
        // #2 is timeSync, compared without its three clock bytes — they change every run.
        assertEquals(
            "0018000200000000020100054b3147200006060003",
            burst[2].copyOfRange(0, 21).toHex(),
            "#2 time sync",
        )
        assertEquals(24, burst[2].size)
        assertEquals(
            listOf(
                "0016000200000000020100054b314720030557000155",
                "0016000200000000020100054b3147200405560001aa",
                "0016000200000000020100054b3147200506050001aa",
                "0016000200000000020100054b3147200605170001aa",
                "001d000200000000020100054b314720080a020008aa55000000000000",
                "0044000a00000000020100054b3147200906080001ff060300015506040001a2060f0001aa" +
                    "0601000101054c000113052d00020000051b0001190521000132054d000132",
            ),
            burst.drop(3).map { it.toHex() },
            "#3..#8 are replayed verbatim from the capture",
        )
    }

    @Test
    fun `the burst carries the capture's own seq bytes, which TxSequencer overwrites`() {
        // Packets #3..#8 were captured mid-session, so byte 16 holds 03, 04, 05, 06, 08, 09.
        // Every one is patched at send time (инвариант 2), so these values never reach the
        // wire. Pinned because a reader who trusts them concludes the burst numbers itself.
        val burst = burstBytes()

        assertEquals(
            listOf(0x03, 0x04, 0x05, 0x06, 0x08, 0x09),
            burst.drop(3).map { it[16].toInt() and 0xFF },
        )
        assertEquals(listOf(0x00, 0x01, 0x00), burst.take(3).map { it[16].toInt() and 0xFF })
    }

    // ── Route card ────────────────────────────────────────────────────────
    //
    // NAV_TEMPLATE is a capture of a real French route. routeCard swaps in a new title and
    // patches a handful of fields inside the fixed suffix that follows it.
    //
    // Field offsets in the DEFAULT card (title 19 chars), from the template's own TLV walk:
    //   45  05 02 maneuver      50  05 03            55  05 05 secondary distance (2 B)
    //   61  05 06 primary unit  66  05 07            71  05 08 ETA, 4 ASCII digits
    //   79  05 54               84  05 09 total distance (2 B)   90  05 46 total unit
    //   95  05 0a              100  05 0c           105  05 0b (6 B)   115  05 55
    //  120  06 05 projection   125  06 0d
    // A different title shifts all of them, which is why the odd-title tests below state
    // their own offsets rather than reusing these.

    /** The template's own title — 19 characters, as captured. */
    private val capturedTitle = "Taille de Mas du Gr"

    @Test
    fun `the default card is the captured template with seq and both distances zeroed`() {
        assertEquals(
            "007e001100000000020100054b31472000" +
                "05010014" + "5461696c6c65206465204d6173206475204772" + "00" +
                "050200013c" + "0503000134" + "050500020000" + "0506000130" + "0507000130" +
                "0508000430333033" + "0554000130" + "050900020000" + "0546000110" +
                "050a000155" + "050c000104" + "050b0006303031303030" + "0555000120" +
                "06050001aa" + "060d0001aa",
            routeCard(capturedTitle, projectionOn = false).toHex(),
            "differs from NAV_TEMPLATE only at seq (25 → 00), 05 05 (000a → 0000) " +
                "and 05 09 (004f → 0000)",
        )
    }

    @Test
    fun `the default card zeroes the captured ride's distances rather than showing them`() {
        // The template says 7.9 km remaining and 1.0 km to the turn, from someone else's
        // ride in France. Re-sending that unpatched at 1 Hz would put believable, wrong
        // numbers in front of the rider — worse than a zero, which reads as "no route".
        val card = routeCard(capturedTitle)

        assertEquals(0x0000, u16At(card, 55), "05 05 secondary distance")
        assertEquals(0x0000, u16At(card, 84), "05 09 total distance")
    }

    @Test
    fun `projection on flips the 06 05 byte and nothing else`() {
        val off = routeCard(capturedTitle, projectionOn = false)
        val on = routeCard(capturedTitle, projectionOn = true)

        assertEquals(off.size, on.size)
        val differing = off.indices.filter { off[it] != on[it] }
        assertEquals(listOf(120), differing, "only the projection flag may move")
        assertEquals(0xAA, off[120].toInt() and 0xFF)
        assertEquals(0x55, on[120].toInt() and 0xFF)
    }

    @Test
    fun `every live field lands at its own offset in the suffix`() {
        val card = routeCard(
            title = capturedTitle,
            projectionOn = true,
            maneuver = DashGlyphs.NAV_MANEUVER_TURN_RIGHT,
            // Both units are deliberately the OPPOSITE of what the template carries
            // (05 06 is 0x30 metres, 05 46 is 0x10 km-tenths). Passing the matching value
            // would assert a byte that was already there and hold nothing down: dropping
            // both patch1 calls from routeCard would still leave this test green.
            primaryUnit = DashGlyphs.NAV_UNIT_KM_TENTHS,
            totalDist = 0x1234,
            totalUnit = DashGlyphs.NAV_UNIT_METERS,
            etaHHMM = "1845",
        )

        // Offsets, not marker searches. Searching for the marker again would re-run the
        // production code's own logic and keep passing even if two patches collided on one
        // field; a fixed offset does not.
        assertEquals(0x15, card[45].toInt() and 0xFF, "05 02 maneuver, template had 0x3c")
        assertEquals(0x10, card[61].toInt() and 0xFF, "05 06 primary unit, template had 0x30")
        assertEquals("31383435", card.copyOfRange(71, 75).toHex(), "05 08 ETA as ASCII")
        assertEquals(0x1234, u16At(card, 84), "05 09 total distance")
        assertEquals(0x30, card[90].toInt() and 0xFF, "05 46 total unit, template had 0x10")
        assertEquals(0x55, card[120].toInt() and 0xFF, "06 05 projection flag")

        // Fields nobody passed keep the template's values.
        assertEquals(0x34, card[50].toInt() and 0xFF, "05 03 untouched")
        assertEquals(0x30, card[66].toInt() and 0xFF, "05 07 untouched")
        assertEquals(0x0000, u16At(card, 55), "05 05 always zeroed, never a parameter")
    }

    @Test
    fun `an ETA that is not four characters is ignored, leaving the template's`() {
        for (bad in listOf("", "184", "18455", "18:45")) {
            val card = routeCard(capturedTitle, etaHHMM = bad)
            assertEquals(
                "30333033",
                card.copyOfRange(71, 75).toHex(),
                "\"$bad\" must not reach a fixed four-byte ASCII field",
            )
        }
    }

    @Test
    fun `a title longer than 60 bytes is truncated and still NUL-terminated`() {
        val card = routeCard("B".repeat(100))

        assertEquals("0501003d", card.copyOfRange(17, 21).toHex(), "TLV length 0x3D = 60 + NUL")
        assertContentEquals(ByteArray(60) { 0x42 }, card.copyOfRange(21, 81))
        assertEquals(0x00, card[81].toInt())
        assertEquals(167, card.size)
        assertEquals(167, declaredLength(card), "outer_len follows the longer title")
    }

    @Test
    fun `a shorter title shortens the packet, template length is not baked in`() {
        val card = routeCard("Go")

        assertEquals("05010003", card.copyOfRange(17, 21).toHex())
        assertEquals(109, card.size)
        assertEquals(109, declaredLength(card))
        assertNotEquals(126, card.size)
    }

    @Test
    fun `a title containing a field marker is left alone — patches go to the suffix`() {
        // The whole reason routeCard searches from the END. A destination name whose bytes
        // spell 05 09 00 02 would otherwise absorb the total-distance patch, corrupting the
        // name AND leaving the real field showing the French ride's 7.9 km.
        val card = routeCard("\u0005\u0009\u0000\u0002XY")

        assertEquals("05010007", card.copyOfRange(17, 21).toHex())
        assertContentEquals(
            byteArrayOf(0x05, 0x09, 0x00, 0x02, 0x58, 0x59, 0x00),
            card.copyOfRange(21, 28),
            "the title bytes must survive verbatim",
        )
        assertEquals(113, card.size)
        // The real field sits in the suffix. This card's title is 13 bytes shorter than the
        // template's, so 05 09's value moves from 84 to 71.
        assertEquals(0x0000, u16At(card, 71), "05 09 total distance")
    }

    /** Two big-endian bytes at [offset]. */
    private fun u16At(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun declaredLength(packet: ByteArray): Int = u16At(packet, 0)

    // ── Active nav packet ─────────────────────────────────────────────────

    @Test
    fun `the active nav packet is eight TLVs with 500 m default distances`() {
        assertEquals(
            "003b000900000000020100054b31472000" +
                "0502000109" + "0504000201f4" + "0506000130" + "0509000201f4" +
                "0546000130" + "050a000155" + "0605000155" + "060d0001aa",
            K1GCodec.encode(DashCommand.ActiveNav()).toHex(),
            "seg_count 0009 = header segment + eight TLVs",
        )
    }

    @Test
    fun `the primary distance is a full 16-bit field`() {
        assertEquals(
            "003b000900000000020100054b31472000" +
                "0502000109" + "05040002ffff" + "0506000130" + "0509000201f4" +
                "0546000130" + "050a000155" + "0605000155" + "060d0001aa",
            K1GCodec.encode(DashCommand.ActiveNav(primaryDist = 0xFFFF)).toHex(),
        )
    }

    @Test
    fun `projection off flips 06 05 to AA here too`() {
        val on = activeNavPacket(projectionOn = true)
        val off = activeNavPacket(projectionOn = false)

        val differing = on.indices.filter { on[it] != off[it] }
        assertEquals(1, differing.size)
        assertEquals(0x55, on[differing.single()].toInt() and 0xFF)
        assertEquals(0xAA, off[differing.single()].toInt() and 0xFF)
    }

    @Test
    fun `the maneuver glyph constants match the hardware sweep in spec-glyph`() {
        // What Maneuver.dashCode on the Dart side resolves to. Confirmed a byte at a time
        // against the physical dash; 0x0B in particular is "roundabout, exit 1", NOT the
        // neutral arrow the upstream project claimed it was.
        assertEquals(0x09, DashGlyphs.NAV_MANEUVER_STRAIGHT)
        assertEquals(0x14, DashGlyphs.NAV_MANEUVER_TURN_LEFT)
        assertEquals(0x15, DashGlyphs.NAV_MANEUVER_TURN_RIGHT)
        assertEquals(0x0A, DashGlyphs.ROUNDABOUT_CW_BASE)
        assertEquals(0x46, DashGlyphs.ROUNDABOUT_CW_EXIT10_BASE)
        assertEquals(0x31, DashGlyphs.ROUNDABOUT_CCW_BASE)
        assertEquals(0x50, DashGlyphs.ROUNDABOUT_CCW_EXIT10_BASE)
        assertEquals(0x10, DashGlyphs.NAV_UNIT_KM_TENTHS)
        assertEquals(0x30, DashGlyphs.NAV_UNIT_METERS)
    }

    // ── Media and call cards ──────────────────────────────────────────────

    @Test
    fun `now playing is 05 0D with three NUL-separated fields`() {
        assertEquals(
            "001a000200000000020100054b31472000" + "050d0005" + "41" + "00" + "42" + "00" + "43",
            nowPlaying("A", "B", "C").toHex(),
            "no trailing NUL — the last field runs to the end of the TLV",
        )
    }

    @Test
    fun `empty media fields still produce both separators`() {
        assertEquals(
            "0017000200000000020100054b31472000050d00020000",
            nowPlaying("", "", "").toHex(),
        )
    }

    @Test
    fun `an ASCII media field is cut at twenty bytes`() {
        val pkt = nowPlaying("T".repeat(50), "L".repeat(50), "R".repeat(50))

        assertEquals(83, pkt.size)
        assertEquals("050d003e", pkt.copyOfRange(17, 21).toHex(), "3 × 20 + 2 separators = 0x3E")
        assertContentEquals(ByteArray(20) { 0x54 }, pkt.copyOfRange(21, 41))
        assertEquals(0x00, pkt[41].toInt())
        assertContentEquals(ByteArray(20) { 0x4C }, pkt.copyOfRange(42, 62))
        assertEquals(0x00, pkt[62].toInt())
        assertContentEquals(ByteArray(20) { 0x52 }, pkt.copyOfRange(63, 83))
    }

    @Test
    fun `media fields are cut at 20 CHARACTERS, not 20 bytes`() {
        // String.take(20) runs before UTF-8 encoding, so a Cyrillic title yields a 40-byte
        // field. Pinned because the dash's own field width is unknown: "obviously" changing
        // this to a byte limit is a protocol change, not a cleanup.
        val pkt = nowPlaying("Ю".repeat(30), "", "")

        assertEquals(63, pkt.size)
        assertEquals("050d002a", pkt.copyOfRange(17, 21).toHex(), "0x2A = 40 bytes + 2 separators")
        assertContentEquals("Ю".repeat(20).toByteArray(Charsets.UTF_8), pkt.copyOfRange(21, 61))
    }

    @Test
    fun `call notify is a NUL-terminated 05 22 card`() {
        assertEquals(
            "001b000200000000020100054b31472000" + "05220006" + "4d6f746f72" + "00",
            callNotify("Motor").toHex(),
        )
    }

    @Test
    fun `call notify truncates to twenty characters and keeps its terminator`() {
        val pkt = callNotify("N".repeat(50))

        assertEquals(42, pkt.size)
        assertEquals("05220015", pkt.copyOfRange(17, 21).toHex(), "20 name bytes + NUL")
        assertContentEquals(ByteArray(20) { 0x4E }, pkt.copyOfRange(21, 41))
        assertEquals(0x00, pkt[41].toInt())
    }

    @Test
    fun `an empty caller name is a bare NUL, which is exactly call clear`() {
        // Not something to rely on, but worth knowing: clearing the card and notifying an
        // unnamed caller put identical bytes on the wire, so the dash cannot tell them apart.
        assertEquals(callClear().toHex(), callNotify("").toHex())
    }
}

// ── The commands under test, by name ──────────────────────────────────────
//
// `DashCommands` was deleted in stage 4: [K1GCodec] builds every packet now, and the wrappers
// existed only so the call sites could migrate one at a time. These stay because this file is
// about "the bytes for a NAMED command" — spelling `K1GCodec.encode(DashCommand.NavStart)` at
// each of sixty assertions would bury the name under the mechanism. Each is one call; nothing
// is computed here, so nothing here can make a wrong expectation pass.

private fun authRequest() = K1GCodec.encode(DashCommand.AuthRequest)
private fun authSendKey(ct: ByteArray) = K1GCodec.encode(DashCommand.AuthSendKey(ct))
private fun hostnameAnnounce(name: String) = K1GCodec.encode(DashCommand.HostnameAnnounce(name))
private fun timeSync() = K1GCodec.encode(timeSyncNow())
private fun navContext() = K1GCodec.encode(DashCommand.NavContext)
private fun emptyLists() = K1GCodec.encode(DashCommand.EmptyLists)
private fun navStart() = K1GCodec.encode(DashCommand.NavStart)
private fun navPlaceholder() = K1GCodec.encode(DashCommand.NavPlaceholder)
private fun projectionFrame() = K1GCodec.encode(DashCommand.ProjectionFrame)
private fun projectionOn() = K1GCodec.encode(DashCommand.ProjectionOn)
private fun projectionStop() = K1GCodec.encode(DashCommand.ProjectionStop)
private fun projectionOff() = K1GCodec.encode(DashCommand.ProjectionOff)
private fun frameDecodedIdr() = K1GCodec.encode(DashCommand.DecoderOpenedAck(keyFrame = true))
private fun frameDecodedP() = K1GCodec.encode(DashCommand.DecoderOpenedAck(keyFrame = false))
private fun buttonAck(code: Byte) = K1GCodec.encode(DashCommand.ButtonAck(code.toInt() and 0xFF))
private fun heartbeat(tempC: Int) = K1GCodec.encode(DashCommand.Heartbeat(tempC))
private fun callNotify(name: String) = K1GCodec.encode(DashCommand.CallNotify(name))
private fun callClear() = K1GCodec.encode(DashCommand.CallClear)
private fun nowPlaying(title: String, album: String, artist: String) =
    K1GCodec.encode(DashCommand.NowPlaying(title, album, artist))

private fun routeCard(
    title: String,
    projectionOn: Boolean = false,
    maneuver: Int? = null,
    primaryUnit: Int? = null,
    totalDist: Int? = null,
    totalUnit: Int? = null,
    etaHHMM: String? = null,
) = K1GCodec.encode(
    DashCommand.RouteCard(title, projectionOn, maneuver, primaryUnit, totalDist, totalUnit, etaHHMM),
)

/**
 * No default for [projectionOn], deliberately.
 *
 * `DashSession.activeNav()` omits the argument and rides on `DashCommand.ActiveNav`'s own
 * default, so a copy of that default here would pin nothing: flipping the production one to
 * `false` would leave all forty goldens green while the live session sent `06 05 = AA` at
 * 1 Hz during guidance and dropped the dash out of projection mid-ride. Review, 2026-09-22.
 */
private fun activeNavPacket(
    maneuver: Int = DashGlyphs.NAV_MANEUVER_STRAIGHT,
    primaryDist: Int = 500,
    primaryUnit: Int = DashGlyphs.NAV_UNIT_METERS,
    totalDist: Int = 500,
    totalUnit: Int = DashGlyphs.NAV_UNIT_METERS,
    projectionOn: Boolean,
) = K1GCodec.encode(
    DashCommand.ActiveNav(maneuver, primaryDist, primaryUnit, totalDist, totalUnit, projectionOn),
)
