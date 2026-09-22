package com.opendash.opendash_dash_engine.dash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every scenario in `spec/wifi_retry_policy.md`, as a test instead of a ride.
 *
 * That is the whole reason [ConnectionFsm] exists. These four flows used to be spread over
 * two collectors, four fields and three jobs in `DashEngineController`, where the only way
 * to check one was to take the bike out — and a ride can show that something went wrong far
 * more easily than it can show which of the four flows was supposed to run.
 */
class ConnectionFsmTest {

    /** Runs a whole flow, returning the final state and every effect in order. */
    private fun run(
        from: ConnState,
        vararg events: ConnEvent,
    ): Pair<ConnState, List<Effect>> {
        var state = from
        val effects = mutableListOf<Effect>()
        for (event in events) {
            val (next, fx) = ConnectionFsm.reduce(state, event)
            state = next
            effects += fx
        }
        return state to effects
    }

    private fun effectsOf(state: ConnState, event: ConnEvent) =
        ConnectionFsm.reduce(state, event).second

    private val ssid = "RE_9CP9_250218"

    // ── Scenario A: the link dropped after a good connection ──────────────

    @Test
    fun `A - a link lost mid-stream comes back without the rider`() {
        val (state, effects) = run(
            ConnState.Idle,
            ConnEvent.UserConnect(ssid),
            ConnEvent.WifiUp(ssid),
            ConnEvent.SessionReady,
            // Out of range. The Wi-Fi layer retries on its own schedule; the FSM only has
            // to keep the session out of the way and be ready for the link's return.
            ConnEvent.WifiDown,
            ConnEvent.WifiUp(ssid),
            ConnEvent.SessionReady,
        )

        assertEquals(ConnState.Streaming(ssid), state)
        assertEquals(
            listOf(
                Effect.StopSession(farewell = false),
                Effect.CancelAuthRetry, Effect.CancelGiveUp,
                Effect.RequestWifi(ssid), Effect.ArmGiveUp,
                Effect.OpenSession(ssid),
                Effect.StartStream, Effect.CancelGiveUp, Effect.CancelAuthRetry,
                Effect.StopSession(farewell = false), Effect.ArmGiveUp,
                Effect.OpenSession(ssid),
                Effect.StartStream, Effect.CancelGiveUp, Effect.CancelAuthRetry,
            ),
            effects,
        )
    }

    @Test
    fun `A - the name the link came up on is the one the session gets`() {
        // Prefix discovery resolves the exact SSID on the way up, and the dash checks that
        // name inside the handshake — asking for 'RE_' and handshaking with 'RE_' fails.
        val (state, effects) = run(
            ConnState.Idle,
            ConnEvent.UserConnect("RE_"),
            ConnEvent.WifiUp("RE_9CP9_250218"),
        )

        assertEquals(ConnState.Handshaking("RE_9CP9_250218", authRetries = 0), state)
        assertTrue(Effect.OpenSession("RE_9CP9_250218") in effects)
    }

    // ── Scenario B: it never came up at all ───────────────────────────────

    @Test
    fun `B - a link that never came up ends in GaveUp, not in retrying for ever`() {
        // The count of Wi-Fi attempts belongs to ReconnectPolicy; what reaches the FSM is
        // its verdict. Until that verdict arrives the FSM sits still — those WifiDown
        // events are the layer reporting that it is still working.
        val (state, effects) = run(
            ConnState.Idle,
            ConnEvent.UserConnect(ssid),
            ConnEvent.WifiDown,
            ConnEvent.WifiDown,
            ConnEvent.WifiGaveUp("2 attempts and never connected"),
        )

        val gaveUp = assertIs<ConnState.GaveUp>(state)
        assertEquals("2 attempts and never connected", gaveUp.reason)
        assertEquals(
            listOf(
                Effect.StopSession(farewell = false),
                Effect.CancelAuthRetry, Effect.CancelGiveUp,
                Effect.RequestWifi(ssid), Effect.ArmGiveUp,
                Effect.StopSession(farewell = false),
                Effect.ReleaseWifi,
                Effect.CancelGiveUp,
                Effect.CancelAuthRetry,
                Effect.Report("2 attempts and never connected"),
                Effect.StandDown,
            ),
            effects,
        )
    }

