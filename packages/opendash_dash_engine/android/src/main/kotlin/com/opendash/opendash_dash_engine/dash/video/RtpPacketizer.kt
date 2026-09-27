package com.opendash.opendash_dash_engine.dash.video

import java.util.Random

/**
 * RFC 6184 H.264 RTP packetizer tuned for the Tripper Dash.
 *
 * Rules enforced (from better-dash analysis):
 *  - NO STAP-A (type 24) — dash rejects aggregation packets
 *  - FU-A (type 28) fragmentation for NALs larger than MAX_PAYLOAD
 *  - Marker bit only on the LAST RTP packet of each access unit
 *  - 90 kHz RTP clock
 *  - Max payload 1380 bytes — see [MAX_PAYLOAD] for where that is and is
 *    not from, and why it is not being raised
 */
class RtpPacketizer(private val onPacket: (ByteArray) -> Unit) {
    companion object {
        /**
         * Largest RTP payload sent in one packet. Public because [NalProcessor]
         * has to know it too: it decides whether an SPS+PPS+IDR bundle still
         * fits one packet or must be split, since [fuA] can only fragment a
         * SINGLE NAL unit (see its doc).
         *
         * **Where 1380 comes from is not recorded anywhere, and it is not the
         * vendor's number.** The line above says "from better-dash analysis";
         * better-dash is not vendored here, so its own derivation cannot be
         * checked from this repo. What the 2026-09-27 decompile of
         * `com.royalenfield.reprime` 10.1.22 does settle is that the original
         * app arrived at something else, by a chain that is fully consistent
         * (classes named by their own string constants — jadx names shift
         * between runs):
         *
         *  - `"Socket"` — RTP buffers are `new byte[1400]`, RTP header 12.
         *  - `"H264Packetizer"` — declares 1372 (unused) and fragments at
         *    1358. Sends `nalLen + 12` for a single NAL and `chunk + 14` for
         *    an FU-A, with both capped at 1358.
         *
         * So: **1400 IP datagram → 1372 UDP payload (1400 − 8 − 20) → 1358 of
         * H.264 in an FU-A (1372 − 12 − 2)**. Every constant falls out of a
         * 1400-byte MTU budget, not out of 1500 — which is also why the
         * "unexplained 80 bytes" in the old task text were never there: they
         * were measured against the wrong divisor.
         *
         * **Ours is 20 bytes bigger than anything the dash has ever been sent
         * by the stock app**: 1380 + 12 = 1392 UDP → 1420 IP, against the
         * original's ceiling of exactly 1400.
         *
         * **And the prize for raising it is far smaller than the old task
         * text claimed.** Not ~3% — that is the total header overhead
         * (40/1420), winnable only at infinite MTU. Going to the largest
         * payload that still fits a 1500 MTU (1460) moves wire efficiency
         * from 1380/1420 = 97.18% to 1460/1500 = 97.33%: **0.15% fewer bytes**
         * for the same video, and ~5.5% fewer packets. That is the whole
         * prize, and it is paid for by going further past a size the vendor's
         * own app never produces, on a link whose path MTU nobody has
         * measured.
         *
         * **We also could not see it go wrong if it did.** RTP on :5000 is
         * deliberately excluded from `PacketCapture`, IP fragmentation happens
         * in the kernel where the app cannot observe it, and there is no
         * phone→dash loss counter anywhere. So "1420 has been fine in the
         * field" is not a claim this project is equipped to make — the honest
         * statement is that nothing has been attributed to it, which is weaker.
         * Raising this needs an instrument first, not arithmetic.
         *
         * (Task 9 in `network-refactoring.md`.)
         */
        const val MAX_PAYLOAD = 1380
        private const val PT = 96
    }

    private val rng = Random()
    private var seq   = rng.nextInt(0xFFFF)
    private val ssrc  = rng.nextInt()
    private val tsBase = rng.nextInt().toLong() and 0xFFFFFFFFL

    /**
     * Packetize a single NAL unit.
     * @param nal    raw NAL bytes (no start code)
     * @param endOfAU true if this is the last NAL in the access unit (triggers marker bit)
     * @param ptsMs  presentation timestamp in milliseconds. Deliberately NOT
     *   `System.currentTimeMillis()` at the call site — see
     *   [com.opendash.opendash_dash_engine.dash.FrameStreamer]'s `videoPtsMs`, a clock advanced
     *   by the INTENDED frame interval once per frame OUT OF THE CODEC rather than real elapsed
     *   time, so the RTP timeline stays evenly spaced regardless of render/encode jitter. Ported
     *   from OpenMotoDash/NorthStar's `videoPtsMs` (see spec/wifi_retry_policy.md's "Из живого
     *   форка") after the 2026-08-29 field session found the dash decoding almost nothing past
     *   the first frame of every stream while wall-clock timestamps were in use.
     */
    fun packetize(nal: ByteArray, endOfAU: Boolean, ptsMs: Long) {
        val ts = (tsBase + ptsMs * 90L) and 0xFFFFFFFFL
        if (nal.size <= MAX_PAYLOAD) {
            emit(nal, marker = endOfAU, ts = ts)
        } else {
            fuA(nal, endOfAU, ts)
        }
    }

    /**
     * RFC 6184 §5.8 FU-A. Fragments **one** NAL unit: the type is taken from
     * `nal[0]` and stamped onto every fragment, so handing this a concatenation
     * of several NALs would relabel all of them as the first one's type and
     * produce an undecodable stream. [NalProcessor] is responsible for never
     * passing a multi-NAL bundle in here.
     */
    private fun fuA(nal: ByteArray, endOfAU: Boolean, ts: Long) {
        val nalType  = nal[0].toInt() and 0x1F
        val fuInd    = ((nal[0].toInt() and 0xE0) or 28).toByte()
        var offset   = 1
        var isFirst  = true
        while (offset < nal.size) {
            val remaining = nal.size - offset
            val chunkLen  = minOf(MAX_PAYLOAD - 2, remaining)
            val isLast    = chunkLen >= remaining

            val fuHeader = ((if (isFirst) 0x80 else 0) or
                           (if (isLast)  0x40 else 0) or nalType).toByte()

            val payload = ByteArray(2 + chunkLen)
            payload[0] = fuInd
            payload[1] = fuHeader
            nal.copyInto(payload, 2, offset, offset + chunkLen)

            emit(payload, marker = isLast && endOfAU, ts = ts)
            offset += chunkLen
            isFirst = false
        }
    }

    private fun emit(payload: ByteArray, marker: Boolean, ts: Long) {
        val pkt = ByteArray(12 + payload.size)
        pkt[0] = 0x80.toByte()
        pkt[1] = ((if (marker) 0x80 else 0x00) or (PT and 0x7F)).toByte()
        pkt[2] = ((seq shr 8) and 0xFF).toByte()
        pkt[3] = (seq and 0xFF).toByte()
        pkt[4] = ((ts shr 24) and 0xFF).toByte()
        pkt[5] = ((ts shr 16) and 0xFF).toByte()
        pkt[6] = ((ts shr  8) and 0xFF).toByte()
        pkt[7] = (ts and 0xFF).toByte()
        val s = ssrc.toLong() and 0xFFFFFFFFL
        pkt[8]  = ((s shr 24) and 0xFF).toByte()
        pkt[9]  = ((s shr 16) and 0xFF).toByte()
        pkt[10] = ((s shr  8) and 0xFF).toByte()
        pkt[11] = (s and 0xFF).toByte()
        payload.copyInto(pkt, 12)
        seq = (seq + 1) and 0xFFFF
        onPacket(pkt)
    }
}
