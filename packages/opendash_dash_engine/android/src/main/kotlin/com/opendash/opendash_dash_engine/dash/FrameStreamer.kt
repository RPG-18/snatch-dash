package com.opendash.opendash_dash_engine.dash

import android.graphics.Canvas
import com.opendash.opendash_dash_engine.dash.map.Percentiles
import com.opendash.opendash_dash_engine.dash.video.DashEncoder
import com.opendash.opendash_dash_engine.dash.video.FrameEncoder
import com.opendash.opendash_dash_engine.dash.video.NalProcessor
import com.opendash.opendash_dash_engine.dash.video.RtpPacketizer
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where a frame comes from, as the loop needs it: advance, draw, describe.
 *
 * Implemented for real by [com.opendash.opendash_dash_engine.dash.map.MapFrameRenderer],
 * which owns MapLibre, the overlays, the camera and the frame bitmap. The split is what
 * makes [FrameStreamer] testable at all: everything below this line needs a device, and
 * everything above it — pacing, drop accounting, the RTP timestamp — does not.
 */
internal interface FrameSource {
    /**
     * Whether the map is going somewhere, i.e. 4 fps rather than 2.
     *
     * Read by the loop BEFORE [advance], so a frame is paced by the verdict of the previous
     * tick. That is deliberate and predates this class: the interval is also the frame's
     * render budget, and [advance] reports against it, so it has to be known first. The cost
     * is that a stopped/started transition uses the old verdict for one frame — invisible
     * next to the camera's own 350 ms smoothing.
     */
    val moving: Boolean

    /**
     * Advance the camera, and redraw the frame if anything visible changed.
     *
     * @param budgetMs this frame's interval — the snapshot deadline is measured against it.
     * @return true when a complete frame exists to encode. False on the first frames of a
     *   stream, before any snapshot has landed: a fresh bitmap is transparent, and sending it
     *   put garbage on the dash for as long as the first (most expensive) snapshot took.
     */
    suspend fun advance(budgetMs: Long): Boolean

    /** Paint the current frame into the encoder's canvas. */
    fun drawInto(canvas: Canvas)

    /** One encoded frame's timings, for the periodic `[map]` line. */
    fun frameEncoded(intervalMs: Long, encodeMs: Long, intendedIntervalMs: Long)

    /**
     * Frame-rate flips since the last call — `fpsFlips=` in the `[stream]` line.
     *
     * Drained by the loop rather than by the source's own window, because the two windows
     * differ (60 s against 30 s) and the count belongs to whichever line actually prints it.
     */
    fun drainFpsFlips(): Int

    /** The `[map]` line for a window of [periodMs], and the start of a new window. */
    fun drainWindowLog(periodMs: Long): String

    /**
     * Forget that the current frame was ever drawn, so the next tick redraws from scratch.
     *
     * Called after an encoder rebuild: the frames it would otherwise skip are the ones that
     * would have gone into a codec that no longer exists.
     */
    fun invalidate()

    /** The debug screen's frame plus its numbers, or null if there is nothing to send. */
    fun previewFrame(encodedBytes: Int, framesSent: Int, decoderOpens: Int, fps: Int): Map<String, Any?>?
}

/**
 * One stream: render → encode → packetize → socket, for as long as the session is STREAMING.
 *
 * Created by [com.opendash.opendash_dash_engine.DashEngineController.startStream] and dead
 * with it. Everything that used to make this loop untestable is now a constructor parameter:
 * the clock, the encoder, the source of frames, the socket, even "is the session still
 * streaming". What is left inside is the part that has to be right and never was checked —
 * the iteration deadline, the drop accounting, and the RTP timestamp rule that cost two
 * rides to learn (spec/video.md).
 *
 * @param clock monotonic milliseconds. A parameter with NO default, exactly as in
 *   [com.opendash.opendash_dash_engine.dash.map.PositionTrust]: a default of `::monotonicMs`
 *   drags `SystemClock` into a JVM test, and a default of `System::currentTimeMillis` is the
 *   wall-clock bug this project keeps re-learning (pipeline.md §4.1).
 * @param senderContext where the RTP sender runs: `StreamThreads.rtp` — a thread of this
 *   stream's own — in production, because `DatagramSocket.send` is a blocking syscall and
 *   blocking a shared pool thread is how the rest of the engine loses its workers. The
 *   `Dispatchers.IO` default is what a caller gets who arranges nothing; the tests pass the
 *   test scheduler.
 */
