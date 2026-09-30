package com.opendash.opendash_dash_engine.util

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * That a ride file always ends.
 *
 * The ride file is the only post-mortem a release build has, and its contract is narrow:
 * every file opens with a header and closes with `==== session end ====`. A file that
 * simply stops is not a file with a missing line — it is the signal for "the process was
 * killed mid-ride", which is the one thing this whole mechanism exists to catch. Before
 * task 12 a second `connect()` produced exactly that, and the 27.09 bench has one.
 */
class RideDiagnosticsTest {

    private lateinit var dir: File
    private var now = 0L

    @BeforeTest
    fun setUp() {
        // Asserted, not assumed: a fixture that silently failed to create the directory
        // would make every count below zero, and two of these tests compare counts.
        dir = File.createTempFile("diag", "").also {
            assertTrue(it.delete() && it.mkdirs(), "could not build a temp diag dir at $it")
        }
        RideDiagnostics.useDirectory(dir)
        // The pre-session buffer survives a session on purpose, so in an `object` it
        // also survives a test: without this, a line left pending by one test is
        // flushed into the next one's file and read as that ride's.
        RideDiagnostics.clearPending()
        // `SystemClock.elapsedRealtime` throws off a device. Advancing it by hand also
        // keeps the `+NNNms` column deterministic.
        now = 0L
        RideDiagnostics.clockMs = { now += 5; now }
    }

    @AfterTest
    fun tearDown() {
        RideDiagnostics.stop("test")
        RideDiagnostics.clearPending()
        RideDiagnostics.useDirectory(null)
        RideDiagnostics.clockMs = ::monotonicMs
        dir.deleteRecursively()
    }

