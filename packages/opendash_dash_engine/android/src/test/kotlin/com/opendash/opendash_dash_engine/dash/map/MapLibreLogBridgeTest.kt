package com.opendash.opendash_dash_engine.dash.map

import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rate limiter in front of the ride file, tested because it is the half of
 * this bridge that can quietly destroy what the other half exists to record: too
 * eager and a ride keeps none of MapLibre's failures, too loose and 4 fps of them
 * bury the `[map]`/`[stream]` telemetry a post-mortem starts from.
 *
 * Lines are read back through [DebugLog.sink] — the same mirror `/more/logs`
 * uses — so no file, no device and no real ten-second wait are involved.
 */
class MapLibreLogBridgeTest {

    private val lines = mutableListOf<String>()
    private var now = 1_000L

    @BeforeTest
    fun setUp() {
        MapLibreLogBridge.clockMs = { now }
        DebugLog.sink = { tag, _, message -> if (tag == "MapLibre") lines += message }
        // A fresh ride: the bridge drops whatever the previous test left it counting.
        RideDiagnostics.start("test")
        lines.clear()
    }

    @AfterTest
    fun tearDown() {
        DebugLog.sink = null
        MapLibreLogBridge.clockMs = System::currentTimeMillis
    }

    private fun log(msg: String) = MapLibreLogBridge.ride("Mbgl", msg, null)

    @Test
    fun `a message repeating every frame costs one line, then a count`() {
        repeat(40) { log("Could not read asset") }
        assertEquals(1, lines.size, "the repeats must collapse into the first line")
        assertTrue(lines[0].endsWith("Could not read asset"))

        // The window closes on the next line, not on a timer of its own.
        now += 10_000
        log("Could not read asset")
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("repeated 39 more time(s)"), lines[1])
    }

    @Test
    fun `alternating messages do not walk through the collapse`() {
        // What a "same as the previous line?" check misses: two missing assets
        // reported once per snapshot each, forever.
        repeat(40) {
            log("Could not read asset: glyphs/A")
            log("Could not read asset: glyphs/B")
        }
        assertEquals(2, lines.size, "each distinct message is written once per window")
    }

    @Test
    fun `a flood of distinct messages is bounded by the budget`() {
        repeat(50) { log("tile $it failed") }
        // Six written, the rest only counted — and said so once.
        assertEquals(6, lines.size)

        now += 10_000
        log("something else")
        assertTrue(
            lines.any { it.startsWith("44 further line(s) not written") },
            "the dropped ones have to be reported, not silently lost: $lines",
        )
    }

    @Test
    fun `a new ride file does not inherit the previous window's counts`() {
        repeat(5) { log("Could not read asset") }
        assertEquals(1, lines.size)

        // The rider reconnects inside the collapse window — the normal case, since
        // the WiFi retry delay is shorter than it.
        now += 8_000
        RideDiagnostics.start("reconnect")
        lines.clear()
        log("Could not read asset")

        assertEquals(1, lines.size, "the new ride file must open with the failure, not swallow it")
        assertTrue(lines[0].endsWith("Could not read asset"), lines[0])
        assertTrue(
            lines.none { it.contains("repeated") },
            "a count belonging to the previous file must not be written into this one: $lines",
        )
    }
}