    @Test
    fun `B - only the rider gets out of GaveUp`() {
        val stuck = ConnState.GaveUp("2 attempts and never connected")
        for (event in listOf(
            ConnEvent.WifiUp(ssid),
            ConnEvent.WifiDown,
            ConnEvent.SessionReady,
            ConnEvent.SessionEnded(handshakeRefused = false),
            ConnEvent.AuthRetryDue,
            ConnEvent.GiveUpDue,
        )) {
            assertEquals(stuck to emptyList<Effect>(), ConnectionFsm.reduce(stuck, event), "$event")
        }

        val (state, effects) = run(stuck, ConnEvent.UserConnect(ssid))
        assertEquals(ConnState.WaitingForWifi(ssid), state)
        assertEquals(
            listOf(
                Effect.StopSession(farewell = false),
                Effect.CancelAuthRetry, Effect.CancelGiveUp,
                Effect.RequestWifi(ssid), Effect.ArmGiveUp,
            ),
            effects,
        )
    }

    // ── Scenario C: the dash went quiet behind a healthy link ─────────────

    @Test
    fun `C - a dash that stops answering is retried on the same link, four times`() {
        var state: ConnState = ConnState.Streaming(ssid)
        val opens = mutableListOf<Effect>()

        // First failure comes from Streaming, the rest from Handshaking.
        val (next, fx) = ConnectionFsm.reduce(state, ConnEvent.SessionEnded(handshakeRefused = false))
        state = next
        assertEquals(listOf(Effect.ArmGiveUp, Effect.ArmAuthRetry), fx, "the timer was cancelled on Streaming")

        repeat(ConnectionFsm.MAX_AUTH_RETRIES) {
            val (afterRetry, retryFx) = ConnectionFsm.reduce(state, ConnEvent.AuthRetryDue)
            state = afterRetry
            opens += retryFx
            val (afterEnd, endFx) = ConnectionFsm.reduce(state, ConnEvent.SessionEnded(handshakeRefused = false))
            state = afterEnd
            // The fourth failure is the one that stops asking.
            val expected: List<Effect> =
                if ((state as ConnState.Handshaking).authRetries < ConnectionFsm.MAX_AUTH_RETRIES) {
                    listOf(Effect.ArmAuthRetry)
                } else {
                    emptyList()
                }
            assertEquals(expected, endFx, "after retry #${(state as ConnState.Handshaking).authRetries}")
        }

        assertEquals<List<Effect>>(
            List(ConnectionFsm.MAX_AUTH_RETRIES) { Effect.OpenSession(ssid) },
            opens,
        )
        assertEquals(ConnState.Handshaking(ssid, ConnectionFsm.MAX_AUTH_RETRIES), state)
    }

    @Test
    fun `C - the budget spent, the give-up timer is what ends it`() {
        val spent = ConnState.Handshaking(ssid, ConnectionFsm.MAX_AUTH_RETRIES)

        assertEquals(
            emptyList<Effect>(),
            effectsOf(spent, ConnEvent.SessionEnded(handshakeRefused = false)),
        )

        val (state, effects) = ConnectionFsm.reduce(spent, ConnEvent.GiveUpDue)
        assertIs<ConnState.GaveUp>(state)
        assertEquals(
            listOf(
                Effect.StopSession(farewell = false),
                Effect.ReleaseWifi,
                Effect.CancelAuthRetry,
                Effect.Report((state as ConnState.GaveUp).reason),
                Effect.StandDown,
            ),
            effects,
        )
    }

    @Test
    fun `C - reaching the stream clears the budget`() {
        // Otherwise a ride with five separate dash restarts would end on the fifth, having
        // recovered from the first four.
        val recovered = ConnState.Handshaking(ssid, authRetries = 3)
        val (streaming, _) = ConnectionFsm.reduce(recovered, ConnEvent.SessionReady)
        val (again, _) = ConnectionFsm.reduce(streaming, ConnEvent.SessionEnded(handshakeRefused = false))

        assertEquals(ConnState.Handshaking(ssid, authRetries = 0), again)
    }

    // ── Scenario D: the rider does it by hand ─────────────────────────────

