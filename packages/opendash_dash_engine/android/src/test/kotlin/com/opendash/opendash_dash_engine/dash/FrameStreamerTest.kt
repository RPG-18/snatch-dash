package com.opendash.opendash_dash_engine.dash

import android.graphics.Canvas
import com.opendash.opendash_dash_engine.dash.video.FrameEncoder
import com.opendash.opendash_dash_engine.util.DebugLog
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/** One Annex-B non-IDR slice: start code, NAL header type 1, a byte of payload. */
private fun pFrame(): ByteArray = byteArrayOf(0, 0, 0, 1, 0x41, 0x2A)

/**
 * The frame loop, on the JVM, at virtual speed.
 *
 * **Why this file exists.** Until the loop moved out of `DashEngineController` it could not
 * be reached without a phone, a dash and a ride — and `CLAUDE.md` puts almost all of this
 * project's risk in exactly that code ("конкурентность, живой энкодер и сокеты"). Every
 * property checked below was previously judged by reading a ride file after the fact:
 *
 *  - the iteration DEADLINE, which the spec states as `max(interval, latency)` and which a
 *    naive `delay(interval)` after the work silently turns into `interval + latency`;
 *  - the RTP timestamp rule of spec/video.md — one step per FRAME out of the codec, never
 *    per iteration. That rule cost two rides to learn (the map froze on a Huawei whose
 *    encoder misses its drain window 5-20% of the time) and nothing mechanical checked it;
 *  - drop accounting: a full outbox has to cost a whole access unit and SAY so, separately
 *    for key frames;
 *  - the encoder rebuild after three consecutive failures.
 *
 * The RTP assertions read the bytes the socket would have seen, not an internal counter:
 * timestamp and marker bit come back out of the datagram header (RFC 3550 §5.1).
 */
class FrameStreamerTest {

    // ── Fakes ────────────────────────────────────────────────────────────

    private class FakeSource : FrameSource {
        override var moving = false
        var ready = true
        var advanceCostMs = 0L
        var throwOnAdvance = false
        val budgets = mutableListOf<Long>()
        var invalidations = 0
        var flips = 0
        val encodedFrames = mutableListOf<Triple<Long, Long, Long>>()

        override suspend fun advance(budgetMs: Long): Boolean {
            budgets += budgetMs
            if (throwOnAdvance) error("snapshotter is on fire")
            if (advanceCostMs > 0) delay(advanceCostMs)
            return ready
        }

        // Never reached: [FakeEncoder.renderFrame] does not invoke the draw lambda, which is
        // what keeps Canvas — an unmocked Android class here — out of this test.
        override fun drawInto(canvas: Canvas) = Unit

        override fun frameEncoded(intervalMs: Long, encodeMs: Long, intendedIntervalMs: Long) {
            encodedFrames += Triple(intervalMs, encodeMs, intendedIntervalMs)
        }

        override fun drainFpsFlips(): Int = flips.also { flips = 0 }
        override fun drainWindowLog(periodMs: Long): String = "frames=0/0 window=${periodMs}ms"
        override fun invalidate() { invalidations++ }
    }

    /**
     * A codec that hands back exactly what the test says, when the test says.
     *
     * [pending] is what `drain()` will emit on its next call — the queue is how a missed
     * drain window is expressed: leave it empty for one iteration and hand back two frames
     * on the next, which is precisely what `OMX.hisi.video.encoder.avc` does in the field.
     */
    private class FakeEncoder(val onEncoded: (ByteArray, Boolean, Boolean) -> Unit) : FrameEncoder {
        var renders = 0
        var released = 0
        val bitrates = mutableListOf<Int>()
        val pending = ArrayDeque<ByteArray>()

        /** Frames handed out per drain() call; the loop counts 0 as a miss and ≥2 as a double. */
        var framesPerDrain = 1

        override fun renderFrame(draw: (Canvas) -> Unit) {
            renders++
            // The real encoder would run `draw` against its input Surface. Skipping it is the
            // whole trick that keeps this test off a device.
            pending.addLast(pFrame())
        }

        override fun drain(): Int {
            var out = 0
            repeat(framesPerDrain) {
                val frame = pending.removeFirstOrNull() ?: return@repeat
                onEncoded(frame, false, false)
                out++
            }
            return out
        }

        override fun requestBitrate(bps: Int) { bitrates += bps }
        override fun release() { released++ }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * The periodic `[stream]` line, not the `[map]` one.
     *
     * Both open with `frames=`, so picking by prefix quietly matched whichever was written
     * last — which is how the first draft of these tests read a render window and looked for
     * drop accounting in it. `drainMiss=` appears only in the stream line.
     */
    private fun streamLine(): String = logLines.last { it.contains("drainMiss=") }

