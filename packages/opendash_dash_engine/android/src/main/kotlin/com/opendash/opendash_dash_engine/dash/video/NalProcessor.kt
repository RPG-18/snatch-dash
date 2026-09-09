package com.opendash.opendash_dash_engine.dash.video

import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics

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
 * project's encoder settings (526×300, 200 kbps, a key frame every 2 s, see
 * [DashEncoder]) a keyframe is several KB against a 1380-byte payload budget,
 * so that path is the norm for map frames, not an edge case — the 2026-09-09
 * ride measured 39.7 KB for one.
 */
class NalProcessor(private val onNal: (ByteArray, Boolean) -> Unit) {
    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
    private val TAG = "NalProcessor"

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var loggedSps: ByteArray? = null
    private var loggedPps: ByteArray? = null
    private var idrCount = 0
    private var bundledIdrs = 0
    private var splitIdrs = 0

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
     * for small keyframes (a near-static frame, e.g. a parked rider). Once it would
     * have to be fragmented the three NALs go out separately instead, because
     * FU-A cannot carry a multi-NAL bundle (see the class doc); SPS and PPS
     * are tens of bytes, so only the IDR is ever actually fragmented.
     */
    private fun collectIdr(idr: ByteArray, out: MutableList<ByteArray>) {
        val s = sps; val p = pps
        if (s != null && p != null) logParameterSetsIfChanged(s, p)
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
            bundledIdrs++
            out += s + START_CODE_4 + p + START_CODE_4 + idr
        } else {
            splitIdrs++
            out += s
            out += p
            out += idr
        }
    }

    /**
     * How this window's key frames went out, `bundled/split`, then reset.
     *
     * The two shapes are structurally different on the wire — one RTP packet whose
     * payload is `SPS ‖ start code ‖ PPS ‖ start code ‖ IDR`, versus three NALs of
     * which the last is fragmented — and which one is used is decided per key frame
     * by [collectIdr], from its size alone. A parked rider crosses that threshold in
     * both directions as the picture's complexity drifts, so a session can switch
     * shape repeatedly without anything in the ride file saying so. Counted here
     * because the field report this answers ("garbage blocks, parked, never
     * recovered") needs to know whether the shape moved at all before that is
     * either suspected or ruled out.
     */
    fun drainIdrShapes(): String {
        val line = "$bundledIdrs/$splitIdrs"
        bundledIdrs = 0
        splitIdrs = 0
        return line
    }

    /**
     * The parameter sets, into the ride file, on every change — not once per session.
     *
     * The firmware whitelists the stock phone's SPS shape before it will leave the
     * loading state (see [normalizeSpsForDash]), and [normalizeSpsForDash] only
     * reaches that shape when byte[1] and byte[3] ALREADY match: an encoder that
     * re-emits its parameter sets mid-stream with a different profile or level slips
     * through unrewritten, and the dash then refuses every key frame that carries it.
     * That failure does not heal — a lost packet is repaired by the next IDR, a
     * rejected SPS is not — so it is the one shape of "the picture broke and stayed
     * broken" that the rest of this file's telemetry cannot distinguish from packet
     * loss. `MediaCodec.setParameters` on a live encoder (the idle/moving bitrate
     * switch in DashEncoder.requestBitrate) is a documented occasion for an encoder
     * to do exactly that, and the switch fires when the rider stops.
     *
     * Logged once per distinct pair, so a stream that never changes them costs one
     * line, and at WARN when the outgoing SPS does not match what the dash accepts.
     */
    private fun logParameterSetsIfChanged(s: ByteArray, p: ByteArray) {
        val first = loggedSps == null
        if (!first && s.contentEquals(loggedSps) && p.contentEquals(loggedPps)) return
        loggedSps = s
        loggedPps = p
        val ok = matchesDashWhitelist(s)
        val line = (if (first) "parameter sets" else "parameter sets CHANGED mid-stream") +
            " SPS=${s.hex()} PPS=${p.hex()} dashShape=${if (ok) "ok" else "MISMATCH"}"
        if (ok) RideDiagnostics.log("stream", line) else RideDiagnostics.warn("stream", line)
    }

    /**
     * Does the SPS about to go out have the shape the dash accepts, `67 42 00 29…`?
     *
     * Asked of the value AFTER [normalizeSpsForDash], because what matters is what
     * lands on the wire, not whether the rewrite ran.
     */
    private fun matchesDashWhitelist(sps: ByteArray): Boolean =
        sps.size >= 4 &&
            (sps[0].toInt() and 0x1F) == 7 &&
            sps[1] == 0x42.toByte() &&
            sps[2] == 0x00.toByte() &&
            sps[3] == 0x29.toByte()

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
