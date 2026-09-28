package com.opendash.opendash_dash_engine.util

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        dir = File.createTempFile("diag", "").let { it.delete(); it.mkdirs(); it }
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
        assertTrue(
            all.indexOf("summary of the first") < all.indexOf(RideDiagnostics.SUPERSEDED),
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
        assertEquals(
            Regex("==== session start").findAll(all).count(),
            Regex("==== session end").findAll(all).count(),
            all,
        )
    }

    @Test
    fun `an unopened logger does not invent a file`() {
        RideDiagnostics.useDirectory(null)
        RideDiagnostics.start("connect")
        RideDiagnostics.log("test", "nowhere")
        assertTrue(rides().isEmpty(), "no directory, no file")
    }

    @Test
    fun `isOpen tracks the file, which is what the caller keys its summary on`() {
        assertTrue(!RideDiagnostics.isOpen, "nothing open before the first start")
        RideDiagnostics.start("connect")
        assertTrue(RideDiagnostics.isOpen)
        RideDiagnostics.stop("disconnect")
        assertTrue(!RideDiagnostics.isOpen, "stop() must clear it or the next summary is misfiled")
    }
}