    /** One `name=<number>` field out of a ride-log line, by exact name. */
    private fun field(line: String, name: String): Int =
        Regex("(?:^|\\s)$name=(\\d+)").find(line)?.groupValues?.get(1)?.toInt()
            ?: error("no $name= in: $line")

    private fun rtpTimestamp(pkt: ByteArray): Long =
        ((pkt[4].toLong() and 0xFF) shl 24) or ((pkt[5].toLong() and 0xFF) shl 16) or
            ((pkt[6].toLong() and 0xFF) shl 8) or (pkt[7].toLong() and 0xFF)

    private val logLines = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        // Drain whatever an earlier test left buffered, then listen: RideDiagnostics.log
        // mirrors every ride-file line into DebugLog, so this is how the `[stream]` line
        // itself — the text a post-mortem reads — can be asserted on.
        DebugLog.sink = { _, _, _ -> }
        logLines.clear()
        DebugLog.sink = { _, _, message -> logLines += message }
    }

    @AfterTest
    fun tearDown() { DebugLog.sink = null }

    private class Rig(val scope: TestScope) {
        val source = FakeSource()
        val sent = mutableListOf<ByteArray>()
        var encoders = 0
        var lastEncoder: FakeEncoder? = null
        var streaming = true

        /**
         * How far the clock runs ahead of the scheduler once [penaltyFromMs] passes.
         *
         * Virtual `delay` is exact, so a late wake-up cannot happen on its own here — and
         * "the clock says more time passed than we asked to sleep" is precisely what being
         * descheduled looks like from inside the loop.
         */
        var clockPenaltyMs = 0L
        var penaltyFromMs = Long.MAX_VALUE
        var sender: ((ByteArray) -> Unit)? = { sent += it }

        var encoderBuilds = true

        val streamer = FrameStreamer(
            source = source,
            encoderFactory = { onEncoded ->
                encoders++
                if (!encoderBuilds) error("MediaCodec.configure failed")
                FakeEncoder(onEncoded).also { lastEncoder = it }
            },
            rtpSender = { sender },
            streaming = { streaming },
            thermal = { "OK" },
            clock = {
                val t = scope.testScheduler.currentTime
                if (t >= penaltyFromMs) t + clockPenaltyMs else t
            },
            // The sender shares the test's scheduler instead of Dispatchers.IO, so "the
            // socket is busy" is something the test can hold still rather than race with.
            senderContext = EmptyCoroutineContext,
            // The counterfactual probe too: on Dispatchers.Default it would sleep in REAL
            // time while everything else here runs on virtual, and compare two clocks.
            probeContext = EmptyCoroutineContext,
        )

        fun start(): Job {
            streamer.prepareEncoder()
            return scope.launch { streamer.run() }
        }
    }

    // ── The loop's cadence ───────────────────────────────────────────────

    @Test
    fun `paces to a deadline, not to a sleep on top of the work`() = runTest {
        val rig = Rig(this)
        rig.source.advanceCostMs = 100      // a slow snapshot, inside the budget
        val job = rig.start()

        advanceTimeBy(1_001)
        rig.streaming = false
        advanceTimeBy(1_000)
        job.join()

        // 2 fps idle: four iterations in a second, NOT the 1000/(500+100) = 1.6 that a
        // `delay(interval)` after the work would have produced.
        assertEquals(3, rig.source.budgets.size)
        assertTrue(rig.source.budgets.all { it == 500L }, "idle budget is 500 ms")
    }

    @Test
    fun `a frame that overruns its budget is not made up for by a short next one`() = runTest {
        val rig = Rig(this)
        rig.source.advanceCostMs = 700      // longer than the whole 500 ms idle budget
        val job = rig.start()

        advanceTimeBy(2_100)
        rig.streaming = false
        advanceTimeBy(1_000)
        job.join()

        // Three iterations of 700 ms, and every one of them still asks for the nominal
        // budget: falling behind is reported (frames=X/expected), never compensated.
        assertEquals(3, rig.source.budgets.size)
        assertEquals(listOf(500L, 500L, 500L), rig.source.budgets)
    }

    @Test
    fun `moving switches the loop to 4 fps and the encoder to the moving bitrate`() = runTest {
        val rig = Rig(this)
        val job = rig.start()

        advanceTimeBy(1_010)            // idle: 500 ms budgets
        val idleBudgets = rig.source.budgets.toList()
        rig.source.moving = true
        advanceTimeBy(1_010)            // moving: 250 ms budgets
        rig.streaming = false
        advanceTimeBy(500)
        job.join()

        assertTrue(idleBudgets.all { it == 500L }, "idle budget is 500 ms: $idleBudgets")
        assertTrue(
            rig.source.budgets.drop(idleBudgets.size).all { it == 250L },
            "moving budget is 250 ms: ${rig.source.budgets}",
        )
        // Retargeted twice, once per TRANSITION — not once per frame. A fresh encoder is
        // already configured at the moving rate, which is why the idle profile is the first
        // of the two rather than a no-op.
        assertEquals(listOf(100_000, 200_000), rig.lastEncoder!!.bitrates)
    }

