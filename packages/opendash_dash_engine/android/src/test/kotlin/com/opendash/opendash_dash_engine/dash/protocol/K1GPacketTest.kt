package com.opendash.opendash_dash_engine.dash.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization test for the K1G container: the framing every command in
 * [DashCommands] is wrapped in, and the seq byte the dash counts.
 *
 * The two directions do NOT share a header. Outgoing packets carry sixteen bytes of
 * header plus the seq byte, so TLVs start at 17; incoming ones are shorter and start
 * at 8. That asymmetry is the format, not an accident, and [parseOutgoing] below
 * states it as a literal rather than reusing production code — a parser that drifts
 * along with the builder would agree with it forever and prove nothing.
 */
class K1GPacketTest {

    /** Where TLVs begin in an app → dash packet. Incoming is 8; see the class doc. */
    private companion object {
        const val OUTGOING_TLV_OFFSET = 17
        const val SEQ_OFFSET = 16
    }

    /**
     * The test's own reading of the outgoing layout, spelled out from the format doc in
     * [K1GPacket] rather than derived from [K1GPacket.build].
     *
     * `seg_count` counts the fixed header segment too, hence `- 1`.
     */
    private fun parseOutgoing(pkt: ByteArray): List<Tlv> {
        val segCount = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        val out = mutableListOf<Tlv>()
        var i = OUTGOING_TLV_OFFSET
        repeat(segCount - 1) {
            val type = pkt[i].toInt() and 0xFF
            val sub = pkt[i + 1].toInt() and 0xFF
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            out += Tlv(type, sub, pkt.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        assertEquals(pkt.size, i, "TLVs must fill the packet exactly, with nothing trailing")
        return out
    }

    private fun assertSameTlvs(expected: List<Tlv>, actual: List<Tlv>) {
        assertEquals(expected.size, actual.size, "TLV count")
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertEquals(e.type, a.type, "type of TLV #$i")
            assertEquals(e.sub, a.sub, "sub of TLV #$i")
            assertContentEquals(e.value, a.value, "value of TLV #$i")
        }
    }

    // ── Header shape ──────────────────────────────────────────────────────

    @Test
    fun `the header is sixteen fixed bytes, a seq placeholder, then TLVs`() {
        val pkt = K1GPacket.build(K1GPacket.tlv(0x06, 0x80, 0x22))

        assertEquals(
            "0016000200000000020100054b314720000680000122",
            pkt.toHex(),
            "one 1-byte TLV: outer_len 0x16, seg_count 2 (header + one TLV), \"K1G \" at 12, seq 0 at 16",
        )
    }

    @Test
    fun `outer_len counts the whole packet, including its own two bytes`() {
        for (payload in listOf(0, 1, 200, 4000)) {
            val pkt = K1GPacket.build(K1GPacket.tlv(0x05, 0x01, ByteArray(payload)))
            val declared = ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
            assertEquals(pkt.size, declared, "outer_len for a $payload-byte value")
        }
    }

    @Test
    fun `seg_count is one more than the number of TLVs — the header is a segment`() {
        for (n in 0..5) {
            val pkt = K1GPacket.build(*Array(n) { K1GPacket.tlv(0x06, it, 0x00) })
            val segCount = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
            assertEquals(n + 1, segCount, "seg_count for $n TLVs")
        }
    }

    // ── patchSeq ──────────────────────────────────────────────────────────

    @Test
    fun `patchSeq writes offset 16 and leaves every other byte alone`() {
        val original = DashCommands.heartbeat()

        val patched = K1GPacket.patchSeq(original, 0xC3)

        assertEquals(0xC3, patched[SEQ_OFFSET].toInt() and 0xFF)
        assertEquals(0x00, original[SEQ_OFFSET].toInt(), "the source packet must not be mutated")
        for (i in original.indices) {
            if (i == SEQ_OFFSET) continue
            assertEquals(original[i], patched[i], "byte $i changed")
        }
    }

    @Test
    fun `patchSeq masks to a byte, so the counter can roll past 255`() {
        val pkt = DashCommands.projectionFrame()

        // DashSocket's TxSequencer hands out an ever-growing Int; the wire field is one byte.
        assertEquals(0x00, K1GPacket.patchSeq(pkt, 256)[SEQ_OFFSET].toInt() and 0xFF)
        assertEquals(0x01, K1GPacket.patchSeq(pkt, 257)[SEQ_OFFSET].toInt() and 0xFF)
        assertEquals(0xFF, K1GPacket.patchSeq(pkt, 511)[SEQ_OFFSET].toInt() and 0xFF)
    }

    @Test
    fun `patchSeq finds the header magic, not a copy of it inside a payload`() {
        // A route card titled "K1G " puts the magic in the TLV value as well. The header's
        // copy comes first, and that is the one that must be patched — patching the payload
        // would corrupt the destination name and leave the dash's seq counter frozen at 0.
        val pkt = DashCommands.routeCard("K1G K1G ", projectionOn = true)
        assertTrue(
            pkt.copyOfRange(OUTGOING_TLV_OFFSET, pkt.size).toHex().contains("4b314720"),
            "test setup: the title was expected to contain the magic",
        )

        val patched = K1GPacket.patchSeq(pkt, 0x7F)

        assertEquals(0x7F, patched[SEQ_OFFSET].toInt() and 0xFF)
        assertContentEquals(
            pkt.copyOfRange(OUTGOING_TLV_OFFSET, pkt.size),
            patched.copyOfRange(OUTGOING_TLV_OFFSET, pkt.size),
            "nothing past the header may move",
        )
    }

    @Test
    fun `patchSeq on a packet with no magic leaves the body untouched`() {
        // Defensive: patchSeq is called on whatever DashSocket is handed. Today nothing
        // reaches it without a header, and if that ever changes this pins what happens —
        // outer_len is still rewritten, the body is not.
        val junk = ByteArray(20) { 0x11 }

        val patched = K1GPacket.patchSeq(junk, 5)

        assertEquals(0x00, patched[0].toInt())
        assertEquals(20, patched[1].toInt())
        assertContentEquals(ByteArray(18) { 0x11 }, patched.copyOfRange(2, 20))
    }

    // ── parseIncoming ─────────────────────────────────────────────────────

    @Test
    fun `parseIncoming reads segments from offset 8`() {
        // 07 01 01 — the auth confirmation, as it arrives from the dash.
        val datagram = "000e000200000000070100010102".hexToBytes()

        val tlvs = K1GPacket.parseIncoming(datagram)

        assertEquals(1, tlvs.size)
        assertEquals(0x07, tlvs[0].type)
        assertEquals(0x01, tlvs[0].sub)
        assertContentEquals(byteArrayOf(0x01), tlvs[0].value)
    }

    @Test
    fun `parseIncoming stops at the end of a truncated TLV instead of throwing`() {
        // 15 bytes: an 8-byte header, then a TLV declaring 8 value bytes of which only 3
        // arrived. Truncation happens for real — UDP cuts silently at the receive buffer
        // (DashSocket, задача 2.3) — so the loop has to survive it: a throw here runs on
        // the RX coroutine and kills the session.
        val datagram = "000f00020000000009060008010203".hexToBytes()
        assertEquals(15, datagram.size, "fixture: outer_len must agree with the real length")

        val tlvs = K1GPacket.parseIncoming(datagram)

        assertEquals(1, tlvs.size)
        assertContentEquals(
            byteArrayOf(0x01, 0x02, 0x03),
            tlvs[0].value,
            "the value is cut at the end of the datagram, not padded out to 8",
        )
    }

    @Test
    fun `parseIncoming ignores a seg_count larger than the bytes on hand`() {
        val datagram = "000e00ff00000000070100010102".hexToBytes()

        val tlvs = K1GPacket.parseIncoming(datagram)

        assertEquals(1, tlvs.size, "255 segments were promised; one was delivered")
    }

    @Test
    fun `parseIncoming returns nothing for an empty or header-only datagram`() {
        assertTrue(K1GPacket.parseIncoming(ByteArray(0)).isEmpty())
        assertTrue(K1GPacket.parseIncoming(ByteArray(7)).isEmpty(), "shorter than the 8-byte header")
        assertTrue(K1GPacket.parseIncoming("0008000100000000".hexToBytes()).isEmpty())
    }

    // ── Round trip ────────────────────────────────────────────────────────

    @Test
    fun `build then parse returns the TLVs unchanged, over a thousand random packets`() {
        val rnd = Random(20260910)
        repeat(1000) { iteration ->
            val tlvs = List(rnd.nextInt(1, 6)) {
                Tlv(
                    type = rnd.nextInt(0, 256),
                    sub = rnd.nextInt(0, 256),
                    // Values run past 255 so the two-byte length field is actually exercised.
                    value = ByteArray(rnd.nextInt(0, 400)) { rnd.nextInt(0, 256).toByte() },
                )
            }

            val pkt = K1GPacket.build(*tlvs.toTypedArray())

            assertSameTlvs(tlvs, parseOutgoing(pkt))
            assertEquals(
                pkt.size,
                ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF),
                "outer_len on iteration $iteration",
            )
        }
    }

    @Test
    fun `an empty TLV value survives the round trip`() {
        val pkt = K1GPacket.build(K1GPacket.tlv(0x05, 0x22, ByteArray(0)))

        val parsed = parseOutgoing(pkt)

        assertEquals(1, parsed.size)
        assertEquals(0, parsed[0].value.size)
    }

    @Test
    fun `the int overload truncates each value to a byte`() {
        val tlv = K1GPacket.tlv(0x06, 0x06, 300, -1, 0)

        assertContentEquals(byteArrayOf(0x2C, 0xFF.toByte(), 0x00), tlv.value)
    }
}
