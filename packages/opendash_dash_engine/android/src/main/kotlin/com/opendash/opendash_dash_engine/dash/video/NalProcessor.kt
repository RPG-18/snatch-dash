package com.opendash.opendash_dash_engine.dash.video

import com.opendash.opendash_dash_engine.util.DebugLog

/**
 * Splits Annex-B H.264 output from MediaCodec into individual NAL units,
 * handles the dash-specific IDR bundling requirement, and filters NAL types
 * the dash rejects (SEI, AUD).
 *
 * Dash-specific rules (from better-dash analysis):
 *  - SPS (type 7) and PPS (type 8): cache, do NOT send raw
 *  - IDR (type 5): prepend cached SPS + PPS with Annex-B start codes, send bundle
 *  - SEI (type 6) and AUD (type 9): discard
 *  - All other slices (types 1–4): send as-is
 *
 * The SPS+PPS+IDR bundle is only emitted as ONE unit while it still fits in a
 * single RTP packet — see [collectIdr]. Above that it MUST be split, because
 * [RtpPacketizer.fuA] reads the NAL type from the first byte of what it is
 * handed and would fragment the whole bundle as if it were one giant SPS
 * (type 7), so the dash would reassemble a single bogus SPS carrying the real
 * PPS and IDR as trailing garbage and never see them as NALs at all. At this
 * project's encoder settings (526×300, 200 kbps, `KEY_I_FRAME_INTERVAL = 1`,
 * see [DashEncoder]) a keyframe is several KB against a 1380-byte payload
 * budget, so that path is the norm for map frames, not an edge case.
 */
class NalProcessor(private val onNal: (ByteArray, Boolean) -> Unit) {
    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
    private val TAG = "NalProcessor"

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var loggedParams = false
    private var idrCount = 0

    /**
     * One call = one access unit (MediaCodec hands out one AU per output
     * buffer), so the NALs to send are collected first and only the LAST one
     * is flagged end-of-AU. That flag drives the RTP marker bit (RFC 6184
     * §5.1: set on the final packet of an access unit); deciding it per
     * position here is what lets a split IDR mark only its last packet while
     * a lone P-slice still marks itself.
     */
    fun process(annexB: ByteArray) {
        val toSend = ArrayList<ByteArray>(4)
        for (nal in split(annexB)) {
            if (nal.isEmpty()) continue
            when (val type = nal[0].toInt() and 0x1F) {
                7    -> sps = normalizeSpsForDash(nal)
                8    -> pps = nal
                5    -> collectIdr(nal, toSend)
                6, 9 -> Unit // SEI, AUD — discard
                else -> if (type in 1..4 || type in 10..12) toSend += nal
            }
        }
        for ((i, nal) in toSend.withIndex()) onNal(nal, i == toSend.lastIndex)
    }

    /**
     * The Tripper firmware whitelists the stock phone's SPS shape (67 42 00 29…)
     * before it will leave the loading state. MediaCodec emits a different
     * constraint byte (e.g. 67 42 C0 29…); rewrite byte[2] to 0x00 to match.
     * The constraint byte doesn't affect slice-header parsing, so this is safe.
     */
    private fun normalizeSpsForDash(sps: ByteArray): ByteArray {
        if (sps.size >= 4 &&
            (sps[0].toInt() and 0x1F) == 7 &&
            sps[1] == 0x42.toByte() &&
            sps[3] == 0x29.toByte()
        ) {
            val out = sps.copyOf()
            out[2] = 0x00
            return out
        }
        return sps
    }

    /**
     * Queues the parameter sets and the IDR itself for sending.
     *
     * Bundled into a single NAL only while the result still fits one RTP
     * packet — that keeps the historical, dash-verified single-packet shape
     * for small keyframes (a near-static idle/wallpaper frame). Once it would
     * have to be fragmented the three NALs go out separately instead, because
     * FU-A cannot carry a multi-NAL bundle (see the class doc); SPS and PPS
     * are tens of bytes, so only the IDR is ever actually fragmented.
     */
    private fun collectIdr(idr: ByteArray, out: MutableList<ByteArray>) {
        val s = sps; val p = pps
        if (!loggedParams && s != null && p != null) {
            loggedParams = true
            DebugLog.i(TAG) { "SPS=${s.hex()} PPS=${p.hex()}" }
        }
        if (s == null || p == null) {
            DebugLog.w(TAG) { "IDR with no SPS/PPS cached — dash will not decode" }
            out += idr
            return
        }
        val bundledSize = s.size + START_CODE_4.size + p.size + START_CODE_4.size + idr.size
        val bundled = bundledSize <= RtpPacketizer.MAX_PAYLOAD
        if (++idrCount <= 3) DebugLog.d(TAG) {
            "emit IDR #$idrCount (sps=${s.size}B pps=${p.size}B idr=${idr.size}B, " +
                if (bundled) "bundled ${bundledSize}B)" else "split — bundle ${bundledSize}B > MTU)"
        }
        if (bundled) {
            out += s + START_CODE_4 + p + START_CODE_4 + idr
        } else {
            out += s
            out += p
            out += idr
        }
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    /** Split Annex-B stream on 4-byte (0x00000001) or 3-byte (0x000001) start codes. */
    private fun split(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i < data.size) {
            val sc4 = i + 3 < data.size &&
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            val sc3 = !sc4 && i + 2 < data.size &&
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                data[i+2] == 1.toByte()
            when {
                sc4 -> { if (start >= 0) nals += data.copyOfRange(start, i); start = i + 4; i += 4 }
                sc3 -> { if (start >= 0) nals += data.copyOfRange(start, i); start = i + 3; i += 3 }
                else -> i++
            }
        }
        if (start in 0 until data.size) nals += data.copyOfRange(start, data.size)
        return nals
    }
}