    // ── No snapshot, no frame ────────────────────────────────────────────

    @Test
    fun `an unfinished snapshot costs a frame, not the loop`() = runTest {
        val rig = Rig(this)
        rig.source.ready = false
        val job = rig.start()

        advanceTimeBy(2_010)
        rig.streaming = false
        advanceTimeBy(500)
        job.join()

        assertEquals(5, rig.source.budgets.size, "the loop keeps its cadence")
        assertEquals(0, rig.lastEncoder!!.renders, "nothing is handed to the encoder")
        assertTrue(rig.sent.isEmpty(), "and nothing reaches the socket")
    }

    // ── The RTP timestamp rule (spec/video.md) ───────────────────────────

    @Test
    fun `the timestamp advances once per frame out of the codec, not once per iteration`() =
        runTest {
            val rig = Rig(this)
            val job = rig.start()

            // Iteration 1 renders but the codec keeps the frame (a missed drain window);
            // iteration 2 hands back BOTH. Before 2026-09-15 those two access units went out
            // stamped identically, and the dash showed the second one immediately — the
            // freeze that took two rides and a second phone to pin down.
            rig.lastEncoder!!.framesPerDrain = 0
            advanceTimeBy(510)
            rig.lastEncoder!!.framesPerDrain = 2
            advanceTimeBy(510)
            rig.streaming = false
            advanceTimeBy(500)
            job.join()

            assertEquals(2, rig.sent.size, "both frames were sent")
            val stamps = rig.sent.map { rtpTimestamp(it) }
            assertEquals(
                (stamps[1] - stamps[0] + 0x1_0000_0000L) % 0x1_0000_0000L,
                500L * 90,
                "one idle interval apart, in 90 kHz ticks",
            )
        }

    @Test
    fun `the step follows the frame rate across a 2 to 4 fps flip`() = runTest {
        val rig = Rig(this)
        val job = rig.start()

        advanceTimeBy(1_010)        // three idle frames, 500 ms apart
        rig.source.moving = true
        advanceTimeBy(1_010)        // then four moving frames, 250 ms apart
        rig.streaming = false
        advanceTimeBy(500)
        job.join()

        val steps = rig.sent.map { rtpTimestamp(it) }
            .zipWithNext { a, b -> (b - a + 0x1_0000_0000L) % 0x1_0000_0000L }
        assertTrue(steps.isNotEmpty(), "frames went out")
        assertTrue(steps.first() == 500L * 90, "idle frames are half a second apart: $steps")
        assertTrue(steps.last() == 250L * 90, "moving frames are a quarter apart: $steps")
        // Never zero: two distinct frames may not share an instant, whatever the codec does.
        assertTrue(steps.none { it == 0L }, "no two frames share a timestamp: $steps")
    }

    // ── Drops ────────────────────────────────────────────────────────────

    @Test
    fun `a socket that never drains costs whole access units, and the line says so`() = runTest {
        val rig = Rig(this)
        // No sender at all: DashSession.rtpSender() returns null when the socket is gone, the
        // sender coroutine returns immediately, and the outbox (capacity 4) fills for good.
        rig.sender = null
        val job = rig.start()

        advanceTimeBy(61_000)
        rig.streaming = false
        advanceTimeBy(500)
        job.join()

        val line = streamLine()
        // Parsed, not matched as a substring: `contains("drop=11")` was the first version
        // of this assertion and it passed against the real `drop=118` as a prefix — so it
        // would have passed against 110 or 1100 just as happily, and the one test guarding
        // drop accounting could not have failed. Found by review, 2026-09-16.
        val encoded = field(line, "frames")
        val dropped = field(line, "drop")
        // Everything the codec produced beyond the four that fit the outbox, and not one
        // frame less: a drop is a whole access unit, never part of one.
        assertEquals(encoded - 4, dropped, "every frame past the queue's depth is counted: $line")
        assertEquals(0, field(line, "dropIdr"), "none of them were key frames: $line")
        assertTrue(rig.sent.isEmpty())
    }

    // ── The stage-6 gate: was the loop waiting for a scheduler? ──────────

