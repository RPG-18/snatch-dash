package com.opendash.opendash_dash_engine.dash.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics

/**
 * MediaCodec H.264 encoder for the Tripper Dash stream:
 *   526 x 300, 2-4 fps, ~200 kbps, Baseline L4.1, key frame every 8 frames.
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

        /**
         * `KEY_I_FRAME_INTERVAL`, in seconds. Raised from 1 to 2 on 2026-09-09.
         *
         * **It is not really seconds.** MediaFormat's own javadoc: "Most video encoders will
         * convert this value to the number of non-key-frames between key-frames, using the
         * frame rate information; therefore, if the actual frame rate differs, the time
         * interval between key frames will not be the configured value." With [FPS] = 4
         * declared, this is a GOP of **8 frames** — and the frame loop feeds 4 fps moving but
         * 2 fps when the map is idle, so the wall-clock interval is 2 s in motion and 4 s at
         * rest. The 2026-09-09 logs show exactly that arithmetic at the old value of 1:
         * `frames=239 (idr=60)` moving (a GOP of 4 frames = 1 s) and `frames=122 (idr=31)`
         * idle (the same 4 frames = 2 s).
         *
         * Why raise it: at 4 fps an interval of 1 s made every fourth frame an IDR, and one
         * of them measured 39.7 KB — some 2.4 MB of a 4.2 MB minute, more than half the
         * stream, spent on key frames.
         *
         * The cost is recovery time. RTP goes out over UDP with no retransmission, so a lost
         * key-frame packet leaves the dash showing garbage until the next one. For contrast,
         * scrcpy — which streams over TCP, where nothing is lost — uses 10 s.
         */
        private const val IDR_INTERVAL_S = 2
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val TAG = "DashEncoder"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    /** One line per stream is the point; the format can change more than once. */
    private var loggedNegotiatedFormat = false

    fun prepare() {
        // Hardware if the device has one that takes these frames, otherwise whatever does
        // — see [selectEncoder]. Null only if nothing in the list qualifies, and then the
        // OS default is the last thing left to try.
        val name = selectEncoder()
        val c = if (name != null) MediaCodec.createByCodecName(name)
                else MediaCodec.createEncoderByType(MIME)
        // Asked of THIS codec, after it exists: an encoder that does not support CBR is
        // entitled to refuse the key in configure(), and the fallback path may well have
        // handed us one — so the question goes to the instance we are about to configure.
        //
        // A `false` here does not mean the hardware cannot do CBR. `EncoderCapabilities`
        // starts at `mBitControl = 1 << BITRATE_MODE_VBR` and only widens if the device's
        // `media_codecs.xml` declares `<Feature name="bitrate-modes" …>` — many OEM files
        // simply do not, for encoders that handle CBR perfectly well. Leaving the key unset
        // in that case is the conservative half of the trade: we lose the cap rather than
        // risk configure() throwing on a phone nobody can test.
        val caps = runCatching { c.codecInfo.getCapabilitiesForType(MIME) }.getOrNull()
        val range = caps?.videoCapabilities?.bitrateRange
        val declaresCbr = caps?.encoderCapabilities
            ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        // Asked for whenever the device declares it — including when [BITRATE] sits under the
        // encoder's declared floor, which is the case on the phone this was developed against
        // (`OMX.hisi.video.encoder.avc`, declared `280000-100000000`).
        //
        // **That is a deliberate experiment, opened 2026-09-09.** CBR means "hold this
        // number", so a target below the floor asks the encoder to hold something it says it
        // cannot, and the way that can go wrong is specific: the parked stream — measured at
        // 52-58 kbps — gets pinned up at the floor instead, permanently above the dash's own
        // profile (q2g = 204800), with [BITRATE_IDLE] no longer able to bring it down. The
        // reason for trying anyway is that the same rides measured 164-205 kbps on a plain
        // map, i.e. this encoder already runs well under the floor it advertises, so the
        // declaration looks conservative rather than binding.
        //
        // What ends the experiment, either way, is the `[stream]` line: idle back near
        // 50-60 kbps and moving near 200 means CBR aimed; idle stuck at ~280 means the floor
        // is real, and then the choice is to restore the `range.lower <= BITRATE_IDLE` guard
        // or to raise [BITRATE] to the floor and accept 1.37x over the dash's profile.
        val belowFloor = range != null && range.lower > BITRATE
        val cbr = declaresCbr
        val format = videoFormat(cbr)
        // Assigned only once the codec is running. Everything from configure() on can
        // throw, and until this line the instance has no other owner: `codec` would stay
        // null, the caller's `encoder?.release()` would find nothing, and a component
        // instance — a globally limited resource — would sit occupied until the finalizer
        // runs, which MediaCodec.release()'s own javadoc asks callers not to wait for.
        // One per failed connection attempt, and a rider retries "Send to Dash".
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
        } catch (e: Throwable) {
            // Read BEFORE the release below: MediaCodec.getName() throws IllegalStateException
            // on a released codec, and that throw would replace the configure() failure we are
            // trying to report — losing the real cause to a diagnostic line about it.
            val name = runCatching { c.name }.getOrDefault("<unnamed>")
            // Both documented failures of configure() — IllegalArgumentException for an
            // unacceptable format, CodecException for a codec error — plus whatever
            // createInputSurface/start add, are the same situation here: this codec never
            // became ours. The surface goes with it; releasing the codec invalidates it.
            runCatching { inputSurface?.release() }
            inputSurface = null
            runCatching { c.release() }
            // prepare() throws from here into startStream, which reports only the message.
            // "startStream failed: IllegalArgumentException" with no codec name is a report
            // nobody can act on.
            RideDiagnostics.warn("stream", "encoder $name failed to configure: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        codec = c
        // Into the ride file, not just DebugLog: these three facts are what the `[stream]`
        // kbps line has to be read against, and on a release build DebugLog writes nothing.
        // Without them, "575 kbps against a 200 kbps target" is a mystery rather than a
        // measurement of a codec that ignored the target.
        // The declared bitrate range comes along because it is the number that decides
        // whether the target is even askable: this phone's OMX.hisi.video.encoder.avc
        // declares `280000-100000000`, i.e. its own floor sits 40% ABOVE [BITRATE] — which
        // is not a number we are free to raise, being the dash's own profile. A stream that
        // settles near 280 rather than 200 is that floor, not a bug in the request.
        RideDiagnostics.log(
            "stream",
            "encoder ${c.name} — target ${BITRATE / 1000}kbps " +
                (when {
                    cbr && belowFloor -> "CBR below the encoder's declared floor (experiment)"
                    cbr -> "CBR"
                    else -> "in the encoder's own mode (CBR not declared)"
                }) +
                (range?.let { ", encoder declares ${it.lower / 1000}-${it.upper / 1000}kbps" } ?: "") +
                ", IDR every ${IDR_INTERVAL_S * FPS} frames (${IDR_INTERVAL_S}s at ${FPS}fps)",
        )
    }

    /**
     * The stream as the dash's decoder expects it, plus how strictly we intend to hold the
     * bitrate.
     *
     * **[MediaFormat.KEY_BITRATE_MODE] is the point of the [cbr] parameter.** Without that
     * key the encoder picks its own default, and on this hardware that default tracked scene
     * complexity rather than the target: the 2026-09-09 ride logged 52-58 kbps standing
     * still, 164-205 on a plain map and **568-575 on a dense one** — 2.8x over [BITRATE],
     * which is not a number we chose but the dash's own high profile (`q2g = 204800`). The
     * comment on [BITRATE] records what overrunning the dash decoder looks like from here:
     * a frozen picture on a dash that still answers telemetry.
     *
     * CBR trades the other way — on a dense map it spends the same bits and lets detail
     * soften. That is the intended trade for a 526x300 panel read at a glance, but it is a
     * judgement about legibility, so the mode is recorded in the ride file next to the kbps
     * it produces.
     */
    private fun videoFormat(cbr: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            if (cbr) {
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IDR_INTERVAL_S)
            // "0: realtime priority — meaning that the codec shall support the given
            // performance configuration at realtime" (MediaFormat javadoc). A hint used for
            // resource planning, not a guarantee — but this stream IS realtime: a frame that
            // misses its slot is not late, it is a dash showing the previous one. The default
            // is best-effort, which is the wrong thing to tell the codec about a ride.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // Encoder latency in FRAMES: "if encoder supports it, it should output at least
            // one output frame after being queued the specified number of frames". At 4 fps a
            // codec that buffers even two frames adds half a second between the map the
            // overlays were drawn against and the picture on the dash. Ignored where the
            // encoder has no such feature, which is why nothing checks it.
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41)
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
        // Why a hardware candidate was turned down, for the line below. Software ones are
        // not collected: nobody is going to investigate those.
        val hardwareRejected = mutableListOf<String>()
        val usable = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            if (!info.isEncoder) return@filter false
            if (!info.supportedTypes.any { it.equals(MIME, true) }) return@filter false
            // Both flags are API 29, which is the project floor — before it was raised this
            // preference was dead code on 26-28, and every one of those devices silently took
            // whatever the OS handed it.
            val hardware = info.isHardwareAccelerated && !info.isSoftwareOnly
            fun reject(why: String): Boolean {
                DebugLog.i(TAG) { "${info.name}: $why — skipping" }
                if (hardware) hardwareRejected += "${info.name} ($why)"
                return false
            }
            val caps = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull()
                ?: return@filter reject("capabilities unreadable")
            val video = caps.videoCapabilities ?: return@filter reject("no video capabilities")
            when {
                !video.supportedWidths.contains(WIDTH) || !video.supportedHeights.contains(HEIGHT) ->
                    reject("${WIDTH}x$HEIGHT out of its range")
                !caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) ->
                    reject("no COLOR_FormatSurface")
                else -> true
            }
        }
        val hardware = usable.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly }
        // A ride encoded on the CPU is the outcome this selection exists to avoid, and it is
        // otherwise indistinguishable from a normal one — the name in the line below is the
        // only trace, and the REASON lives in DebugLog, which a release build drops. Said
        // once here, where a post-mortem will find it, and only when it actually happened.
        if (hardware == null && hardwareRejected.isNotEmpty()) {
            RideDiagnostics.warn(
                "stream",
                "no hardware encoder qualified, falling back to software — ${hardwareRejected.joinToString("; ")}",
            )
        }
        return hardware?.name ?: usable.firstOrNull()?.name
    }

    /**
     * What the encoder actually accepted, once per stream.
     *
     * `KEY_PRIORITY`, `KEY_LATENCY` and `KEY_BITRATE_MODE` are hints: nothing in
     * `CodecCapabilities` says whether a given encoder honours them, and the device's
     * `media_codecs.xml` declares only `bitrate-modes` — not latency, not priority. The
     * documented way to find out is this one: `MediaFormat.KEY_LATENCY`'s javadoc says
     * "use the output format to verify that this feature was enabled and the actual value
     * used by the encoder". So we ask after the fact, on whatever phone the rider owns,
     * and put the answer where a release build can still be read.
     *
     * A key missing from the echo means the encoder did not adopt it — which is legal and
     * silent, and exactly the thing that would otherwise be argued about from the couch.
     */
    private fun logNegotiatedFormat() {
        if (loggedNegotiatedFormat) return
        // After the read, not before: a first call that finds no output format yet would
        // otherwise silence the echo for the rest of the stream.
        val out = runCatching { codec?.outputFormat }.getOrNull() ?: return
        loggedNegotiatedFormat = true
        fun echo(key: String): String =
            runCatching { if (out.containsKey(key)) out.getNumber(key).toString() else "—" }
                .getOrDefault("?")
        RideDiagnostics.log(
            "stream",
            "encoder echoed: bitrate=${echo(MediaFormat.KEY_BIT_RATE)} " +
                "bitrate-mode=${echo(MediaFormat.KEY_BITRATE_MODE)} " +
                "i-frame-interval=${echo(MediaFormat.KEY_I_FRAME_INTERVAL)} " +
                "latency=${echo(MediaFormat.KEY_LATENCY)} " +
                "priority=${echo(MediaFormat.KEY_PRIORITY)} " +
                "(— = not adopted)",
        )
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
                // SPS/PPS still come as a CODEC_CONFIG buffer, not from here — this is only
                // where the encoder finally says what it made of the format we asked for.
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logNegotiatedFormat()
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
