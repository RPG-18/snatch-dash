package com.opendash.opendash_dash_engine.util

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What gets logged before anyone is listening.
 *
 * The line this pins down is [ExitInfoCollector]'s "last exit was an ANR / native crash": it
 * is written from `Application.onCreate`, the Flutter plugin attaches later, and until it does
 * `DebugLog.sink` is null. Those lines used to reach logcat and nothing else — a ring buffer
 * minutes long, against a phone that comes back to the desk hours after the ride.
 */
class DebugLogTest {

    private val received = mutableListOf<Triple<String, String, String>>()

    @BeforeTest
    fun setUp() {
        // Drains whatever an earlier test left buffered, so this one starts empty.
        DebugLog.sink = { _, _, _ -> }
        DebugLog.sink = null
        received.clear()
    }

    @AfterTest
    fun tearDown() {
        DebugLog.sink = null
    }

    private fun attach() {
        DebugLog.sink = { tag, level, message -> received += Triple(tag, level, message) }
    }

    @Test
    fun `lines logged before the sink attaches arrive when it does, in order`() {
        DebugLog.i("Boot") { "first" }
        DebugLog.w("Boot") { "second" }
        assertTrue(received.isEmpty(), "nothing can be delivered while nobody is listening")

        attach()

        assertEquals(2, received.size)
        assertEquals(listOf("first", "second"), received.map { it.third.substringAfterLast("] ") })
        assertEquals(listOf("I", "W"), received.map { it.second })
    }

    @Test
    fun `a flushed line carries the time it was logged, not the time it was flushed`() {
        DebugLog.i("Boot") { "cold start" }
        attach()

        val (_, _, message) = received.single()
        // "[HH:mm:ss.SSS pre-attach] cold start" — the marker is the point: without it the
        // whole buffer wears the attach timestamp and reads as if it happened mid-session.
        assertTrue(
            Regex("""^\[\d\d:\d\d:\d\d\.\d\d\d pre-attach] cold start$""").matches(message),
            "unexpected shape: $message",
        )
    }

    @Test
    fun `once attached, lines go straight through`() {
        attach()
        DebugLog.i("Live") { "no marker here" }

        assertEquals("no marker here", received.single().third)
    }

    @Test
    fun `detaching keeps buffering, so a reattach loses nothing`() {
        attach()
        DebugLog.sink = null
        DebugLog.i("Gap") { "logged between attaches" }
        received.clear()

        attach()

        assertEquals(1, received.size, "a detach/attach cycle within one process is ordinary")
        assertTrue(received.single().third.endsWith("logged between attaches"))
    }

    @Test
    fun `the buffer is bounded and keeps the newest lines`() {
        repeat(100) { i -> DebugLog.i("Flood") { "line $i" } }

        attach()

        assertEquals(64, received.size, "capacity is the cap, not a suggestion")
        assertTrue(received.first().third.endsWith("line 36"), "the oldest are the ones dropped")
        assertTrue(received.last().third.endsWith("line 99"))
    }
}