    private fun rides(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".log") }?.sortedBy { it.name }.orEmpty()

    @Test
    fun `a second start closes the first file instead of abandoning it`() {
        RideDiagnostics.start("connect")
        RideDiagnostics.log("test", "first")
        // The caller's own closing line — DashEngineController writes the Wi-Fi totals
        // here, and they have to land in the file that is ending, not the next one.
        RideDiagnostics.log("test", "summary of the first")
        RideDiagnostics.start("connect")
        RideDiagnostics.log("test", "second")
        RideDiagnostics.stop("disconnect")

        val files = rides()
        // Same-second starts share a stamp and append into one file — that is deliberate
        // (see PacketCapture.start), so assert on content rather than on file count.
        val all = files.joinToString("\n") { it.readText() }
        assertEquals(2, Regex("==== session start").findAll(all).count(), all)
        assertEquals(2, Regex("==== session end").findAll(all).count(), all)
        assertTrue(RideDiagnostics.SUPERSEDED in all, "the first close must say why:\n$all")
        // Presence first. `indexOf` returns -1 for a line that never arrived, and -1 is
        // less than any real index — so the ordering assertion alone passes loudest
        // exactly when the behaviour it pins is gone.
        val summaryAt = all.indexOf("summary of the first")
        val closedAt = all.indexOf(RideDiagnostics.SUPERSEDED)
        assertTrue(summaryAt >= 0, "the caller's closing line never reached a file:\n$all")
        assertTrue(
            summaryAt < closedAt,
            "the caller's closing line must precede the end marker:\n$all",
        )
    }

    @Test
    fun `every start is matched by an end, however many there are`() {
        repeat(4) {
            RideDiagnostics.start("connect")
            RideDiagnostics.log("test", "ride $it")
        }
        RideDiagnostics.stop("disconnect")
        val all = rides().joinToString("\n") { it.readText() }
        // The expected number, not just "the two agree": zero equals zero, so a fixture
        // that wrote nothing would pass a bare comparison while guaranteeing nothing.
        assertEquals(4, Regex("==== session start").findAll(all).count(), all)
        assertEquals(4, Regex("==== session end").findAll(all).count(), all)
    }

    @Test
    fun `an unopened logger does not invent a file`() {
        RideDiagnostics.useDirectory(null)
        RideDiagnostics.start("connect")
        RideDiagnostics.log("test", "nowhere")
        assertTrue(rides().isEmpty(), "no directory, no file")
    }

    @Test
    fun `nothing lands after the end marker of the file it was written to`() {
        RideDiagnostics.start("connect")
        RideDiagnostics.log("test", "inside")
        RideDiagnostics.stop("disconnect")
        RideDiagnostics.log("test", "after the end marker")

        val all = rides().joinToString("\n") { it.readText() }
        assertTrue("inside" in all, all)
        assertFalse(
            "after the end marker" in all,
            "a line landed after `session end`, which is what makes the marker mean " +
                "anything:\n$all",
        )
    }

    @Test
    fun `a line from outside a session is kept and written into the next one`() {
        // Task 14's whole point. The Flutter error this was built for (28.09, the
        // settings screen) fired with no dash connected, so the version that only
        // wrote between connect and disconnect dropped it — the feature failing in
        // exactly its motivating case.
        RideDiagnostics.warn("test", "crashed before connecting", keepWhenIdle = true)
        RideDiagnostics.start("connect")
        RideDiagnostics.stop("disconnect")

        val all = rides().joinToString("\n") { it.readText() }
        assertTrue("crashed before connecting" in all, "the line was dropped:\n$all")
        // Marked, because it has no `+NNNms`: there was no session to measure it
        // from, and an unmarked line reads as having happened during the ride.
        assertTrue("[outside a session]" in all, all)
        val headerAt = all.indexOf("==== session start")
        val lineAt = all.indexOf("crashed before connecting")
        assertTrue(headerAt in 0..<lineAt, "it must follow the header, not precede it:\n$all")
    }

    @Test
    fun `an ordinary write outside a session is still dropped, not carried over`() {
        // The buffer is opt-in for exactly this reason. Half the engine keeps writing
        // after `stop` runs on Main — the RX loop's socket error, FrameStreamer's
        // finally, the `[mem]` job on Dispatchers.IO — and buffering those replays a
        // session's own tail at the head of the NEXT ride file, which is the misfiling
        // `RideDiagnostics.session` exists to prevent.
        RideDiagnostics.start("connect")
        RideDiagnostics.log("test", "inside")
        RideDiagnostics.stop("disconnect")
        RideDiagnostics.warn("test", "RX loop stopped — socket error")
        RideDiagnostics.log("test", "late [mem] sample")

        RideDiagnostics.start("connect again")
        RideDiagnostics.stop("disconnect")
        val all = rides().joinToString("\n") { it.readText() }
        assertFalse("RX loop stopped" in all, "a session's tail reached the next file:\n$all")
        assertFalse("late [mem] sample" in all, all)
    }

    @Test
    fun `the buffer is bounded, and says how much it dropped`() {
        // A rider who leaves the app broken for an hour must not turn the next ride
        // file into a spool of everything that happened before it.
        repeat(30) { RideDiagnostics.warn("test", "pre $it", keepWhenIdle = true) }
        RideDiagnostics.start("connect")
        RideDiagnostics.stop("disconnect")

        val all = rides().joinToString("\n") { it.readText() }
        assertEquals(20, Regex("\\[outside a session]").findAll(all).count(), all)
        // The NEWEST survive: an error still in flight when the rider connects beats
        // one from an hour ago.
        assertFalse("pre 9 " in all || "pre 9\n" in all, "the oldest should be gone:\n$all")
        assertTrue("pre 29" in all, all)
        assertTrue("10 earlier line(s) dropped" in all, "the loss must be stated:\n$all")
    }

    @Test
    fun `an empty buffer adds nothing to the file`() {
        RideDiagnostics.start("connect")
        RideDiagnostics.stop("disconnect")
        val all = rides().joinToString("\n") { it.readText() }
        assertFalse("[outside a session]" in all, all)
        assertFalse("dropped" in all, all)
    }

    @Test
    fun `changing the directory closes the open file instead of abandoning it`() {
        RideDiagnostics.start("connect")
        RideDiagnostics.log("test", "first")
        val other = File.createTempFile("diag2", "").also {
            assertTrue(it.delete() && it.mkdirs())
        }
        try {
            RideDiagnostics.useDirectory(other)
            val all = rides().joinToString("\n") { it.readText() }
            assertEquals(1, Regex("==== session end").findAll(all).count(), all)
        } finally {
            RideDiagnostics.useDirectory(dir)
            other.deleteRecursively()
        }
    }
}