    @Test
    fun `D - disconnect stops the session before the link goes, and with a farewell`() {
        // The order is the whole scenario: the farewell is two packets that must reach the
        // dash before its socket closes, and releasing the Wi-Fi first takes the network
        // they travel on. Without them the dash sits on its last frame until its own
        // timeout — what a rider reads as "the map froze".
        val (state, effects) = ConnectionFsm.reduce(ConnState.Streaming(ssid), ConnEvent.UserDisconnect)

        assertEquals(ConnState.Idle, state)
        assertEquals(
            listOf(
                Effect.StopSession(farewell = true),
                Effect.ReleaseWifi,
                Effect.CancelGiveUp,
                Effect.CancelAuthRetry,
            ),
            effects,
        )
    }

    @Test
    fun `D - and the connect that follows starts a clean cycle`() {
        val (state, effects) = run(
            ConnState.Streaming(ssid),
            ConnEvent.UserDisconnect,
            ConnEvent.UserConnect(ssid),
        )

        assertEquals(ConnState.WaitingForWifi(ssid), state)
        assertEquals(
            listOf(
                Effect.StopSession(farewell = true), Effect.ReleaseWifi,
                Effect.CancelGiveUp, Effect.CancelAuthRetry,
                Effect.StopSession(farewell = false),
                Effect.CancelAuthRetry, Effect.CancelGiveUp,
                Effect.RequestWifi(ssid), Effect.ArmGiveUp,
            ),
            effects,
        )
    }

    @Test
    fun `D - a half-open session is dropped without a farewell`() {
        // Nothing has authenticated, so there is nothing that would act on the two packets.
        val (state, effects) =
            ConnectionFsm.reduce(ConnState.WaitingForWifi(ssid), ConnEvent.UserDisconnect)

        assertEquals(ConnState.Idle, state)
        assertEquals(
            listOf(
                Effect.StopSession(farewell = false),
                Effect.ReleaseWifi,
                Effect.CancelGiveUp,
                Effect.CancelAuthRetry,
            ),
            effects,
        )
    }

    // ── The three extra cases the plan names ──────────────────────────────

    @Test
    fun `connect on a live connection does nothing at all`() {
        // spec/fsm.md promises "connect() — no-op" here, and it is not a promise about
        // tidiness: re-running the cycle tore down the Wi-Fi link the running session's
        // sockets are bound to, and the rider's "Send to Dash" killed the picture.
        assertEquals(
            emptyList<Effect>(),
            effectsOf(ConnState.Streaming(ssid), ConnEvent.UserConnect(ssid)),
        )
    }

    @Test
    fun `connect while still trying starts the cycle over`() {
        // Only a WORKING connection is left alone. Trying is not working: once the retry
        // budget is spent, Handshaking does nothing at all until the give-up timer, and a
        // rider pressing the button into that would get two minutes of silence — while the
        // controller had already opened a fresh ride file on their behalf.
        val restart = listOf(
                Effect.StopSession(farewell = false),
                Effect.CancelAuthRetry, Effect.CancelGiveUp,
                Effect.RequestWifi(ssid), Effect.ArmGiveUp,
        )
        assertEquals(restart, effectsOf(ConnState.WaitingForWifi(ssid), ConnEvent.UserConnect(ssid)))
        assertEquals(
            restart,
            effectsOf(ConnState.Handshaking(ssid, authRetries = 4), ConnEvent.UserConnect(ssid)),
        )
    }

    @Test
    fun `giving up lets go of the phone, not just of the dash`() {
        // The give-up timer used to call disconnect() and got this for free. With the
        // decision in the reducer the work had to come too, or a phone that has stopped
        // trying keeps a PARTIAL_WAKE_LOCK, a GPS fix, media forwarding and an open ride
        // file for as long as the app lives — the exact battery cut-off the 120 s exists
        // to be.
        for (event in listOf(ConnEvent.GiveUpDue, ConnEvent.WifiGaveUp("no dash"))) {
            assertTrue(
                Effect.StandDown in effectsOf(ConnState.Handshaking(ssid, 0), event),
                "$event",
            )
        }
        // Not on the ordinary paths: a reconnect in progress still needs GPS and the
        // service, and a deliberate disconnect has its own teardown.
        assertFalse(Effect.StandDown in effectsOf(ConnState.Streaming(ssid), ConnEvent.UserDisconnect))
        assertFalse(Effect.StandDown in effectsOf(ConnState.Streaming(ssid), ConnEvent.WifiDown))
    }

