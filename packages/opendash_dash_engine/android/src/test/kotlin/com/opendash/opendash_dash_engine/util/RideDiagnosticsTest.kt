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
        // `SystemClock.elapsedRealtime` throws off a device. Advancing it by hand also
        // keeps the `+NNNms` column deterministic.
        now = 0L
        RideDiagnostics.clockMs = { now += 5; now }
    }

    @AfterTest
    fun tearDown() {
        RideDiagnostics.stop("test")
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
    fun `nothing is written after stop, and nothing before start`() {
        RideDiagnostics.log("test", "before any session")
        RideDiagnostics.start("connect")
        RideDiagnostics.log("test", "inside")
        RideDiagnostics.stop("disconnect")
        RideDiagnostics.log("test", "after the end marker")

        val all = rides().joinToString("\n") { it.readText() }
        assertTrue("inside" in all, all)
        assertFalse("before any session" in all, "a line escaped into a file:\n$all")
        assertFalse(
            "after the end marker" in all,
            "a line landed after `session end`, which is what makes the marker mean " +
                "anything:\n$all",
        )
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
