package com.opendash.opendash_dash_engine.dash.video

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what leaves [NalProcessor] byte for byte, because the dash is the only
 * specification there is and it is not available from a JVM test.
 *
 * The case this file exists for is [spsIsRewrittenOnlyForTheLegacyShape]. It was
 * written to catch a suspected failure — the dash refusing an un-rewritten SPS — and
 * the ride of 2026-09-09 22:01-22:32 falsified that: twelve sessions sent
 * `67 42 C0 15…` and all twelve got the dash's own "key frame decoded" back. What the
 * test pins is therefore not a hazard but a fact worth not drifting: the rewrite
 * reaches only `42 xx 29`, so on this encoder (which emits level 0x15) it does
 * nothing at all. If someone later widens the guard, or an encoder appears that lands
 * on it, this test is where that shows up.
 */
class NalProcessorTest {

    private val sc4 = byteArrayOf(0, 0, 0, 1)
    private val sc3 = byteArrayOf(0, 0, 1)

    /** Collects `(nal, endOfAU)` pairs in emission order. */
    private class Sink {
        val nals = mutableListOf<ByteArray>()
        val endFlags = mutableListOf<Boolean>()
        val proc = { n: ByteArray, e: Boolean -> nals += n; endFlags += e; Unit }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun annexB(vararg nals: ByteArray, startCode: ByteArray = sc4): ByteArray {
        var out = ByteArray(0)
        for (n in nals) out += startCode + n
        return out
    }

    private val pps = bytes(0x68, 0x22)
    private val smallIdr = bytes(0x65, 0x33)
    /** Comfortably past [RtpPacketizer.MAX_PAYLOAD], so the bundle cannot fit one packet. */
    private val bigIdr = byteArrayOf(0x65) + ByteArray(RtpPacketizer.MAX_PAYLOAD + 20) { 0xAA.toByte() }

    @Test
    fun smallIdrIsBundledWithTheParameterSetsIntoOneNal() {
        val sink = Sink()
        val sps = bytes(0x67, 0x42, 0xC0, 0x29, 0x11)
        NalProcessor(sink.proc).process(annexB(sps, pps, smallIdr))

        assertEquals(1, sink.nals.size, "SPS+PPS+IDR under the MTU is one NAL")
        assertContentEquals(
            bytes(0x67, 0x42, 0x00, 0x29, 0x11) + sc4 + pps + sc4 + smallIdr,
            sink.nals[0],
        )
        assertEquals(listOf(true), sink.endFlags)
    }

    @Test
    fun largeIdrGoesOutAsThreeNalsWithEndOfAuOnlyOnTheLast() {
        val sink = Sink()
        val sps = bytes(0x67, 0x42, 0xC0, 0x29, 0x11)
        NalProcessor(sink.proc).process(annexB(sps, pps, bigIdr))

        assertEquals(3, sink.nals.size, "a bundle over the MTU must be split — FU-A takes one NAL")
        assertContentEquals(bytes(0x67, 0x42, 0x00, 0x29, 0x11), sink.nals[0])
        assertContentEquals(pps, sink.nals[1])
        assertContentEquals(bigIdr, sink.nals[2])
        assertEquals(listOf(false, false, true), sink.endFlags, "the marker bit belongs to the AU's last packet")
    }

    @Test
    fun spsIsRewrittenOnlyForTheLegacyShape() {
        // Baseline profile (0x42) at level 4.1 (0x29): the constraint byte is rewritten.
        assertEquals(0x00, firstNalOf(bytes(0x67, 0x42, 0xC0, 0x29, 0x11))[2].toInt() and 0xFF)

        // Another profile — left exactly as the encoder produced it.
        assertEquals(0xC0, firstNalOf(bytes(0x67, 0x4D, 0xC0, 0x29, 0x11))[2].toInt() and 0xFF)

        // Another level — same. This is the real device's case: OMX.hisi emits level
        // 0x15 (2.1) however loudly DashEncoder asks for AVCLevel41, so the rewrite is
        // skipped on every frame of every ride, and the dash decodes anyway.
        assertEquals(0xC0, firstNalOf(bytes(0x67, 0x42, 0xC0, 0x15, 0x11))[2].toInt() and 0xFF)
    }

    /** Runs one SPS through a split IDR, where the SPS is emitted as its own NAL. */
    private fun firstNalOf(sps: ByteArray): ByteArray {
        val sink = Sink()
        NalProcessor(sink.proc).process(annexB(sps, pps, bigIdr))
        return sink.nals[0]
    }

    @Test
    fun seiAndAudAreDiscardedAndSlicesPassThrough() {
        val sink = Sink()
        val sei = bytes(0x06, 0x05, 0x10)
        val aud = bytes(0x09, 0x30)
        val slice = bytes(0x41, 0x01, 0x02)
        NalProcessor(sink.proc).process(annexB(sei, aud, slice))

        assertEquals(1, sink.nals.size)
        assertContentEquals(slice, sink.nals[0])
        assertEquals(listOf(true), sink.endFlags)
    }

    @Test
    fun threeByteStartCodesSplitTheSameWayAsFourByte() {
        val sink = Sink()
        val sps = bytes(0x67, 0x42, 0xC0, 0x29, 0x11)
        NalProcessor(sink.proc).process(annexB(sps, pps, smallIdr, startCode = sc3))

        assertEquals(1, sink.nals.size)
        assertContentEquals(
            bytes(0x67, 0x42, 0x00, 0x29, 0x11) + sc4 + pps + sc4 + smallIdr,
            sink.nals[0],
            "the bundle is always rebuilt with 4-byte start codes, whatever came in",
        )
    }

    @Test
    fun idrWithoutCachedParameterSetsGoesOutAlone() {
        val sink = Sink()
        NalProcessor(sink.proc).process(annexB(smallIdr))

        assertEquals(1, sink.nals.size)
        assertContentEquals(smallIdr, sink.nals[0], "no SPS/PPS to prepend — send what we have")
    }

    @Test
    fun idrShapeCountsSeparateBundledFromSplitAndResetOnDrain() {
        val sink = Sink()
        val proc = NalProcessor(sink.proc)
        val sps = bytes(0x67, 0x42, 0xC0, 0x29, 0x11)

        proc.process(annexB(sps, pps, smallIdr))
        proc.process(annexB(smallIdr))
        proc.process(annexB(bigIdr))
        assertEquals("2/1", proc.drainIdrShapes(), "two under the MTU, one over it")

        // The shape flipping mid-session is the thing worth seeing in a ride file, so the
        // window has to start clean rather than accumulate for the life of the stream.
        assertEquals("0/0", proc.drainIdrShapes())

        proc.process(annexB(bigIdr))
        assertEquals("0/1", proc.drainIdrShapes())
        assertTrue(sink.nals.isNotEmpty())
    }
}
