package com.opendash.opendash_dash_engine.dash.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.opendash.opendash_dash_engine.util.DebugLog

/**
 * MediaCodec H.264 encoder for the Tripper Dash stream:
 *   526 x 300, 2-4 fps, ~200 kbps, Baseline L4.1, 1-second IDR interval.
 *
 * [FPS] is the maximum encoder hint. The frame loop feeds 4 fps while moving and
 * throttles to 2 fps when stopped, matching the stable RE projection envelope.
 * The hardware encoder auto-timestamps each frame from the input surface, so the
 * variable feed rate is fine. Tiny resolution → still far under the OLED-projection
 * draw this whole project exists to avoid.
 *
 * Frames are drawn with Android Canvas via the input Surface's hardware
 * canvas — call [renderFrame] with a draw lambda, then [drain] to pull
 * encoded NAL data out.
 *
 * @param onEncodedData  called with (annexBBytes, isKeyFrame, isCodecConfig) for each
 *   output buffer. `isCodecConfig` marks the SPS/PPS buffer, which is passed through like
 *   any other (NalProcessor needs it) but is not a frame — anything counting frames or
 *   measuring their size has to skip it or it reports 30 bytes of parameter sets.
 */
class DashEncoder(private val onEncodedData: (ByteArray, Boolean, Boolean) -> Unit) {
    companion object {
        const val WIDTH   = 526
        const val HEIGHT  = 300
        const val FPS     = 4
        // ~200 kbps is the dash's OWN high profile (q2g = 204800), not a number we
        // picked — and the ceiling matters as much as the target: OpenMotoDash/
        // NorthStar records that the 1.2 Mbps this used to run at "risked
        // overrunning the dash decoder". A frozen picture on a dash that is still
        // answering telemetry is exactly what an overrun would look like from
        // here, so staying under the profile is not an efficiency nicety.
        const val BITRATE = 200_000

        /**
         * The dash's low profile (q2g = 102400), requested while the map is not
         * moving — see [requestBitrate].
         *
         * Ported from NorthStar 2026-08-28, a month after this file was taken from
         * it; the change simply never came across. Nothing else in the two
         * encoders differs.
         */
        const val BITRATE_IDLE = 100_000
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val TAG = "DashEncoder"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    fun prepare() {
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        // Hardware if the device has one that takes these frames, otherwise whatever does
        // — see [selectEncoder]. Null only if nothing in the list qualifies, and then the
        // OS default is the last thing left to try.
        val name = selectEncoder()
        val c = if (name != null) MediaCodec.createByCodecName(name)
                else MediaCodec.createEncoderByType(MIME)
        // Assigned only once the codec is running. Everything from configure() on can
        // throw, and until this line the instance has no other owner: `codec` would stay
        // null, the caller's `encoder?.release()` would find nothing, and a component
        // instance — a globally limited resource — would sit occupied until the finalizer
        // runs, which MediaCodec.release()'s own javadoc asks callers not to wait for.
        // One per failed connection attempt, and a rider retries "Send to Dash".
        try {
            DebugLog.i(TAG) { "Encoder: ${c.name}" }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
        } catch (e: Throwable) {
            // Both documented failures of configure() — IllegalArgumentException for an
            // unacceptable format, CodecException for a codec error — plus whatever
            // createInputSurface/start add, are the same situation here: this codec never
            // became ours. The surface goes with it; releasing the codec invalidates it.
            runCatching { inputSurface?.release() }
            inputSurface = null
            runCatching { c.release() }
            throw e
        }
        codec = c
    }

    /**
     * Pick an AVC encoder that can actually take our frames: hardware if one qualifies,
     * otherwise any encoder that does; null → let the OS pick and hope.
     *
     * The hard filter is **`COLOR_FormatSurface`**, and it is the one check with a documented
     * basis. `CodecCapabilities.isFormatSupported` does not look at it at all — `KEY_COLOR_FORMAT`
     * is absent from the "MediaFormat keys considered" table in its javadoc — and surface input
     * is not something the CDD requires of an encoder: 5.1.7 mandates `COLOR_FormatYUV420Flexible`
     * and a planar/semiplanar YUV420, while surface mode appears only as a STRONGLY RECOMMENDED,
     * and for image codecs at that. We feed frames through `createInputSurface`, so an encoder
     * without it cannot serve us at all.
     *
     * **`isFormatSupported` is deliberately NOT used**, and this is the second attempt at this
     * function saying so. It enforces the encoder's declared size alignment
     * (`width % mWidthAlignment` in `VideoCapabilities.supports`), and our frame is 526x300 —
     * 526 % 16 = 14. On every device whose AVC encoder declares 16x16 alignment yet encodes this
     * stream today, that check would quietly drop the hardware encoder and leave the ride to
     * `c2.android.avc.encoder` on the CPU, with nothing in a release log to say so. Same for the
     * `KEY_PROFILE`/`KEY_LEVEL` we configure with: Baseline L4.1 is inherited over-specification
     * for 526x300 at 4 fps, and an encoder advertising a maximum of `AVCLevel4` would fail the
     * check while doing the work. A silent demotion to software is worse than the failure the
     * check would prevent — that one is loud (`configure()` throws, the codec is released, the
     * session ends in `startStream failed`) and it is the rarer case.
     *
     * The size check that remains is the plain range, without alignment: it catches an encoder
     * that cannot do this size at all, and cannot demote one that can.
     *
     * A software encoder runs the CPU hot, which is what preferring hardware is for; but it
     * draws a map, and that beats a dash showing nothing.
     */
    private fun selectEncoder(): String? {
        val usable = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            if (!info.isEncoder) return@filter false
            if (!info.supportedTypes.any { it.equals(MIME, true) }) return@filter false
            val caps = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull()
                ?: return@filter false
            val video = caps.videoCapabilities ?: return@filter false
            when {
                !video.supportedWidths.contains(WIDTH) || !video.supportedHeights.contains(HEIGHT) -> {
                    DebugLog.i(TAG) { "${info.name}: ${WIDTH}x$HEIGHT out of its range — skipping" }
                    false
                }
                !caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) -> {
                    DebugLog.i(TAG) { "${info.name}: no COLOR_FormatSurface — skipping" }
                    false
                }
                else -> true
            }
        }
        // Both flags are API 29, which is the project floor — before it was raised this
        // preference was dead code on 26-28, and every one of those devices silently took
        // whatever the OS handed it.
        return usable.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly }?.name
            ?: usable.firstOrNull()?.name
    }

    /** Draw one frame into the encoder via hardware canvas. */
    fun renderFrame(draw: (Canvas) -> Unit) {
        val surface = inputSurface ?: return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            return
        }
        try {
            draw(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Pull all available encoded buffers; call after every [renderFrame]. */
    fun drain() {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit // SPS/PPS come as CODEC_CONFIG buffer
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx) ?: run {
                        codec.releaseOutputBuffer(idx, false); continue
                    }
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    // Pass EVERY buffer through, including CODEC_CONFIG (SPS/PPS) — the
                    // NAL processor needs the parameter sets to bundle them with each IDR,
                    // otherwise the dash can't initialise its decoder and times out.
                    if (info.size > 0) {
                        val data = ByteArray(info.size).also { buf.get(it) }
                        onEncodedData(data, isKey, isConfig)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    /**
     * Retarget the running encoder, without rebuilding it or forcing an IDR.
     *
     * `PARAMETER_KEY_VIDEO_BITRATE` applies to the live session, so the two
     * profiles cost nothing to switch between — which is the whole reason the
     * dash has two. Failure is logged and swallowed: a codec that will not
     * retarget is a stream at the wrong bitrate, not a stream that should stop.
     */
    fun requestBitrate(bps: Int) {
        val c = codec ?: return
        runCatching {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        }.onFailure { DebugLog.w(TAG) { "requestBitrate($bps) failed: ${it.message}" } }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release(); inputSurface = null
    }
}
