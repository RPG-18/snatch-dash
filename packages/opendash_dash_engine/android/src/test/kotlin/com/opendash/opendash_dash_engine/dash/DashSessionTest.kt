package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.DashCommand
import com.opendash.opendash_dash_engine.dash.protocol.K1GCodec
import com.opendash.opendash_dash_engine.dash.protocol.Scripts
import com.opendash.opendash_dash_engine.dash.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What one connection to the dash does, on virtual time.
 *
 * Everything here was unreachable before stage 4. The session used to build its own
 * `DashSocket` in a constructor that binds :2000 and :2002 — impossible in a JVM test — and
 * it kept its state in `@Volatile` fields a test could not observe without racing it. With
 * [DashTransport] as a seam and the whole session on one injected dispatcher, "what goes out,
 * in what order, how far apart" becomes an assertion instead of a code review.
 *
 * The clock is the test scheduler's, so the 15 s auth timeout and the 10 s watchdog are
 * checked at their real thresholds in microseconds of wall time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashSessionTest {

    private val ssid = "K1G-TEST"

    /** A session, its transport, and the chrome it reads — wired to one test scheduler. */
    private class Rig(
        val session: DashSession,
        val wire: FakeDashTransport,
        val chrome: MutableStateFlow<DashChrome>,
        val parent: Job,
        val events: MutableList<SessionEvent>,
    )

    private fun TestScope.rig(
        chrome: MutableStateFlow<DashChrome> = MutableStateFlow(DashChrome()),
        transport: FakeDashTransport = FakeDashTransport { testScheduler.currentTime },
    ): Rig {
        val parent = Job()
        val events = mutableListOf<SessionEvent>()
        val session = DashSession.open(
            ssid = ssid,
            chrome = chrome,
            clock = { testScheduler.currentTime },
            parent = parent,
            context = StandardTestDispatcher(testScheduler),
        ) { transport }
        backgroundScope.launch { session.events.toList(events) }
        return Rig(session, transport, chrome, parent, events)
    }

    // ── Handshake ─────────────────────────────────────────────────────────

    @Test
    fun `the burst goes out in order, 20 ms apart, before anything is expected back`() = runTest {
        val r = rig()
        advanceTimeBy(200)
        runCurrent()

        val expected = Scripts.initialBurst("OpenDash", DashCommand.TimeSync(0, 0, 0))
        val byTime = r.wire.sent.groupBy { it.atMs }
        // Nine packets at 0, 20, 40 … 160 ms — invariant 4. The burst does NOT have the wire
        // to itself and never did: the 1 Hz heartbeat and the 30 s clock sync run from the
        // moment the session opens, so tick zero shares the millisecond with burst packet #0.
        // Grouping by time is what lets the spacing be asserted anyway.
        assertEquals(
            (0..8).map { it * 20L },
            byTime.keys.sorted(),
            "one burst packet every 20 ms and nothing in between",
        )
        // The time step is the one packet whose bytes depend on the clock, so it is compared
        // by its fixed prefix; every other packet is compared whole.
        for (i in expected.indices) {
            val want = K1GCodec.encode(expected[i].cmd).toHex()
            val at = byTime.getValue(i * 20L)
            val got = (if (i == 0) at.first() else at.single()).bytes.toHex()
            if (i == 2) assertEquals(want.take(42), got.take(42), "#2 time sync header")
            else assertEquals(want, got, "burst packet #$i")
        }
        assertEquals(DashState.AUTHENTICATING, r.session.state.value)
        r.close()
    }

    @Test
    fun `the full handshake reaches READY through nav entry, byte for byte`() = runTest {
        val r = rig(chrome = MutableStateFlow(DashChrome(destinationName = "Мурманск")))
        advanceTimeBy(200)
        runCurrent()
        r.wire.clearSent()

        val key = offerPubKey(r)
        runCurrent()

        // q3c.d: the RSA block, built by the codec, 149 B all in.
        val sendKey = r.wire.sent.single().bytes
        assertEquals(149, sendKey.size, "21-byte header + one RSA-1024 block")
        assertTrue(sendKey.toHex().startsWith("0095"), "declared length 0x0095")
        assertEquals(1024, key.modulus.bitLength(), "test setup: an RSA-1024 dash key")

        r.wire.clearSent()
        r.wire.deliver(incoming(0x07, 0x01, byteArrayOf(0x01, 0x02)))
        // Nav entry's pauses total 1790 ms; 1800 covers it with nothing else in the window
        // (the 1 Hz heartbeat is filtered below — it is not part of the script).
        advanceTimeBy(1_800)
        runCurrent()

        val heartbeat = enc(DashCommand.Heartbeat())
        val navEntry = r.wire.sent.filterNot { it.bytes.toHex() == heartbeat }
        val script = Scripts.enterNavMode("Мурманск")
        assertEquals(
            script.map { K1GCodec.encode(it.cmd).toHex() },
            navEntry.map { it.bytes.toHex() },
            "nav entry, byte for byte and in order",
        )
        val start = navEntry.first().atMs
        assertEquals(
            script.runningFold(0L) { at, step -> at + step.pauseAfterMs }.dropLast(1),
            navEntry.map { it.atMs - start },
            "and with the captured pauses — invariant 5",
        )
        assertEquals(DashState.READY, r.session.state.value)
        assertEquals<List<SessionEvent>>(listOf(SessionEvent.Ready), r.events)
        r.close()
    }

    @Test
    fun `the destination the card carries is the one set before the session opened`() = runTest {
        val r = rig(chrome = MutableStateFlow(DashChrome(destinationName = "Дом")))
        reachReady(r)

        val cards = r.wire.sent.map { it.bytes.toHex() }
            .filter { it.contains(K1GCodec.encode(DashCommand.RouteCard("Дом")).toHex().drop(20)) }
        assertTrue(cards.isNotEmpty(), "the chrome set before open() is what nav entry announces")
        r.close()
    }

    // ── Auth failures ─────────────────────────────────────────────────────

    @Test
    fun `auth that never confirms fails at the timeout, and the wire goes quiet`() = runTest {
        val r = rig()
        advanceTimeBy(200)
        runCurrent()

        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(DashState.ERROR, r.session.state.value)
        val failed = assertIs<SessionEvent.Failed>(r.events.single())
        assertTrue(failed.reason.startsWith("Auth timed out"), failed.reason)
        assertTrue(r.wire.closed, "the transport is closed, not left bound to :2002")

        val after = r.wire.sent.size
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(after, r.wire.sent.size, "nothing leaves a failed session")
        r.close()
    }

    @Test
    fun `a rejected key is re-offered five times and then no more`() = runTest {
        val r = rig()
        advanceTimeBy(200)
        runCurrent()

        val authRequest = K1GCodec.encode(DashCommand.AuthRequest).toHex()
        repeat(6) {
            offerPubKey(r)
            runCurrent()
            r.wire.deliver(incoming(0x07, 0x01, byteArrayOf(0x00, 0x02)))
            runCurrent()
        }

        // One in the burst plus five retries. The sixth rejection is answered with silence:
        // a dash that has said no six times is not about to say yes.
        assertEquals(6, r.wire.sent.count { it.bytes.toHex() == authRequest })

        // And it stays silent through the re-ask interval. A rejection clears the key
        // halves, so a prod keyed on "has the dash offered a key" would read false here and
        // start asking again every 2 s — walking straight past the budget just spent.
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(
            6,
            r.wire.sent.count { it.bytes.toHex() == authRequest },
            "the reject budget is a budget, not a pause",
        )
        assertEquals(DashState.AUTHENTICATING, r.session.state.value, "still waiting, not failed")
        r.close()
    }

    @Test
    fun `a silent dash is asked again, and the ask is the same packet`() = runTest {
        val r = rig()
        advanceTimeBy(200)
        runCurrent()
        r.wire.clearSent()

        // Six seconds of the dash saying nothing at all — which is what the 2026-09-19
        // Huawei log holds: telemetry and a restart blob, but never a byte of type 07.
        advanceTimeBy(6_000)
        runCurrent()

        val authRequest = enc(DashCommand.AuthRequest)
        assertEquals(
            3,
            r.wire.sent.count { it.bytes.toHex() == authRequest },
            "one re-ask every 2 s while the dash stays silent",
        )
        r.close()
    }

    @Test
    fun `the re-asking stops as soon as the dash offers its key`() = runTest {
        val r = rig()
        advanceTimeBy(200)
        runCurrent()

        // Only the modulus: the dash has started its half of the handshake but has not
        // finished it. Another q3c.e here would restart ITS side while DashAuth.keySent
        // keeps ours from answering the second offer — a stall we would have caused.
        val pub = rsaKey()
        r.wire.deliver(incoming(0x07, 0x00, pub.modulus.toByteArray()))
        runCurrent()
        r.wire.clearSent()

        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(
            0,
            r.wire.sent.count { it.bytes.toHex() == enc(DashCommand.AuthRequest) },
            "mid-handshake, the dash is left alone",
        )
        r.close()
    }

    @Test
    fun `a dash that answers late still connects`() = runTest {
        val r = rig()
        advanceTimeBy(200)
        runCurrent()

        // 2026-09-18, Huawei: the request went out at 9:40:42 and the answer came at
        // 9:41:01. Nineteen seconds is past AUTH_TIMEOUT, but five seconds is not, and
        // before the re-asking a dash that ignored the one burst was never asked again.
        advanceTimeBy(5_000)
        runCurrent()
        offerPubKey(r)
        runCurrent()
        r.wire.deliver(incoming(0x07, 0x01, byteArrayOf(0x01, 0x02)))
        advanceTimeBy(1_800)
        runCurrent()

        assertEquals(DashState.READY, r.session.state.value)
        r.close()
    }

    // ── Farewell ──────────────────────────────────────────────────────────

    @Test
    fun `closing mid-script sends exactly one stop and one off, and then nothing`() = runTest {
        val r = rig()
        advanceTimeBy(200)
        runCurrent()
        offerPubKey(r)
        runCurrent()
        r.wire.deliver(incoming(0x07, 0x01, byteArrayOf(0x01, 0x02)))
        // Part-way into nav entry: its pauses total 1790 ms, so 300 ms is mid-script.
        advanceTimeBy(300)
        runCurrent()
        r.wire.clearSent()

        r.session.close(farewell = true)
        runCurrent()

        val tail = r.wire.sent.map { it.bytes.toHex() }
        assertEquals(
            1,
            tail.count { it == K1GCodec.encode(DashCommand.ProjectionStop).toHex() },
            "exactly one projectionStop",
        )
        assertEquals(
            1,
            tail.count { it == K1GCodec.encode(DashCommand.ProjectionOff).toHex() },
            "exactly one projectionOff",
        )
        assertEquals(
            listOf(
                K1GCodec.encode(DashCommand.ProjectionStop).toHex(),
                K1GCodec.encode(DashCommand.ProjectionOff).toHex(),
            ),
            tail.takeLast(2),
            "and they are the last two packets of the session",
        )
        assertTrue(r.wire.closed)
        assertEquals(DashState.IDLE, r.session.state.value)
    }

    @Test
    fun `closing without a farewell writes nothing at all`() = runTest {
        val r = rig()
        reachReady(r)
        r.wire.clearSent()

        r.session.close(farewell = false)
        runCurrent()

        assertEquals(emptyList<String>(), r.wire.sent.map { it.bytes.toHex() })
        assertTrue(r.wire.closed)
    }

    // ── The one clock ─────────────────────────────────────────────────────

    @Test
    fun `one second of streaming is four projection frames, one card and one heartbeat`() = runTest {
        val chrome = MutableStateFlow(
            DashChrome(destinationName = "Дом", nav = NavFigures(0x14, 120, 0x30, 4200, 0x30)),
        )
        val r = rig(chrome = chrome)
        reachReady(r)
        r.session.startStreaming()
        // To the next tick boundary, so the window below is a whole number of ticks.
        advanceTimeBy(250)
        runCurrent()
        r.wire.clearSent()

        advanceTimeBy(1_000)
        runCurrent()

        val hexes = r.wire.sent.map { it.bytes.toHex() }
        assertEquals(4, hexes.count { it == enc(DashCommand.ProjectionFrame) }, "4 Hz projection")
        assertEquals(1, hexes.count { it == enc(DashCommand.Heartbeat()) }, "1 Hz heartbeat")
        assertEquals(
            1,
            hexes.count { it == enc(DashCommand.RouteCard("Дом", true, 0x14, 0x30, 4200, 0x30)) },
            "1 Hz route card, carrying the live figures",
        )
        assertEquals(
            1,
            hexes.count { it == enc(DashCommand.ActiveNav(0x14, 120, 0x30, 4200, 0x30)) },
            "1 Hz nav bubble",
        )
        r.close()
    }

    @Test
    fun `the order inside a one-second tick is fixed`() = runTest {
        val chrome = MutableStateFlow(
            DashChrome(
                destinationName = "Дом",
                nav = NavFigures(0x14, 120, 0x30, 4200, 0x30),
                nowPlaying = NowPlaying("Song"),
            ),
        )
        val r = rig(chrome = chrome)
        reachReady(r)
        r.session.startStreaming()
        advanceTimeBy(250)
        runCurrent()
        r.wire.clearSent()

        advanceTimeBy(1_000)
        runCurrent()

        // The tick that carries the 1 Hz senders, in full. The dash renders whatever arrived
        // last, so "card, then the figures drawn on it" is a property of the protocol and not
        // of which coroutine happened to win.
        val tick = r.wire.sent.map { it.bytes.toHex() }.let { all ->
            val i = all.indexOfFirst { it == enc(DashCommand.Heartbeat()) }
            all.subList((i - 4).coerceAtLeast(0), i + 1)
        }
        assertEquals(
            listOf(
                enc(DashCommand.ProjectionFrame),
                enc(DashCommand.RouteCard("Дом", true, 0x14, 0x30, 4200, 0x30)),
                enc(DashCommand.ActiveNav(0x14, 120, 0x30, 4200, 0x30)),
                enc(DashCommand.NowPlaying("Song", "", "")),
                enc(DashCommand.Heartbeat()),
            ),
            tick,
        )
        r.close()
    }

    @Test
    fun `the clock sync goes out every thirty seconds and not oftener`() = runTest {
        val r = rig()
        reachReady(r)
        r.session.startStreaming()
        r.wire.clearSent()

        // Stepped, with the dash talking back: 30 s of pure silence would trip the watchdog
        // at ten, which is itself worth having this test prove.
        advanceStreaming(r, 30_000)

        val syncs = r.wire.sent.count { it.bytes.toHex().startsWith("00180002000000000201000" + "54b3147200006060003") }
        assertEquals(1, syncs, "one 06 06 per 30 s — the dash has no clock of its own")
        r.close()
    }

    @Test
    fun `nothing is sent to a dash that has not reached streaming`() = runTest {
        val r = rig()
        reachReady(r)
        r.wire.clearSent()

        advanceTimeBy(1_000)
        runCurrent()

        val hexes = r.wire.sent.map { it.bytes.toHex() }
        assertFalse(
            hexes.any { it == enc(DashCommand.ProjectionFrame) },
            "no projection keep-alive before the decoder is open",
        )
        assertEquals(1, hexes.count { it == enc(DashCommand.Heartbeat()) }, "but the heartbeat runs")
        r.close()
    }

    // ── Watchdog ──────────────────────────────────────────────────────────

    @Test
    fun `ten seconds of silence after auth ends the session`() = runTest {
        val r = rig()
        reachReady(r)

        advanceTimeBy(11_000)
        runCurrent()

        assertEquals(DashState.IDLE, r.session.state.value, "recoverable, not ERROR")
        assertTrue(r.events.contains(SessionEvent.DashSilent))
        assertTrue(r.wire.closed)
        r.close()
    }

    @Test
    fun `a packet on the ninth second resets the watchdog`() = runTest {
        val r = rig()
        reachReady(r)

        advanceTimeBy(9_000)
        r.wire.deliver(incoming(0x0C, 0x08, byteArrayOf(0x00, 0x00)))
        runCurrent()
        advanceTimeBy(9_000)
        runCurrent()

        assertEquals(DashState.READY, r.session.state.value)
        assertFalse(r.events.contains(SessionEvent.DashSilent))
        r.close()
    }

    @Test
    fun `the watchdog does not run before auth — a dash may take nineteen seconds to answer`() =
        runTest {
            // 2026-09-18, Huawei: the request went out at 9:40:42 and the dash answered at
            // 9:41:01. A watchdog armed from the first packet would have killed that session
            // at ten seconds, every time.
            val r = rig()
            advanceTimeBy(200)
            runCurrent()

            advanceTimeBy(12_000)
            runCurrent()

            assertEquals(DashState.AUTHENTICATING, r.session.state.value)
            assertTrue(r.events.isEmpty(), "no DashSilent while the handshake is still open")
            r.close()
        }

    @Test
    fun `a socket error ends the session and says so once`() = runTest {
        val r = rig()
        reachReady(r)

        r.wire.close()
        runCurrent()

        assertEquals(DashState.IDLE, r.session.state.value)
        val failed = assertIs<SessionEvent.Failed>(r.events.last())
        assertEquals("Lost connection to dash", failed.reason)
        r.close()
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    @Test
    fun `a button is acknowledged before it is reported`() = runTest {
        val r = rig()
        reachReady(r)
        r.wire.clearSent()

        r.wire.deliver(incoming(0x09, 0x00, byteArrayOf(0x00, 0x01, 0x22)))
        // runCurrent, not advanceUntilIdle: the ack must be on the wire by the time the
        // event reaches anyone, without a tick passing to help it.
        runCurrent()

        assertEquals(
            enc(DashCommand.ButtonAck(0x22)),
            r.wire.sent.first().bytes.toHex(),
            "the ack is the first thing out — invariant 7",
        )
        assertEquals(SessionEvent.Button(0x22), r.events.last())
        r.close()
    }

    // ── One session at a time ─────────────────────────────────────────────

    @Test
    fun `a closed session cannot write to a later one's transport`() = runTest {
        val first = rig()
        reachReady(first)
        first.session.close(farewell = false)
        runCurrent()

        val second = rig()
        reachReady(second)
        val firstTail = first.wire.sent.size

        advanceStreaming(second, 5_000)

        assertEquals(firstTail, first.wire.sent.size, "the closed session writes nowhere")
        assertTrue(first.wire.closed)
        assertFalse(second.wire.closed, "and has not touched the live one")
        assertTrue(second.wire.sent.size > firstTail, "which is the one still talking")
        second.close()
    }

    @Test
    fun `a transport that will not open fails the session instead of throwing at the caller`() =
        runTest {
            val parent = Job()
            val events = mutableListOf<SessionEvent>()
            val session = DashSession.open(
                ssid = ssid,
                chrome = MutableStateFlow(DashChrome()),
                clock = { testScheduler.currentTime },
                parent = parent,
                context = StandardTestDispatcher(testScheduler),
            ) { throw java.net.BindException("Address already in use") }
            backgroundScope.launch { session.events.toList(events) }
            runCurrent()

            assertEquals(DashState.ERROR, session.state.value)
            val failed = assertIs<SessionEvent.Failed>(events.single())
            assertTrue(failed.reason.contains("Address already in use"), failed.reason)
            parent.cancel()
        }

    /**
     * The one test on the real clock, and the one that would have caught the 2026-09-19
     * deadlock in review instead of after it.
     *
     * Everything else here runs on virtual time, where "close() never returns" shows up as a
     * build that hangs — which is not a red test, it is a stuck CI job nobody can read. This
     * one runs the session on a real dispatcher and puts a wall-clock bound on the close, so
     * the failure has a name.
     *
     * What it guards: [DashSession.close] cancels the scope and joins it, and the receive
     * loop inside that scope is parked in a `receive()` that only closing the transport can
     * wake. Close the transport after the join and the two wait on each other forever.
     */
    @Test
    fun `close returns, though only closing the transport can end the receive loop`() =
        runBlocking {
            val wire = FakeDashTransport { System.currentTimeMillis() }
            val parent = Job()
            val session = DashSession.open(
                ssid = ssid,
                chrome = MutableStateFlow(DashChrome()),
                clock = { System.currentTimeMillis() },
                parent = parent,
                context = Dispatchers.Default,
            ) { wire }
            // Far enough in for the receive loop to be parked: the burst is 180 ms.
            delay(300)

            val closed = withTimeoutOrNull(5_000) { session.close(farewell = false) }

            assertTrue(closed != null, "close() must not wait on a receive only it can unblock")
            assertTrue(wire.closed, "and the sockets are released")
            parent.cancel()
        }

    // ── Rig helpers ───────────────────────────────────────────────────────

    private fun enc(cmd: DashCommand) = K1GCodec.encode(cmd).toHex()

    private fun rsaKey(): RSAPublicKey = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(1024) }.generateKeyPair().public as RSAPublicKey

    /** Feeds the dash's RSA halves and returns the key it offered. */
    private fun offerPubKey(r: Rig): RSAPublicKey {
        val pub = rsaKey()
        r.wire.deliver(incoming(0x07, 0x00, pub.modulus.toByteArray()))
        r.wire.deliver(incoming(0x07, 0x03, pub.publicExponent.toByteArray()))
        return pub
    }

    private fun TestScope.reachReady(r: Rig) {
        advanceTimeBy(200)          // the burst: nine packets 20 ms apart
        runCurrent()
        offerPubKey(r)
        runCurrent()
        r.wire.deliver(incoming(0x07, 0x01, byteArrayOf(0x01, 0x02)))
        advanceTimeBy(1_800)        // nav entry: 1790 ms of captured pauses
        runCurrent()
        check(r.session.state.value == DashState.READY) {
            "setup: expected READY, got ${r.session.state.value}"
        }
    }

    /**
     * Advance while the dash keeps talking.
     *
     * `advanceTimeBy` alone is a dash that has gone silent, and past ten seconds the watchdog
     * is right to end the session — so any window longer than that has to include traffic, the
     * way a real link does (the 2026-09-13 log: ~7 packets a second, worst gap 1010 ms).
     */
    private fun TestScope.advanceStreaming(r: Rig, ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 1_000L)
            advanceTimeBy(step)
            runCurrent()
            r.wire.deliver(incoming(0x0C, 0x08, byteArrayOf(0x00, 0x00)))
            runCurrent()
            left -= step
        }
    }

    private suspend fun Rig.close() {
        session.close(farewell = false)
        parent.cancel()
    }

    /** One TLV wrapped in the dash's own datagram shape: len, seg_count, four zeros. */
    private fun incoming(type: Int, sub: Int, value: ByteArray): ByteArray {
        val tlv = byteArrayOf(
            type.toByte(), sub.toByte(),
            (value.size shr 8).toByte(), value.size.toByte(),
        ) + value
        val total = 8 + tlv.size
        return byteArrayOf(
            (total shr 8).toByte(), total.toByte(),
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
        ) + tlv
    }
}
