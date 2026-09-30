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
         * **1380's provenance is recorded in better-dash — twice, with two
         * different meanings and two different rationales. We inherited the
         * larger one. The vendor's own cap is 1360.** Trail followed
         * 2026-09-27 against `better-dash` and a decompile of
         * `com.royalenfield.reprime` 10.1.22.
         *
         * better-dash states 1380 in two ways that do not agree:
         *
         *  - `tripper_app_like_nav.py` (`--pkt-size`) calls it the *total
         *    UDP-payload size*, sourced from a capture of the stock phone:
         *    *"the real phone's full RTP UDP payloads are 1380 B in
         *    nav_open_ok.pcap"*. It then does `max_payload = pkt_size - 20`,
         *    so its RTP payload budget is **1360**.
         *  - `dash_ui/rtp.py` defaults `max_payload = 1380` and uses it
         *    directly as the *RTP payload*, as this class does.
         *    `dash_ui/stream.py` gives that its own rationale — a ceiling
         *    "to avoid IP fragmentation on the 192.168.1.x link", which is
         *    the sentence this file's own class doc used to carry verbatim.
         *
         * **We ported the `dash_ui` line**, hence 1380 + 12 = 1392 UDP →
         * 1420 IP. Whether that is an inherited slip or a deliberate second
         * choice cannot be told from better-dash: both readings are written
         * down there as if intended, and its clamp comment ("42 bytes of
         * headroom … relative to `pkt_size` (default 1400)") matches neither
         * its own subtraction of 20 nor its own default of 1380.
         *
         * **What the vendor actually sends** (classes named by their own
         * string constants — jadx names shift between runs):
         *
         *  - `"Socket"` — `DatagramPacket` buffers are `new byte[1400]`, and
         *    the length actually sent is set per packet by the packetizer.
         *    So 1400 bounds the UDP payload; it is not itself proof of an IP
         *    budget.
         *  - `"H264Packetizer"` — literals, not derivations: fragments when a
         *    NAL exceeds **1358**, sends `nalLen + 12` for a single NAL and
         *    `chunk + 14` for an FU-A, both capped at 1358. (It also declares
         *    1372, which nothing reads.)
         *
         * Maxima: RTP payload **1358** single-NAL and **1360** FU-A, UDP
         * payload **1372**, IP datagram **1400** — which is the 1400 buffer
         * exactly, though whether the author derived one from the other is a
         * guess.
         *
         * In the units this constant is in:
         *
         * | | RTP payload cap | UDP | IP |
         * |---|---|---|---|
         * | stock app (decompile) | 1358 / 1360 | 1372 | 1400 |
         * | better-dash `tripper_app_like_nav.py` | 1360 | 1372 | 1400 |
         * | better-dash `dash_ui/rtp.py` | 1380 | 1392 | 1420 |
         * | **here** | **1380** | **1392** | **1420** |
         *
         * So our ceiling sits 20-22 bytes above the vendor's, and the
         * direction that aligns us is DOWN, to 1360. Not done here: it
         * changes the video path, nothing in the field points at it, and the
         * argument is symmetry rather than a defect. Left as a decision with
         * the evidence attached.
         *
         * **One reconciliation is tempting and stays unproven.** 1372 + 8 =
         * 1380, so better-dash's capture reading would line up with the
         * decompile if the 1380 it saw was Wireshark's `udp.length`, which
         * includes the UDP header. `nav_open_ok.pcap` is not in better-dash,
         * and its help text says "UDP-payload" outright, so this is a
         * coincidence worth noting and nothing more. One filter on that file
         * would settle it.
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
         * (Task 9 in `plans/done/2026-09-30-network-refactoring.md`.)
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