internal class FrameStreamer(
    private val source: FrameSource,
    private val encoderFactory: (onEncoded: (ByteArray, Boolean, Boolean) -> Unit) -> FrameEncoder,
    private val rtpSender: () -> ((ByteArray) -> Unit)?,
    private val streaming: () -> Boolean,
    private val previewSink: () -> ((Map<String, Any?>) -> Unit)?,
    private val decoderOpens: () -> Int,
    private val thermal: () -> String,
    private val clock: () -> Long,
    private val senderContext: CoroutineContext = Dispatchers.IO,
    /** One line naming the threads this stream runs on; null when nobody arranged any. */
    private val threadsReport: suspend () -> String? = { null },
    /**
     * Where the counterfactual probe sleeps — the shared pool in production, see [poolLateMax].
     *
     * A parameter for the same reason [clock] is: `Dispatchers.Default` inside `runTest` runs
     * on real threads and real time while everything else in the test runs on virtual time,
     * so the probe would both slow the suite down and compare two different clocks.
     */
    private val probeContext: CoroutineContext = Dispatchers.Default,
) {
    companion object {
        private const val TAG = "FrameStreamer"
        private const val FPS_MOVING = 4
        private const val FPS_IDLE = 2

        // How often the loop reports encoder/RTP output — the OTHER half of the
        // frame-decode-ack counter in DashSession: that one proves the dash decoded a frame,
        // this one proves we actually produced/sent one. Zero here while STREAMING means the
        // encoder itself stalled (this side); nonzero here but zero acks on the DashSession
        // side means frames leave the phone but the dash never confirms them — two different
        // bugs in two different files, previously indistinguishable without hex-grepping raw
        // TX dumps.
        private const val ENCODER_LOG_INTERVAL_MS = 60_000L

        // How often the render budget is summarised into the ride log. Frequent
        // enough to catch a stretch of the ride, rare enough that the sort behind
        // the percentiles is free.
        private const val RENDER_LOG_INTERVAL_MS = 30_000L

        // RTP_AU_SPREAD_MS (30 ms) lived here and is GONE as of 2026-09-11 — the reasoning
        // that produced it is kept in the sender loop below, together with the measurements
        // that overturned it. Short version: it spread a key frame's 17-18 datagrams over
        // 30 ms to keep any one of them from being lost in the burst, and the 2026-09-10 ride
        // made artifacts MORE frequent, not less, while showing the send path was never under
        // pressure to begin with.
    }

    // Written by [prepareEncoder] on the thread that starts the stream and read by the loop
    // on `dash-frame` (and rewritten there when it rebuilds a wedged encoder) —
    // @Volatile so neither side works off a cached reference. The encoder is released by the
    // loop itself, in its finally: nothing else can know that the last renderFrame/drain has
    // returned.
    @Volatile private var encoder: FrameEncoder? = null

    // Encoder/RTP output counters for the periodic health log — per stream, like everything
    // else in this object.
    private var framesEncoded = 0
    private var idrFramesEncoded = 0
    private val rtpPacketsSent = AtomicLong(0)

    // Bytes, not just packets. The packet count alone cannot answer the only
    // question that matters about this stream — are we inside the dash's own
    // ~200 kbps profile (see DashEncoder.BITRATE) — because a packet is
    // anywhere from a few bytes to 1392. On 2026-09-06 that left the measured
    // rate somewhere between 168 and 470 kbps, which is the difference between
    // "fine" and "twice the dash's profile", and nothing in the log could
    // narrow it.
    private val rtpBytesSent = AtomicLong(0)

    // Frame size and the datagram cost of a key frame — the two numbers the audit
    // (§4.6) asks for and the ride file has never carried: the only frame size
    // anywhere in it is "first video frame sent", once per session. They decide what
    // a rider sees after ONE lost datagram, because a key frame is undecodable
    // unless every one of its fragments arrives. Same [Percentiles] as the render
    // stats, drained into the same minute.
    private val frameBytes = Percentiles()
    private val idrDatagrams = Percentiles()

    // One access unit per element, not one packet. The original reason was pacing —
    // the sender had to know the burst size before its first packet — and the pacing
    // is gone as of 2026-09-11. The grouping stays because drop accounting needs it:
    // an overflow has to discard a WHOLE frame and know whether it was a key frame
    // ([rtpDroppedIdr]), and half an access unit on the wire is worth nothing to the
    // decoder anyway.
    //
    // FOUR, not the 64 this was first written with. A deep queue does not protect a
    // realtime stream, it hides the stall and converts it into latency: 64 AUs is
    // ~16 s of video at 4 fps, so a blocked socket would have kept `drop=0` in the
    // ride file while the dash showed a map from a quarter-minute ago — worse than a
    // gap, and invisible in exactly the file that exists to make it visible. One AU
    // is a few ms of airtime out of a 250 ms budget, so four is already a second
    // of slack for a GC pause; past that, dropping and SAYING so is the honest
    // failure. The render loop still never waits on the network — trySend, never send.
    private val rtpOutbox = Channel<List<ByteArray>>(capacity = 4)
    private val rtpDropped = AtomicInteger(0)

    /**
     * Dropped access units that were key frames, counted apart from [rtpDropped].
     *
     * The two cost different things and want different reactions. A dropped P-frame is
     * one frame the dash never sees — at 4 fps, a quarter second. A dropped key frame is
     * the whole GOP: every P-frame after it references a picture the decoder does not
     * have, so the dash shows garbage until the next IDR, which at a 2 s interval is
     * eight frames later. A single `drop=` number cannot tell "we lost a quarter second"
     * from "we lost two seconds and showed mush for them".
     */
    private val rtpDroppedIdr = AtomicInteger(0)

    /**
     * Gate 0 of pipeline.md: what each `drain()` actually yielded.
     *
     * [drainMiss] counts iterations where the frame just rendered did not come out within
     * DRAIN_TIMEOUT_US and was left for a later one; [drainDouble] counts iterations that
     * yielded two or more frames, which is the same event seen from the other end.
     *
     * **What they measured, and what they measure now.** Until 2026-09-15 a double meant
     * two access units went out with the SAME RTP timestamp, because the clock advanced
     * per iteration rather than per frame — and that was the one telemetry difference
     * between a Huawei whose map kept freezing (drainMiss median 22 a minute) and a Xiaomi
     * whose map did not (0 in 73 of 92 windows). Since the clock moved into `onEncoded`
     * the stamps are distinct and evenly spaced whatever the codec does, so these two now
     * report a property of the ENCODER and nothing about what reaches the dash:
     * `OMX.hisi.video.encoder.avc` misses 5-20% of its windows, `c2.mtk.avc.encoder`
     * almost none. Still worth watching — a rise means the codec is falling behind — but
     * a nonzero is no longer a defect in the stream.
     *
     * This existed BEFORE any rework of the encode path, on purpose, and that paid off:
     * the reading redirected the fix from pipeline.md's stage 6 (async MediaCodec, a
     * rewrite of the live encoder) to three lines in the callback. The three attempts
     * before it — RTP pacing, DSCP, a 256 KiB SO_SNDBUF — were reasoned rather than
     * measured, all wrong in the field, and the two that shipped made the picture worse.
     */
    private val drainMiss = AtomicInteger(0)
    private val drainDouble = AtomicInteger(0)

    /**
     * Gate for stage 6 of pipeline.md: how long after its deadline the loop actually woke up.
     *
     * Measured only on iterations that really slept — if the body overran the budget there
     * was no sleep to be late from, and counting the overrun here would report our own work
     * as scheduling delay, which is the one thing this number must not do.
     *
     * **What it does NOT measure, and the mistake that matters.** This counter shipped in the
     * same build as the dedicated threads it was meant to judge, so it can only ever describe
     * the NEW arrangement — it never saw `Dispatchers.Default` and cannot say what the shared
     * pool used to cost. Gate 0 worked precisely because it shipped one build EARLIER than the
     * change it gated; this one did not, and calling it a verdict on the pool would be reading
     * a number that was never taken. Found by review, 2026-09-16.
     *
     * What it does measure is still worth having: how late this loop wakes on its own thread.
     * Near zero means the current arrangement meets its deadline; tens of milliseconds mean
     * even a dedicated thread at `THREAD_PRIORITY_DISPLAY` is losing the CPU, which would be
     * a finding about the phone rather than about the dispatcher. The counterfactual — what
     * the shared pool would have done under the same conditions — is [poolLate].
     */
    private val wakeLate = Percentiles()

    /**
     * The same sleep, on `Dispatchers.Default`, for as long as this stream runs.
     *
     * A probe rather than an argument. §4.3 makes the case against the shared pool entirely
     * from documentation, and three earlier changes made from equally sound unmeasured
     * arguments — RTP pacing, DSCP, a 256 KiB SO_SNDBUF — were all wrong in the field, two of
     * them visibly. Since the gate above can no longer answer the question (it ships with the
     * cure), this samples the pool directly: every iteration that sleeps also asks a coroutine
     * on `Default` to sleep exactly as long, and records how late THAT one woke.
     *
     * Atomics, not [Percentiles]: this is the one counter written from another thread, and
     * `Percentiles` is not thread-safe — the same reason `rxGaps` is collected inside the RX
     * loop rather than in the logger. Max and a count past 50 ms rather than percentiles,
     * because the question is binary — was the pool ever late enough to miss a frame — and 50
     * ms is a fifth of the 4 fps budget.
     */
    private val poolLateMax = AtomicLong(0)
    private val poolLateOver50 = AtomicInteger(0)
    private val poolProbes = AtomicInteger(0)

    /**
     * Iterations whose body overran the budget, so there was no sleep to be late from.
     *
     * Printed next to [wakeLate] because without it an empty sample reads as a calm window:
     * a stretch where every single frame overran would print `wakeLate=-ms`, which looks
     * exactly like a stretch where nothing ever went wrong. Found by review, 2026-09-16.
     */
    private val overruns = AtomicInteger(0)

    // Filled by the packetizer callback during one nalProc.process() call, then handed
    // to the outbox as a unit. Only ever touched from the frame-loop coroutine.
    private val auPackets = ArrayList<ByteArray>(32)

    /** Size the encoder produced for the most recent frame — one of the debug screen's numbers. */
    private var lastEncodedBytes = 0

    // Which profile the encoder is currently on, so it is poked only on a
    // transition rather than every frame.
    private var idleBitrate = false

    // One-shot timing, paired with DashSession's own "dash DECODED first IDR" line — the
    // gap between the two is exactly the dash's own decode latency for this session. Ported
    // idea from OpenMotoDash/NorthStar's `loggedFirstFrame` (see
    // spec/wifi_retry_policy.md's "Из живого форка").
    private var loggedFirstFrame = false

    // Monotonic RTP presentation clock — advanced by the INTENDED frame interval (see
    // [onEncoded]), NOT System.currentTimeMillis(). Ported from OpenMotoDash/NorthStar's
    // `videoPtsMs` (see spec/wifi_retry_policy.md's "Из живого форка" for how that fork was
    // found). Originally motivated by a 2026-08-29 field session that read DashSession's
    // ACK counter sitting at 0 for 66 of 67 sampled minutes as "dash stopped decoding video"
    // — a reading a LATER 2026-08-29 field report (map updated fine that whole ride)
    // falsified; see spec/video.md's "09 06/04 55 — НЕ ack на каждый кадр" for the
    // correction. Keeping this change anyway: a monotonic PTS instead of one carrying
    // render/encode/GC jitter is more correct RTP practice regardless.
    private var videoPtsMs = 0L

    /**
     * How far [videoPtsMs] moves per FRAME — set by the loop, consumed by [onEncoded].
     *
     * It lives out here because the callback runs inside `enc.drain()` and cannot see the
     * loop's `frameIntervalMs`. Plain `var` for the same reason [videoPtsMs] is: `drain()`
     * invokes the callback synchronously on the frame loop's own coroutine, so both are
     * single-threaded.
     *
     * A frame drained late therefore carries the step in force when it came OUT, not when
     * it was rendered. Across an fps flip that shifts one frame's spacing once; the old
     * scheme mis-stamped that same frame more coarsely, and neither is worth carrying a
     * per-frame step around for.
     */
    private var ptsStepMs = 1000L / FPS_IDLE

    // Collects rather than sends. `DatagramSocket.send` is a syscall that blocks when the
    // Wi-Fi driver's queue is full, and the frame loop calling it inline put the render
    // deadline at the mercy of the radio (audit §4.1). The queue is what decouples them;
    // since 2026-09-16 the sender also has a thread of its own to block on.
    private val packetizer = RtpPacketizer { rtpPkt -> auPackets += rtpPkt }

    // endOfAU comes from NalProcessor, which knows which NAL closes the access unit —
    // this used to be hardcoded `true`, marking every packet. Harmless while each AU
    // was exactly one NAL, but wrong the moment an IDR goes out as separate
    // SPS/PPS/IDR packets: the marker bit has to land on the last one only.
    private val nalProc = NalProcessor { nal, endOfAU ->
        packetizer.packetize(nal, endOfAU = endOfAU, ptsMs = videoPtsMs)
    }

    private val onEncoded: (ByteArray, Boolean, Boolean) -> Unit = { annexB, isKey, isConfig ->
        // The SPS/PPS buffer goes to the packetizer like any other but is not a
        // frame: counting it would put 30 bytes of parameter sets in front of
        // anyone reading "size of the last frame", once per session, at exactly
        // the moment they start looking.
        if (!isConfig) {
            // Advanced HERE, per frame that actually came out of the codec, and not once
            // per loop iteration as it was until 2026-09-15. Before `nalProc.process`
            // below, which runs the packetizer synchronously and reads [videoPtsMs].
            //
            // The defect this fixes, measured on the ride of that date: when the encoder
            // misses its DRAIN_TIMEOUT_US window the frame comes out on the NEXT
            // iteration — by which time the old code had already advanced the clock — so
            // it carried that iteration's stamp, and if the next frame made it too, two
            // access units went out stamped identically. On the Huawei's
            // OMX.hisi.video.encoder.avc that was 5-20% of frames (drainMiss median 22 a
            // minute); on the Xiaomi's c2.mtk.avc.encoder it was 0 in 73 of 92 windows.
            // The Huawei is also the phone whose map kept freezing while the Xiaomi's kept
            // updating, with drop=, wedged=, timeouts= and link losses identical and clean
            // on both — this was the only telemetry difference between them.
            //
            // Counting frames instead of iterations makes the stamps monotonic and evenly
            // spaced by construction, which is the property videoPtsMs was introduced for
            // and did not actually have. The misses themselves remain; they belong to the
            // encoder, and RFC 6184's 90 kHz clock does not care when a frame was handed
            // over, only that distinct frames carry distinct instants.
            videoPtsMs += ptsStepMs
            framesEncoded++
            lastEncodedBytes = annexB.size
            frameBytes.add(annexB.size.toLong())
            if (isKey) idrFramesEncoded++
        }
        if (!loggedFirstFrame && !isConfig) {
            loggedFirstFrame = true
            RideDiagnostics.log("stream", "first video frame sent (key=$isKey, ${annexB.size}B)")
        }
        // process() runs the packetizer synchronously on this coroutine, so when it
        // returns [auPackets] holds exactly this access unit — which is both the
        // number the ride file wants and the group the sender has to pace as one.
        auPackets.clear()
        nalProc.process(annexB)
        if (auPackets.isNotEmpty()) {
            if (isKey && !isConfig) idrDatagrams.add(auPackets.size.toLong())
            // Never `send`: this runs inside the render loop, and a full outbox must
            // cost a dropped frame, not a late one. A codec-config buffer emits no
            // packets at all (SPS/PPS are cached, not sent), hence the guard.
            if (rtpOutbox.trySend(auPackets.toList()).isFailure) {
                rtpDropped.incrementAndGet()
                if (isKey && !isConfig) rtpDroppedIdr.incrementAndGet()
            }
        }
    }

    /**
     * Build the encoder for this stream, on the caller's thread.
     *
     * Separate from [run] so it keeps happening where it always did — inside
     * `startStream`, where a throw is caught and turns into a disconnect. Moving it into
     * the loop would put that failure inside a launched job, where the retry path cannot
     * see it.
     */
    fun prepareEncoder() {
        encoder = encoderFactory(onEncoded)
    }

    /** Undo [prepareEncoder] when the stream never got as far as [run]. */
    fun releaseEncoder() {
        runCatching { encoder?.release() }
        encoder = null
    }

    /**
     * The stream itself. Returns when the session stops streaming or the caller cancels.
     */
    suspend fun run() = coroutineScope {
        // Before anything else, and once per stream: which threads this loop and its sender
        // actually got, and at what priority. Asking for THREAD_PRIORITY_DISPLAY is not the
        // same as getting it (see StreamThreads.report), and a refusal that says nothing is
        // a change that cannot be told from a working one.
        threadsReport()?.let { RideDiagnostics.log("stream", it) }
        // A child of this scope, so cancelling the stream cancels it — no separate
        // teardown path, which is the property the rest of this file keeps paying for.
        launch(senderContext) {
            // Claimed once, for the life of this stream. session.rtpSender() captures the
            // socket and checks it is still the current one on every packet — the identity
            // guard the control senders have had all along and this path did not, see
            // DashSession.rtpSender. Null means there is no socket to stream over, which
            // is not an error worth a teardown: the frame loop's own `streaming()`
            // condition ends things a moment later.
            val sendRtp = rtpSender() ?: return@launch
            for (au in rtpOutbox) {
                // Back to back, on purpose. This loop used to pace the packets of one
                // access unit across `min(30 ms, frameInterval / 4)`; the pacing was
                // REMOVED on 2026-09-11 because the ride of 2026-09-10 21:00 says it
                // made the picture worse, and the numbers say it was never needed.
                //
                // What it was for: keeping an 18-datagram key frame from overrunning
                // the send path. That premise is now measured and false. The same ride
                // printed `sndbuf=4096KiB` — the platform default on this phone is 4 MiB,
                // twenty times the stock-Linux figure the plan assumed — over a 65 Mbps
                // link carrying 200 kbps, and `drop=0 dropIdr=0` in all twenty windows.
                // Nothing was ever congested here.
                //
                // What it cost: at 65 Mbps those 18 datagrams are about 3 ms of airtime
                // sent as ONE A-MPDU — a single contention, a block ACK, and the driver
                // retransmitting just the missing subframes inside the same TXOP.
                // Stretching them over 30 ms is ten times longer than the burst needs
                // and hands the radio 18 separate transmissions instead, each
                // contending on its own in a 2.4 GHz band shared with a city. Losing
                // one datagram of a key frame costs a whole GOP — eight frames, two
                // seconds of mush — so trading aggregation for spacing is the wrong
                // way round for this stream.
                //
                // The queue above stays: it is what turns a genuine stall into a
                // counted drop instead of latency, and `drop=` is how we would find
                // out if the premise above ever stops being true.
                for (pkt in au) {
                    // The cancellation check the `delay()` used to provide for free.
                    // Iterating the channel suspends, so cancellation is seen BETWEEN
                    // access units either way — but without a suspension point inside
                    // this loop an AU that had already started would run to completion
                    // after `rtpOutbox.cancel()`. That is the leak `cancel()` rather
                    // than `close()` exists to prevent, and dropping the pacing must not
                    // quietly hand it back.
                    //
                    // It is now belt and braces rather than the only guard: [sendRtp] is
                    // bound to this stream's socket and refuses a stale one. Both stay —
                    // this one stops the work, that one stops the packet.
                    ensureActive()
                    sendRtp(pkt)
                    rtpPacketsSent.incrementAndGet()
                    rtpBytesSent.addAndGet(pkt.size.toLong())
                }
            }
        }
        // Kept outside the try so the finally can reach it: the probes are children of this
        // scope, and without cancelling the last one the stream's teardown would wait out a
        // sleep whose answer nobody will read.
        var probeJob: Job? = null
        try {
            var failures = 0
            var lastEncoderLogAt = clock()
            var lastRenderLogAt = lastEncoderLogAt
            var lastFrameSentAt = 0L
            var loggedFrames = 0; var loggedIdr = 0; var loggedRtp = 0L; var loggedBytes = 0L
            // Declared outside the try/catch and assigned fresh once per iteration, so the
            // trailing delay() below can reuse the SAME value the PTS advance used — both must
            // agree on "how long is this frame", or the RTP timeline and the actual send
            // cadence drift apart from each other. Starts at the conservative (idle)
            // interval; only matters for a hypothetical exception inside [FrameSource.advance]
            // itself, before the real value below gets assigned.
            var frameIntervalMs = 1000L / FPS_IDLE
            while (isActive && streaming()) {
                // Top of the iteration, so the trailing delay() can pace to a DEADLINE
                // rather than sleep a whole interval on top of the work: the body waits
                // for a snapshot (up to the interval itself), and adding the full
                // interval after it made the real period `interval + latency`. At 4 fps
                // with a 100 ms snapshot that is ~2.9 fps, and it sagged exactly under
                // the load that makes snapshots slow. The spec asks for
                // `max(interval, latency)` (drawing_from_local_tiles.md, «Цикл ждёт
                // снапшот»), which is what the deadline gives.
                val iterationStartMs = clock()
                try {
                    frameIntervalMs = 1000L / (if (source.moving) FPS_MOVING else FPS_IDLE)
                    val haveFrame = source.advance(frameIntervalMs)
                    val enc = encoder
                    if (haveFrame && enc != null) {
                        // Match the encoder's target to the dash's own two profiles,
                        // on the transition only. [FrameSource.moving] is already what picks
                        // the frame rate, so reusing it keeps one notion of "the map
                        // is going somewhere" instead of introducing a second that
                        // could disagree with the first.
                        if (source.moving && idleBitrate) {
                            enc.requestBitrate(DashEncoder.BITRATE)
                            idleBitrate = false
                        } else if (!source.moving && !idleBitrate) {
                            enc.requestBitrate(DashEncoder.BITRATE_IDLE)
                            idleBitrate = true
                        }
                        val encodeStart = clock()
                        enc.renderFrame { canvas -> source.drawInto(canvas) }
                        // Publish the step; the clock itself advances per drained frame
                        // inside [onEncoded]. Advancing it here was what let two frames
                        // pulled in one drain share a timestamp — see that callback.
                        ptsStepMs = frameIntervalMs
                        when (enc.drain()) {
                            0 -> drainMiss.incrementAndGet()
                            1 -> Unit
                            else -> drainDouble.incrementAndGet()
                        }
                        val sentAt = clock()
                        source.frameEncoded(
                            intervalMs = if (lastFrameSentAt == 0L) 0L else sentAt - lastFrameSentAt,
                            encodeMs = sentAt - encodeStart,
                            intendedIntervalMs = frameIntervalMs,
                        )
                        lastFrameSentAt = sentAt
                        emitFramePreview(frameIntervalMs)
                    }
                    failures = 0
                    val now = clock()
                    if (now - lastRenderLogAt > RENDER_LOG_INTERVAL_MS) {
                        val elapsed = now - lastRenderLogAt
                        lastRenderLogAt = now
                        RideDiagnostics.log("map", source.drainWindowLog(elapsed))
                    }
                    if (now - lastEncoderLogAt > ENCODER_LOG_INTERVAL_MS) {
                        val nowRtp = rtpPacketsSent.get()
                        val nowBytes = rtpBytesSent.get()
                        val dFrames = framesEncoded - loggedFrames
                        val dIdr = idrFramesEncoded - loggedIdr
                        val dRtp = nowRtp - loggedRtp
                        val dBytes = nowBytes - loggedBytes
                        val dFlips = source.drainFpsFlips()
                        loggedFrames = framesEncoded; loggedIdr = idrFramesEncoded; loggedRtp = nowRtp
                        loggedBytes = nowBytes
                        lastEncoderLogAt = now
                        val intervalS = ENCODER_LOG_INTERVAL_MS / 1_000
                        val thermalLabel = thermal()
                        // Into the ride file, not just app_log.txt. This is the
                        // only number that says whether anything reached the
                        // socket, and a frozen-map report is exactly when it is
                        // wanted — but app_log.txt is a ring buffer that a long
                        // ride overwrites, so on 2026-09-06 the question "did the
                        // stream keep flowing" had no answer left by the time the
                        // phone was back on the cable.
                        if (dFrames == 0) {
                            // Both files, one call: the ride file is where a post-mortem
                            // starts, and app_log/`/more/logs` need this at W or their
                            // level filters stop surfacing it. That pair used to be
                            // written by hand here — see RideDiagnostics.warn.
                            RideDiagnostics.warn(
                                "stream",
                                "encoder output: 0 frames in the last ${intervalS}s while STREAMING " +
                                    "— render/encode loop itself stalled (nothing to even send) — " +
                                    // fpsFlips, and only it: [dFlips] is drained above the
                                    // branch, so a stalled window that does not print it
                                    // loses the count rather than saving it for the next
                                    // one — and a window where the loop runs without
                                    // encoding is exactly when an oscillating rate policy
                                    // is worth seeing. The drop counters are NOT here on
                                    // purpose: no encoded frame means no trySend, so they
                                    // are structurally zero, and printing zeroes would
                                    // imply the queue was examined when it was not.
                                    //
                                    // The drain counters ARE here, and unlike the drop
                                    // counters they say something this branch cannot say
                                    // otherwise: drainMiss > 0 means the loop kept
                                    // iterating and the encoder gave back nothing.
                                    //
                                    // The converse does NOT hold, and the first draft of
                                    // this comment claimed it did: drain() is reached only
                                    // when the source has a frame, so a loop spinning
                                    // without a snapshot — the commonest stall — also
                                    // prints drainMiss=0. Read it with `[map]`'s
                                    // blank=/timeouts= from the same minute, which say
                                    // whether frames were being produced at all. Review
                                    // caught the overclaim.
                                    //
                                    // They also have to be reset here either way, or the
                                    // stalled window's count leaks into the next one.
                                    "drainMiss=${drainMiss.getAndSet(0)} " +
                                    "drainDouble=${drainDouble.getAndSet(0)} " +
                                    "wakeLate=${wakeLate.drain()}ms " +
                                    "overrun=${overruns.getAndSet(0)} " +
                                    "poolLate=${drainPoolLate()} " +
                                    "fpsFlips=$dFlips thermal=$thermalLabel",
                            )
                        } else {
                            RideDiagnostics.log(
                                "stream",
                                "frames=$dFrames (idr=$dIdr) rtp=$dRtp ${dBytes / 1024}KiB " +
                                    "${dBytes * 8 / 1000 / intervalS}kbps " +
                                    "frame=${frameBytes.drain()}B " +
                                    "idrPkts=${idrDatagrams.drain()} " +
                                    "idrShape=${nalProc.drainIdrShapes()} " +
                                    "drop=${rtpDropped.getAndSet(0)} " +
                                    "dropIdr=${rtpDroppedIdr.getAndSet(0)} " +
                                    "drainMiss=${drainMiss.getAndSet(0)} " +
                                    "drainDouble=${drainDouble.getAndSet(0)} " +
                                    "wakeLate=${wakeLate.drain()}ms " +
                                    "overrun=${overruns.getAndSet(0)} " +
                                    "poolLate=${drainPoolLate()} " +
                                    "fpsFlips=$dFlips " +
                                    "bitrate=${if (idleBitrate) "idle" else "moving"} " +
                                    "thermal=$thermalLabel in the last ${intervalS}s",
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    DebugLog.e(TAG, { "Frame loop error #$failures" }, e)
                    if (failures >= 3) {
                        runCatching { encoder?.release() }
                        encoder = runCatching { encoderFactory(onEncoded) }
                            .onFailure { DebugLog.e(TAG, { "Encoder rebuild failed" }, it) }
                            .getOrNull()
                        if (encoder == null) {
                            // Stop, rather than spin. With no encoder there is no
                            // renderFrame and no drain, so nothing can throw, so this branch
                            // can never be reached again — the loop would keep taking
                            // snapshots at 2-4 fps for the rest of the ride and encode none
                            // of them, while the session sits in STREAMING with its give-up
                            // timer already cancelled. That is a frozen dash whose only
                            // trace was a per-minute "0 frames" warn with no cause in it,
                            // and on a release build DebugLog above writes nowhere at all.
                            //
                            // Ending the loop does not recover the ride either — recovery
                            // belongs to the connection FSM, which does not exist yet
                            // (network-refactoring.md, этап 6). What it does is make the
                            // cause survive to the ride file and stop paying MapLibre for
                            // frames nobody will ever encode. Found by review, 2026-09-16.
                            RideDiagnostics.warn(
                                "stream",
                                "encoder rebuild failed after 3 frame errors — ending the " +
                                    "stream; the dash keeps its last frame until reconnect",
                            )
                            return@coroutineScope
                        }
                        // A fresh encoder is configured at DashEncoder.BITRATE, i.e. the
                        // moving profile, whatever the old one was last told. Without
                        // this the flag can claim "idle" over a codec running at the
                        // moving target, and since requestBitrate only fires on a
                        // TRANSITION, nothing corrects it until the rider stops and
                        // starts again — a rebuild while parked would stream at the
                        // moving rate for as long as the bike stands still.
                        idleBitrate = false
                        source.invalidate()
                        failures = 0
                    }
                }
                // Whatever is left of this frame's budget. Overrunning it is not made up
                // for by shortening the next frame: the loop falls behind by the overrun
                // and shows up as `frames=X/expected` plus `late=` in the render log,
                // which is the honest reading — videoPtsMs advances by the nominal
                // interval, and that stays truthful for as long as the budget is kept.
                val remainingMs = frameIntervalMs - (clock() - iterationStartMs)
                if (remainingMs > 0) {
                    // The counterfactual, started before our own sleep so both wait through
                    // the same stretch of wall time under the same system load — see
                    // [poolLateMax]. It is a child of this scope, so it dies with the stream.
                    //
                    // The deadline is computed HERE, at the launch site, not inside the
                    // coroutine. Inside, it would start counting only once the pool had
                    // already found a thread for it — so a saturated pool, the single
                    // condition this probe exists to catch, would have been excluded from
                    // its own measurement. Found by review, 2026-09-18; the numbers of the
                    // 18.09 rides were taken with the narrower definition and read low by
                    // exactly the dispatch time (pipeline.md §0.8).
                    val probeTarget = clock() + remainingMs
                    probeJob = launch(probeContext) {
                        val target = probeTarget
                        delay(remainingMs)
                        val late = (clock() - target).coerceAtLeast(0L)
                        poolProbes.incrementAndGet()
                        if (late > 50) poolLateOver50.incrementAndGet()
                        poolLateMax.updateAndGet { maxOf(it, late) }
                    }
                    delay(remainingMs)
                    wakeLate.add((clock() - (iterationStartMs + frameIntervalMs)).coerceAtLeast(0L))
                } else {
                    overruns.incrementAndGet()
                    // Still a suspension point: without one, a loop that never fits its budget
                    // would never give the dispatcher a chance to deliver cancellation.
                    delay(0L)
                }
            }
        } finally {
            probeJob?.cancel()
            // The loop is the last thing that draws into this encoder, so it is the only
            // place that can free it without racing renderFrame/drain — see the note in
            // DashEngineController.disconnect. Runs on cancellation too (nothing here
            // suspends), which is what makes "cancel the loop" a complete teardown on its own.
            runCatching { encoder?.release() }
            encoder = null
            // cancel(), NOT close(). close() lets the sender drain what is queued.
            // That used to be the only thing standing between a dead stream and the next
            // session's socket, because `DashSession.sendRtp` wrote to whatever `socket`
            // was live at that moment; since 2026-09-14 the sender is bound to this
            // stream's socket and refuses a stale one (DashSession.rtpSender), so this is
            // now the outer of two guards rather than the sole one. It still earns its
            // place: refusing the packet at the socket still leaves the sender doing the
            // work of an ended stream.
            // On the Wi-Fi-loss path this loop exits on its own rather than
            // being cancelled, so a drained queue could put the dead stream's packets,
            // carrying the old packetizer's SSRC and sequence numbers, onto the NEXT
            // session's freshly bound socket. Frames from a stream that has ended are
            // stale by definition; there is nothing here worth delivering.
            rtpOutbox.cancel()
        }
    }

    /** `max/over50/n` for the window, and a fresh window — see [poolLateMax]. */
    private fun drainPoolLate(): String =
        "${poolLateMax.getAndSet(0)}/${poolLateOver50.getAndSet(0)}/${poolProbes.getAndSet(0)}"

    /**
     * One frame plus its numbers to the debug screen, or nothing at all — see [previewSink].
     *
     * Wrapped, and that is not decoration: the sink is a Dart EventSink reached through the
     * plugin, so a throw from it would land in the frame loop's own catch, count towards
     * [failures] and — three frames later — rebuild a perfectly good encoder because a debug
     * screen misbehaved. The old shape had the whole body inside one `runCatching` for the
     * same reason; splitting it across two objects is what nearly lost the guard.
     */
    private fun emitFramePreview(frameIntervalMs: Long) {
        val sink = previewSink() ?: return
        val frame = source.previewFrame(
            encodedBytes = lastEncodedBytes,
            framesSent = framesEncoded,
            decoderOpens = decoderOpens(),
            fps = (1000L / frameIntervalMs).toInt(),
        ) ?: return
        runCatching { sink(frame) }
            .onFailure { DebugLog.w(TAG) { "frame preview sink failed: ${it.message}" } }
    }
}