    @Test
    fun `a restart never releases the WiFi request`() {
        // Releasing unregisters the NetworkCallback, and re-registering one is what makes
        // Android raise its "connect to this network?" dialog — in the field it appeared in
        // exactly the one reconnect of nineteen that went through a release (2026-08-27,
        // spec/wifi_retry_policy.md scenario D). This effect stood in `restart` for one day
        // and cost the rider a dialog on every tap of "Подключить"; DashWifiManager.connect
        // decides for itself whether the live request can be reused, and it cannot if we
        // have just thrown it away.
        for (state in listOf<ConnState>(
            ConnState.Idle,
            ConnState.WaitingForWifi(ssid),
            ConnState.Handshaking(ssid, authRetries = 2),
            ConnState.GaveUp("2 attempts and never connected"),
        )) {
            assertFalse(
                Effect.ReleaseWifi in effectsOf(state, ConnEvent.UserConnect(ssid)),
                "restart from $state released the request",
            )
        }
        // The deliberate paths still do release: a disconnect is the rider saying stop.
        assertTrue(Effect.ReleaseWifi in effectsOf(ConnState.Streaming(ssid), ConnEvent.UserDisconnect))
    }

    @Test
    fun `a second tap does not inherit the first attempt's countdown`() {
        // ArmGiveUp is idempotent — it no-ops while a countdown runs — so a restart has to
        // cancel first. Without it a tap at t=115s of a 120s window is killed five seconds
        // later, which is exactly what the controller's own cancelGiveupTimer() prevented
        // before that call moved into the reducer.
        val effects = effectsOf(ConnState.WaitingForWifi(ssid), ConnEvent.UserConnect(ssid))
        assertTrue(
            effects.indexOf(Effect.CancelGiveUp) < effects.indexOf(Effect.ArmGiveUp),
            "cancel must come first: $effects",
        )
    }

    @Test
    fun `every way out of Handshaking stands the retry timer down`() {
        // A link flap — stream, session ends, link drops, link returns — otherwise leaves
        // the timer armed from the old attempt, and it opens a second session on top of
        // one already opening.
        val mid = ConnState.Handshaking(ssid, authRetries = 1)
        for (event in listOf(
            ConnEvent.SessionReady,
            ConnEvent.WifiDown,
            ConnEvent.WifiGaveUp("gone"),
            ConnEvent.GiveUpDue,
            ConnEvent.UserDisconnect,
        )) {
            assertTrue(Effect.CancelAuthRetry in effectsOf(mid, event), "$event")
        }
    }

    @Test
    fun `a handshake refused on a guessed name withdraws the guess`() {
        // The link worked and the dash still would not finish — that is a statement about
        // the NAME, and it is the only failure that can be.
        val refused = effectsOf(
            ConnState.Handshaking(ssid, authRetries = 0),
            ConnEvent.SessionEnded(handshakeRefused = true),
        )
        assertEquals(listOf(Effect.RejectSsidGuess, Effect.ArmAuthRetry), refused)

        // A socket error or a taken port says nothing about the name.
        assertEquals(
            listOf(Effect.ArmAuthRetry),
            effectsOf(ConnState.Handshaking(ssid, authRetries = 0), ConnEvent.SessionEnded(false)),
        )
    }

    @Test
    fun `giving up still lets the rider drop the network`() {
        // Giving up stops the retrying; it does not undo the WifiNetworkSpecifier request,
        // and while that stands the phone is on a no-internet network nothing is using.
        val (state, effects) =
            ConnectionFsm.reduce(ConnState.GaveUp("2 attempts and never connected"), ConnEvent.UserDisconnect)

        assertEquals(ConnState.Idle, state)
        assertEquals(listOf(Effect.ReleaseWifi), effects)
    }

    @Test
    fun `a name corrected mid-handshake restarts the session on the new one`() {
        // Prefix discovery can name the network only after association, via a capabilities
        // update — and the dash checks that name inside the encrypted handshake. A session
        // already in flight under the old name will be refused, so ignoring the correction
        // costs the full 15 s auth timeout before anything can recover.
        val (state, effects) = ConnectionFsm.reduce(
            ConnState.Handshaking("RE_", authRetries = 2),
            ConnEvent.WifiUp("RE_9CP9_250218"),
        )

        assertEquals(ConnState.Handshaking("RE_9CP9_250218", authRetries = 0), state)
        assertEquals(
            listOf(
                Effect.StopSession(farewell = false),
                Effect.CancelAuthRetry,
                Effect.OpenSession("RE_9CP9_250218"),
            ),
            effects,
        )
    }

