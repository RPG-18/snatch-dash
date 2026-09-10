package com.opendash.opendash_dash_engine.dash.video

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization test for the RFC 6184 packetizer — инвариант 10 of the refactoring plan.
 *
 * Two of the rules here are dash-specific and not negotiable: no STAP-A aggregation, and a
 * marker bit only on the last packet of an access unit. The dash's decoder is a black box
 * that answers "09 06 55, frame decoded" or says nothing at all, so a violation shows up in
 * the field as a frozen picture, not as an error.
 *
 * SSRC, the starting sequence number and the timestamp base are all drawn from `Random()`
 * per instance and cannot be predicted, so nothing below asserts an absolute value for
 * them. What is asserted is the shape and the DIFFERENCES, which is what a receiver reads.
 */
class RtpPacketizerTest {

    private val captured = mutableListOf<ByteArray>()
    private val packetizer = RtpPacketizer { captured += it }

    private fun ByteArray.marker(): Boolean = (this[1].toInt() and 0x80) != 0
    private fun ByteArray.payloadType(): Int = this[1].toInt() and 0x7F
    private fun ByteArray.seq(): Int = ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)
    private fun ByteArray.ssrc(): Long = readU32(8)
    private fun ByteArray.timestamp(): Long = readU32(4)
    private fun ByteArray.payload(): ByteArray = copyOfRange(12, size)

    private fun ByteArray.readU32(at: Int): Long =
        ((this[at].toLong() and 0xFF) shl 24) or
            ((this[at + 1].toLong() and 0xFF) shl 16) or
            ((this[at + 2].toLong() and 0xFF) shl 8) or
            (this[at + 3].toLong() and 0xFF)

    private fun nal(type: Int, size: Int): ByteArray =
        ByteArray(size) { i -> if (i == 0) (0x60 or type).toByte() else (i and 0xFF).toByte() }

    // ── Header ────────────────────────────────────────────────────────────

    @Test
    fun `the fixed header is 12 bytes, version 2, payload type 96`() {
        packetizer.packetize(nal(1, 100), endOfAU = true, ptsMs = 0)

        val pkt = captured.single()
        assertEquals(112, pkt.size, "12-byte header + the NAL verbatim")
        assertEquals(0x80.toByte(), pkt[0], "version 2, no padding, no extension, CSRC count 0")
        assertEquals(96, pkt.payloadType(), "dynamic PT for H.264")
    }

    @Test
    fun `every packet from one instance carries the same SSRC`() {
        packetizer.packetize(nal(1, 50), endOfAU = false, ptsMs = 0)
        packetizer.packetize(nal(5, 5000), endOfAU = true, ptsMs = 250)

        assertEquals(1, captured.map { it.ssrc() }.distinct().size)
    }

    @Test
    fun `two packetizers do not share a stream identity`() {
        // One per session. Reusing an SSRC across sessions would let a decoder treat the new
        // stream as a continuation of the old one, complete with a sequence-number jump.
        val a = mutableListOf<ByteArray>()
        val b = mutableListOf<ByteArray>()
        RtpPacketizer { a += it }.packetize(nal(1, 20), endOfAU = true, ptsMs = 0)
        RtpPacketizer { b += it }.packetize(nal(1, 20), endOfAU = true, ptsMs = 0)

        // A 1-in-4-billion collision would make this flaky; both fields would have to
        // collide at once, which is not worth engineering around.
        assertTrue(
            a.single().ssrc() != b.single().ssrc() || a.single().timestamp() != b.single().timestamp(),
            "independent instances must not start from the same random state",
        )
    }

    // ── Single-packet NALs ────────────────────────────────────────────────

    @Test
    fun `a NAL that fits is sent verbatim, with no aggregation header`() {
        val source = nal(7, 40)

        packetizer.packetize(source, endOfAU = false, ptsMs = 0)

        val payload = captured.single().payload()
        assertContentEquals(source, payload, "no STAP-A, no FU-A — the dash rejects both")
        assertEquals(7, payload[0].toInt() and 0x1F, "the NAL type byte is the payload's first")
    }

    @Test
    fun `1380 bytes is the last size that fits in one packet`() {
        packetizer.packetize(nal(1, RtpPacketizer.MAX_PAYLOAD), endOfAU = true, ptsMs = 0)
        assertEquals(1, captured.size)

        captured.clear()
        packetizer.packetize(nal(1, RtpPacketizer.MAX_PAYLOAD + 1), endOfAU = true, ptsMs = 0)
        assertEquals(2, captured.size, "one byte over and it fragments")
    }

    // ── Marker bit ────────────────────────────────────────────────────────

    @Test
    fun `the marker rides only on the last packet of an access unit`() {
        // A three-NAL AU: SPS, PPS, IDR. Only the IDR ends it.
        packetizer.packetize(nal(7, 20), endOfAU = false, ptsMs = 0)
        packetizer.packetize(nal(8, 8), endOfAU = false, ptsMs = 0)
        packetizer.packetize(nal(5, 300), endOfAU = true, ptsMs = 0)

        assertEquals(listOf(false, false, true), captured.map { it.marker() })
    }

    @Test
    fun `a fragmented NAL sets the marker on its final fragment only`() {
        packetizer.packetize(nal(5, 5000), endOfAU = true, ptsMs = 0)

        assertEquals(4, captured.size)
        assertEquals(listOf(false, false, false, true), captured.map { it.marker() })
    }

    @Test
    fun `a fragmented NAL in mid-AU carries no marker at all`() {
        packetizer.packetize(nal(5, 5000), endOfAU = false, ptsMs = 0)

        assertTrue(captured.none { it.marker() }, "endOfAU false means the AU is not over")
    }

    // ── FU-A ──────────────────────────────────────────────────────────────

    @Test
    fun `FU-A splits at 1378 payload bytes and reassembles to the original NAL`() {
        val source = nal(5, 5000)

        packetizer.packetize(source, endOfAU = true, ptsMs = 0)

        assertEquals(4, captured.size, "4999 body bytes over 1378-byte fragments")
        assertEquals(
            listOf(1380, 1380, 1380, 867),
            captured.map { it.payload().size },
            "2 bytes of FU indicator + header on every fragment",
        )

        // Reassembly, the way a receiver does it: the original NAL header is rebuilt from
        // the FU indicator's top three bits and the FU header's type.
        val fuInd = captured.first().payload()[0].toInt()
        val fuHdr = captured.first().payload()[1].toInt()
        val rebuilt = byteArrayOf(((fuInd and 0xE0) or (fuHdr and 0x1F)).toByte()) +
            captured.flatMap { it.payload().drop(2) }.toByteArray()
        assertContentEquals(source, rebuilt)
    }

    @Test
    fun `the FU indicator repeats the NRI of the NAL it fragments and says type 28`() {
        packetizer.packetize(nal(5, 3000), endOfAU = true, ptsMs = 0)

        for (pkt in captured) {
            val fuInd = pkt.payload()[0].toInt() and 0xFF
            assertEquals(28, fuInd and 0x1F, "FU-A")
            assertEquals(0x60, fuInd and 0xE0, "NRI copied from the source NAL's 0x60")
        }
    }

    @Test
    fun `start and end bits mark the first and last fragment and nothing between`() {
        packetizer.packetize(nal(5, 5000), endOfAU = true, ptsMs = 0)

        val start = captured.map { (it.payload()[1].toInt() and 0x80) != 0 }
        val end = captured.map { (it.payload()[1].toInt() and 0x40) != 0 }
        assertEquals(listOf(true, false, false, false), start)
        assertEquals(listOf(false, false, false, true), end)
        assertTrue(
            captured.all { (it.payload()[1].toInt() and 0x1F) == 5 },
            "every fragment repeats the original NAL type",
        )
    }

    @Test
    fun `a NAL just over the limit becomes two fragments, the second a single byte`() {
        // The tightest edge: 1381 bytes is 1380 of body, one more than a fragment holds.
        packetizer.packetize(nal(1, RtpPacketizer.MAX_PAYLOAD + 1), endOfAU = true, ptsMs = 0)

        assertEquals(listOf(1380, 4), captured.map { it.payload().size })
        assertEquals(listOf(false, true), captured.map { it.marker() })
    }

    // ── Sequence numbers ──────────────────────────────────────────────────

    @Test
    fun `sequence numbers advance by one per packet, across NALs and fragments`() {
        packetizer.packetize(nal(7, 20), endOfAU = false, ptsMs = 0)
        packetizer.packetize(nal(5, 5000), endOfAU = true, ptsMs = 0)

        val seqs = captured.map { it.seq() }
        assertEquals(5, seqs.size)
        seqs.zipWithNext().forEach { (a, b) ->
            assertEquals((a + 1) and 0xFFFF, b, "gap in the sequence: $a → $b")
        }
    }

    @Test
    fun `the sequence number wraps at 0xFFFF rather than overflowing`() {
        // The counter starts somewhere random in 0..0xFFFE, so 0x10000 + a few packets is
        // enough to guarantee at least one wrap wherever it started.
        repeat(0x10000 + 8) { packetizer.packetize(nal(1, 4), endOfAU = true, ptsMs = 0) }

        val seqs = captured.map { it.seq() }
        assertTrue(seqs.any { it == 0 }, "the counter must pass through zero")
        assertTrue(seqs.all { it in 0..0xFFFF })
        seqs.zipWithNext().forEach { (a, b) -> assertEquals((a + 1) and 0xFFFF, b) }
    }

    // ── Timestamps ────────────────────────────────────────────────────────

    @Test
    fun `every packet of one access unit shares a timestamp`() {
        packetizer.packetize(nal(7, 20), endOfAU = false, ptsMs = 400)
        packetizer.packetize(nal(5, 5000), endOfAU = true, ptsMs = 400)

        assertEquals(1, captured.map { it.timestamp() }.distinct().size)
    }

    @Test
    fun `the clock is 90 kHz — a 250 ms step is 22500 ticks`() {
        packetizer.packetize(nal(1, 10), endOfAU = true, ptsMs = 0)
        packetizer.packetize(nal(1, 10), endOfAU = true, ptsMs = 250)
        packetizer.packetize(nal(1, 10), endOfAU = true, ptsMs = 1000)

        // Differences, not absolutes: the base is random per instance.
        val ts = captured.map { it.timestamp() }
        assertEquals(22_500L, (ts[1] - ts[0]) and 0xFFFFFFFFL, "250 ms at 4 fps")
        assertEquals(67_500L, (ts[2] - ts[1]) and 0xFFFFFFFFL, "750 ms")
        assertEquals(90_000L, (ts[2] - ts[0]) and 0xFFFFFFFFL, "one second")
    }

    @Test
    fun `the timestamp field wraps at 2^32 and the delta survives the wrap`() {
        // What a receiver actually sees. Note what CANNOT be tested from out here: `emit`
        // writes four bytes, so the `and 0xFFFFFFFFL` inside `packetize` is unobservable —
        // removing it changes nothing on the wire. So this pins the wrap itself.
        //
        // The step is deliberately about HALF a cycle — 20_000_000 ms of PTS is
        // 1_800_000_000 ticks — and there are four of them, so the run spans 7.2e9 ticks
        // and crosses 2^32 wherever the random base happened to start.
        //
        // Half a cycle, not almost a whole one, and that matters: a step of 2^32 - 76 ticks
        // also wraps, but a 31-bit counter would show the SAME difference for it (both read
        // as -76), so the test would pass against a narrower field. At 1.8e9 the two cannot
        // agree. The frame loop's videoPtsMs never resets inside a session, so a long ride
        // reaches this range for real.
        val stepMs = 20_000_000L
        val stepTicks = stepMs * 90L

        repeat(5) { i -> packetizer.packetize(nal(1, 10), endOfAU = true, ptsMs = stepMs * i) }

        val ts = captured.map { it.timestamp() }
        assertTrue(ts.all { it in 0..0xFFFFFFFFL }, "the field is 32 bits wide")
        ts.zipWithNext().forEach { (a, b) ->
            assertEquals(stepTicks and 0xFFFFFFFFL, (b - a) and 0xFFFFFFFFL, "$a → $b")
        }
        assertTrue(
            ts.zipWithNext().any { (a, b) -> b < a },
            "four half-cycle steps span more than 2^32 — the counter must have rolled over",
        )
    }
}