    @Test
    fun `a frame that overruns its budget is not reported as scheduling delay`() = runTest {
        val rig = Rig(this)
        rig.source.advanceCostMs = 700      // 200 ms past the idle budget, every iteration
        val job = rig.start()

        advanceTimeBy(61_000)
        rig.streaming = false
        advanceTimeBy(1_000)
        job.join()

        // The loop never slept, so there was nothing to be late from. Counting the overrun
        // here would blame the scheduler for our own work — and would have made the case for
        // dedicated threads out of a snapshot that took too long.
        val line = streamLine()
        // `-`, not `0/0/0`: an empty sample is not a measurement of zero, and the ride file
        // says so — the same marker every other percentile field in this line uses.
        assertTrue(line.contains("wakeLate=-ms"), "no sleep, no lateness: $line")
        // And the reason the sample is empty is printed next to it. Without this number a
        // window where EVERY frame overran looks exactly like a window where nothing went
        // wrong — both show `wakeLate=-ms`. Found by review, 2026-09-16.
        assertTrue(field(line, "overrun") > 0, "the overruns are counted: $line")
    }

    @Test
    fun `a late wake-up is measured and reported`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        rig.penaltyFromMs = 30_000
        rig.clockPenaltyMs = 40

        advanceTimeBy(61_000)
        rig.streaming = false
        advanceTimeBy(1_000)
        job.join()

        val line = streamLine()
        val max = Regex("wakeLate=\\d+/\\d+/(\\d+)ms").find(line)?.groupValues?.get(1)?.toInt()
        assertTrue(max != null && max >= 40, "the hiccup reaches the ride file: $line")
    }

    @Test
    fun `the shared pool is probed alongside every sleep`() = runTest {
        val rig = Rig(this)
        val job = rig.start()

        advanceTimeBy(61_000)
        rig.streaming = false
        advanceTimeBy(1_000)
        job.join()

        // max/over50/n. The count is what matters here: one probe per sleeping iteration, so
        // the counterfactual covers the same window the loop does. Its VALUE is meaningless
        // under virtual time — nothing is ever late — and that is fine: what a real pool does
        // is a question for the ride file, not for this test.
        val probes = Regex("poolLate=\\d+/\\d+/(\\d+)").find(streamLine())?.groupValues?.get(1)?.toInt()
        assertTrue(probes != null && probes > 100, "one probe per sleep: ${streamLine()}")
        assertTrue(job.isCompleted, "and no probe outlives the stream")
    }

    // ── Failures ─────────────────────────────────────────────────────────

    @Test
    fun `three failures in a row rebuild the encoder and redraw from scratch`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        assertEquals(1, rig.encoders)

        rig.source.throwOnAdvance = true
        advanceTimeBy(1_010)        // iterations at t=0, 500, 1000: exactly three throws
        rig.streaming = false
        advanceTimeBy(500)
        job.join()

        assertEquals(3, rig.source.budgets.size, "exactly three iterations threw")
        assertEquals(2, rig.encoders, "the encoder was rebuilt exactly once")
        assertEquals(1, rig.source.invalidations, "and the next frame is drawn from scratch")
    }

    @Test
    fun `two failures are not enough to rebuild`() = runTest {
        val rig = Rig(this)
        val job = rig.start()

        rig.source.throwOnAdvance = true
        advanceTimeBy(510)          // iterations at t=0 and 500: two throws
        rig.streaming = false
        advanceTimeBy(500)
        job.join()

        assertEquals(2, rig.source.budgets.size, "exactly two iterations threw")
        assertEquals(1, rig.encoders, "a transient failure does not cost the encoder")
        assertEquals(0, rig.source.invalidations)
    }

    @Test
    fun `a good frame between failures resets the count`() = runTest {
        val rig = Rig(this)
        val job = rig.start()

        rig.source.throwOnAdvance = true
        advanceTimeBy(510)          // two throws
        rig.source.throwOnAdvance = false
        advanceTimeBy(510)          // then two clean frames
        rig.source.throwOnAdvance = true
        advanceTimeBy(510)          // and two more throws
        rig.streaming = false
        advanceTimeBy(500)
        job.join()

        // Four failures in the window, but never three in a ROW: the counter is about a
        // codec that has stopped working, not about a ride that had a bad moment.
        assertEquals(1, rig.encoders)
    }

    @Test
    fun `a rebuild that fails ends the stream instead of spinning frameless`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        rig.encoderBuilds = false        // the codec is gone: MediaCodec.configure throws

        rig.source.throwOnAdvance = true
        advanceTimeBy(1_010)             // three throws → rebuild → and it fails
        job.join()                       // the loop ends on its own, without streaming=false

        assertTrue(job.isCompleted, "the loop stopped rather than spinning without an encoder")
        val warn = logLines.last { it.contains("encoder rebuild failed") }
        assertTrue(warn.contains("ending the stream"), "with the cause in the ride file: $warn")
        val after = rig.source.budgets.size
        advanceTimeBy(5_000)
        assertEquals(after, rig.source.budgets.size, "and no more snapshots are paid for")
    }

    @Test
    fun `the encoder is released when the loop unwinds`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        val enc = rig.lastEncoder!!

        advanceTimeBy(600)
        job.cancel()
        job.join()

        assertEquals(1, enc.released, "the loop's finally is the one place that can free it")
    }
}