    @Test
    fun `the same name arriving again changes nothing`() {
        assertEquals(
            emptyList<Effect>(),
            effectsOf(ConnState.Handshaking(ssid, authRetries = 2), ConnEvent.WifiUp(ssid)),
        )
    }

    @Test
    fun `a link lost during the handshake drops the session without a farewell`() {
        val (state, effects) =
            ConnectionFsm.reduce(ConnState.Handshaking(ssid, authRetries = 2), ConnEvent.WifiDown)

        assertEquals(ConnState.WaitingForWifi(ssid), state)
        assertEquals(
            listOf(Effect.StopSession(farewell = false), Effect.CancelAuthRetry),
            effects,
        )
    }

    @Test
    fun `the give-up timer runs until the stream starts, and not after`() {
        // Armed on every path that leaves a working stream, cancelled the moment one
        // starts. Leaving it armed ends a healthy ride at two minutes; forgetting to
        // re-arm it leaves a dead connection with nothing to end it.
        assertTrue(Effect.ArmGiveUp in effectsOf(ConnState.Idle, ConnEvent.UserConnect(ssid)))
        assertTrue(Effect.CancelGiveUp in effectsOf(ConnState.Handshaking(ssid, 0), ConnEvent.SessionReady))
        assertTrue(Effect.ArmGiveUp in effectsOf(ConnState.Streaming(ssid), ConnEvent.WifiDown))
        assertTrue(
            Effect.ArmGiveUp in effectsOf(ConnState.Streaming(ssid), ConnEvent.SessionEnded(false)),
        )

        assertIs<ConnState.GaveUp>(ConnectionFsm.reduce(ConnState.WaitingForWifi(ssid), ConnEvent.GiveUpDue).first)
        assertIs<ConnState.GaveUp>(ConnectionFsm.reduce(ConnState.Handshaking(ssid, 0), ConnEvent.GiveUpDue).first)
        // Not while streaming: there the timer is not running at all.
        assertEquals(ConnState.Streaming(ssid), ConnectionFsm.reduce(ConnState.Streaming(ssid), ConnEvent.GiveUpDue).first)
    }

    // ── The ride-file labels ──────────────────────────────────────────────

    @Test
    fun `labels carry the state and the retry budget, and never the SSID`() {
        // The SSID is on the `[wifi]` line beside this one; a second copy is noise, and a
        // label that grows a field every time the type does breaks a log a human greps.
        assertEquals("Idle", ConnState.Idle.label)
        assertEquals("WaitingForWifi", ConnState.WaitingForWifi(ssid).label)
        assertEquals("Handshaking#3", ConnState.Handshaking(ssid, authRetries = 3).label)
        assertEquals("Streaming", ConnState.Streaming(ssid).label)
        assertEquals("GaveUp", ConnState.GaveUp("2 attempts and never connected").label)

        for (state in listOf<ConnState>(
            ConnState.WaitingForWifi(ssid),
            ConnState.Handshaking(ssid, 1),
            ConnState.Streaming(ssid),
        )) {
            assertFalse(state.label.contains(ssid), state.label)
        }
    }

    @Test
    fun `the one flag that decides a blacklisting is in the event label`() {
        assertEquals("SessionEnded(refused)", ConnEvent.SessionEnded(handshakeRefused = true).label)
        assertEquals("SessionEnded", ConnEvent.SessionEnded(handshakeRefused = false).label)
        assertEquals("WifiUp", ConnEvent.WifiUp(ssid).label)
        assertFalse(ConnEvent.WifiUp(ssid).label.contains(ssid))
        // And the farewell, which is what tells a deliberate stop from a dropped link.
        assertEquals("StopSession(farewell)", Effect.StopSession(farewell = true).label)
        assertEquals("StopSession", Effect.StopSession(farewell = false).label)
    }

    @Test
    fun `nothing at all happens in Idle except a connect`() {
        for (event in listOf(
            ConnEvent.UserDisconnect,
            ConnEvent.WifiUp(ssid),
            ConnEvent.WifiDown,
            ConnEvent.SessionReady,
            ConnEvent.SessionEnded(handshakeRefused = true),
            ConnEvent.AuthRetryDue,
            ConnEvent.GiveUpDue,
        )) {
            assertEquals(
                ConnState.Idle to emptyList<Effect>(),
                ConnectionFsm.reduce(ConnState.Idle, event),
                "$event",
            )
        }
    }
}
