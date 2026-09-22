package com.opendash.opendash_dash_engine.dash

/**
 * Where the connection to the dash has got to.
 *
 * Five states for what used to be four booleans and three jobs spread across
 * `DashEngineController` — `sessionStarted`, `authRetries`, `giveupJob`, plus two
 * collectors that each held half the answer. The combinations those could express included
 * several nobody wanted: "streaming with no session", "retrying with no Wi-Fi request",
 * "gave up but still counting down". Here they are not representable.
 */
internal sealed interface ConnState {
    /** Nobody has asked for a connection, or the last one was ended deliberately. */
    data object Idle : ConnState

    /** A Wi-Fi request is out. The Wi-Fi layer does its own backoff — see [ReconnectPolicy]. */
    data class WaitingForWifi(val ssid: String) : ConnState

    /**
     * The link is up and a session is being opened or is opening.
     *
     * [authRetries] is how many re-handshakes on this same link have already been spent.
     * It lives in the state rather than in a field because it is only meaningful here:
     * reaching [Streaming] or losing the link both reset it by construction, which is
     * exactly what a field had to be remembered to do.
     */
    data class Handshaking(val ssid: String, val authRetries: Int) : ConnState

    /**
     * The session reached READY and the stream was started.
     *
     * [settled] is the difference between "a stream started" and "a stream is working", and
     * the two must not be confused: entering this state used to reset
     * [Handshaking.authRetries] outright, so a session that reached READY and died a second
     * later spent none of the budget. A dash that accepts the handshake and then drops the
     * stream — the shape a restarting dash has — was retried for ever, `authRetries` never
     * passing 1. So the budget travels THROUGH this state in [authRetries] and is reset only
     * by [ConnEvent.StreamSettled], STREAM_SETTLE_MS after the stream began.
     *
     * The give-up countdown is still cancelled on entry, and deliberately: it is armed from
     * the rider's tap, and the link can legitimately take most of its two minutes to come up
     * (`CONNECT_TIMEOUT` is 30 s per attempt, and [ReconnectPolicy] backs off between them).
     * A stream that starts at t=100 s must not be killed at t=120 s for being younger than
     * its settle window. What bounds a flapping stream is the retry budget above, and what
     * re-arms the countdown once that budget is spent is the [Handshaking] branch.
     */
    data class Streaming(
        val ssid: String,
        val settled: Boolean = false,
        val authRetries: Int = 0,
    ) : ConnState

    /** Stopped trying. Only a fresh [ConnEvent.UserConnect] leaves this state. */
    data class GaveUp(val reason: String) : ConnState
}

/**
 * Everything that can move the connection along, from all four sources that used to be
 * wired separately: the rider, the Wi-Fi layer, the session, and two timers.
 */
internal sealed interface ConnEvent {
    data class UserConnect(val ssid: String) : ConnEvent
    data object UserDisconnect : ConnEvent

    /** The Wi-Fi layer reached CONNECTED. [ssid] is the name it actually joined. */
    data class WifiUp(val ssid: String) : ConnEvent

    /** The link is not up, but the Wi-Fi layer is still trying. */
    data object WifiDown : ConnEvent

    /** The Wi-Fi layer stopped trying — scenario B of spec/wifi_retry_policy.md. */
    data class WifiGaveUp(val reason: String) : ConnEvent

    /** The session finished its handshake and entered nav mode. */
    data object SessionReady : ConnEvent

    /**
     * The session is over, whatever the cause — an auth timeout, the RX watchdog, a socket
     * error. [handshakeRefused] is true only when the link worked and the dash still never
     * completed the handshake; see [SessionEvent.Failed].
     */
    data class SessionEnded(val handshakeRefused: Boolean) : ConnEvent

    /** The re-handshake timer fired. */
    data object AuthRetryDue : ConnEvent

    /**
     * The stream has been up long enough to count as working — see [ConnState.Streaming].
     *
     * A timer, not evidence from the frame loop, and deliberately: the question it answers is
     * "did this session last", which is a duration and nothing else. Wiring it to the first
     * delivered frame would answer "did one frame go out", which a flapping session also
     * manages.
     *
     * It has no effects of its own. All it does is clear the retry budget, which is the one
     * thing a stream that lasted has earned and a stream that did not has not.
     */
    data object StreamSettled : ConnEvent

    /** The give-up timer fired: this long without reaching [ConnState.Streaming]. */
    data object GiveUpDue : ConnEvent
}

/**
 * What the world must be made to do. The reducer decides; something else performs.
 *
 * Everything with a side effect is here, which is the property that makes [reduce] pure and
 * therefore testable: sockets, timers, the Wi-Fi request and the ride file all sit on the
 * far side of this list.
 */
internal sealed interface Effect {
    data class RequestWifi(val ssid: String) : Effect

    /**
     * Let the dash's network go.
     *
     * @param linger keep the platform request alive for a short while first — only the
     *   rider's own «Отключить» asks for this, and it is the fix for 2026-09-22. Releasing
     *   unregisters the `NetworkCallback`, and the next `connect()` therefore has to call
     *   `requestNetwork()` again, which is what raises Android's network-picker dialog: the
     *   guard in `DashWifiManager.connect` that avoids it needs a request that is still
     *   held, and a release makes that branch unreachable by construction. Every one of the
     *   four «Отключить → Подключить» cycles in the 2026-09-22 Huawei ride files went
     *   through this effect, and the rider got a dialog each time.
     *
     *   Not on the give-up paths. "We have stopped trying" and "come straight back" are
     *   opposite intentions, and [StandDown] beside them exists to let go of the phone.
     */
    data class ReleaseWifi(val linger: Boolean) : Effect
    data class OpenSession(val ssid: String) : Effect

    /** @param farewell send `projectionStop`/`projectionOff` first — see `DashSession.close`. */
    data class StopSession(val farewell: Boolean) : Effect
    data object StartStream : Effect
    data object ArmGiveUp : Effect
    data object CancelGiveUp : Effect
    data object ArmAuthRetry : Effect

    /** Start the countdown after which a stream counts as working — [ConnEvent.StreamSettled]. */
    data object ArmSettle : Effect

    /**
     * Stand that countdown down. Every exit from [ConnState.Streaming] emits it.
     *
     * Without it a stream that ends at 19 s and a new one that starts at 20 s would be told
     * "settled" by the first one's timer, which is exactly the case the timer exists to catch.
     */
    data object CancelSettle : Effect

    /**
     * Stand the re-handshake timer down.
     *
     * Every exit from [ConnState.Handshaking] emits it. Without it a link flap —
     * Streaming, session ends, link drops, link returns — leaves the timer armed from the
     * old attempt, and it fires a second `OpenSession` on top of one already opening.
     */
    data object CancelAuthRetry : Effect

    /**
     * The dash refused a handshake on a name taken from a scan, so that network is not it.
     *
     * A no-op when the name came from the rider — only the Wi-Fi layer knows which it was.
     * Carried as an effect because the verdict is the session's: association succeeded and
     * nothing below the K1G handshake can tell "the wrong Royal Enfield" from "the right
     * one having a bad day".
     */
    data object RejectSsidGuess : Effect

    /** One line for the rider and the ride file. */
    data class Report(val reason: String) : Effect

    /**
     * The connection is over for good — let go of everything that is not the session or the
     * link: the foreground service and its wake locks, GPS, media forwarding, the per-minute
     * memory probe, and the ride file.
     *
     * Emitted only on the two give-up paths. Before the reducer existed, the give-up timer
     * called `disconnect()` and got all of this for free; moving the decision here without
     * moving the work left a phone that had stopped trying still holding a PARTIAL_WAKE_LOCK
     * and a GPS fix — indefinitely, which is the battery cut-off RECONNECT_GIVEUP_MS exists
     * to be.
     *
     * It carries no reason, and that is a finding rather than an omission. Review asked for
     * one on the grounds that [ReleaseWifi] runs first and `DashWifiManager.disconnect` ends
     * with `_state.value = WifiState()`, wiping `wifiError` before the rider could be told
     * why. Both halves of that are true and it still changes nothing: `publishState` fires
     * again immediately after the last effect, with no error on it, and — checked, 2026-09-22
     * — NOTHING in `lib/` renders `DashEngineState.wifiError` or `.errorMessage` at all.
     * There is no rider-facing message to preserve. The gap is real and is recorded in
     * network-refactoring.md; inventing a banner here would be a different change.
     * [Report] puts the reason in the ride file, which is where it can currently be read.
     */
    data object StandDown : Effect
}

/**
 * The whole connection, as one function.
 *
 * `reduce(state, event) -> (state, effects)`; no Android types, no clock, no randomness, so
 * every scenario in `spec/wifi_retry_policy.md` is a unit test rather than a ride.
 *
 * **What it deliberately does not decide.** How long to wait before the next Wi-Fi request
 * ([ReconnectPolicy] owns that, and the Wi-Fi layer retries under [ConnState.WaitingForWifi]
 * without telling anyone), and how long the two timers are. Timers are armed and cancelled
 * as effects; their firing comes back as an event.
 */
internal object ConnectionFsm {

    /**
     * Re-handshakes allowed on one link before the state is left to the give-up timer.
     *
     * Four, carried over from `DashEngineController.MAX_AUTH_RETRIES`. It bounds scenario C
     * — the dash silent behind a healthy Wi-Fi link — where nothing else would: the Wi-Fi
     * layer sees no fault at all and will never retry on its own.
     */
    const val MAX_AUTH_RETRIES = 4

    fun reduce(state: ConnState, event: ConnEvent): Pair<ConnState, List<Effect>> = when (state) {
        is ConnState.Idle -> when (event) {
            is ConnEvent.UserConnect -> restart(event.ssid)
            else -> state to emptyList()
        }

        is ConnState.WaitingForWifi -> when (event) {
            // The name the layer joined, not the one asked for: prefix discovery resolves
            // an exact SSID on the way up, and everything downstream needs that one.
            is ConnEvent.WifiUp ->
                ConnState.Handshaking(event.ssid, authRetries = 0) to
                    listOf(Effect.OpenSession(event.ssid))
            is ConnEvent.WifiGaveUp -> wifiGaveUp(event.reason)
            is ConnEvent.GiveUpDue -> giveUp(GIVE_UP_REASON)
            is ConnEvent.UserDisconnect -> disconnect(farewell = false)
            // Tapping "connect" while it is still searching starts the search again, which
            // is what the controller does today: its no-op guard wants a live session AND a
            // connected link, and here there is neither. A button that does nothing visible
            // is how "it hung" gets reported.
            is ConnEvent.UserConnect -> restart(event.ssid)
            // Still trying — the Wi-Fi layer's own business, and it has not stopped.
            else -> state to emptyList()
        }

        is ConnState.Handshaking -> when (event) {
            // The budget goes WITH us, which is the fix of 2026-09-22: it used to be
            // dropped here, and a dash that accepts every handshake and drops every stream
            // therefore had no bound at all. CancelGiveUp stays — see [ConnState.Streaming]
            // for why the countdown must not outlive the rider's tap into a healthy stream.
            is ConnEvent.SessionReady ->
                ConnState.Streaming(state.ssid, settled = false, authRetries = state.authRetries) to
                    listOf(
                        Effect.StartStream,
                        Effect.CancelGiveUp,
                        Effect.CancelAuthRetry,
                        Effect.ArmSettle,
                    )

            // The link is fine and the session is not — retry the handshake on the same
            // network, which is the only recovery scenario C has. Past the budget the state
            // simply waits: the give-up timer is still armed and is what ends it.
            is ConnEvent.SessionEnded -> {
                // A refusal is evidence about the NAME, and only here: the link worked and
                // the dash still would not finish. Emitted before the retry — the Wi-Fi
                // layer answers it by re-requesting, which arrives as WifiDown and stands
                // the retry down again; with no guess in play it is a no-op and the retry
                // proceeds as before.
                val reject: List<Effect> =
                    if (event.handshakeRefused) listOf(Effect.RejectSsidGuess) else emptyList()
                if (state.authRetries < MAX_AUTH_RETRIES) {
                    state to reject + Effect.ArmAuthRetry
                } else {
                    // ArmGiveUp, and it is not redundant. On the ordinary path the countdown
                    // is already running and this is a no-op; on the path through a stream
                    // that never settled it is NOT — [ConnState.Streaming] cancelled it on
                    // the way in. Without this the machine would sit here in silence with
                    // nothing left to end it.
                    state to reject + Effect.ArmGiveUp
                }
            }

            // Guarded, so that the budget is a property of THIS function and not of the
            // controller remembering not to arm a timer. Today nothing produces this event
            // past the budget — [Effect.ArmAuthRetry] is the only source and the branch
            // above stops emitting it — but "the bound holds because the caller behaves" is
            // exactly the kind of invariant the reducer exists to stop having.
            is ConnEvent.AuthRetryDue ->
                if (state.authRetries >= MAX_AUTH_RETRIES) state to emptyList()
                else state.copy(authRetries = state.authRetries + 1) to
                    listOf(Effect.OpenSession(state.ssid))

            // The network went away under a half-open session. No farewell: the packets
            // could only go into a socket nobody reads.
            is ConnEvent.WifiDown ->
                ConnState.WaitingForWifi(state.ssid) to
                    listOf(Effect.StopSession(farewell = false), Effect.CancelAuthRetry)

            // The link came up again under a name we are NOT handshaking with. That is
            // prefix discovery correcting itself: a capabilities update names the network
            // the platform would not name at `onAvailable`, and the dash checks that name
            // inside the encrypted handshake — so the session in flight is talking to it
            // under a name it will refuse. Restarting the session costs a second; ignoring
            // the correction costs the full 15 s auth timeout first.
            is ConnEvent.WifiUp ->
                if (event.ssid == state.ssid) {
                    state to emptyList()
                } else {
                    ConnState.Handshaking(event.ssid, authRetries = 0) to listOf(
                        Effect.StopSession(farewell = false),
                        Effect.CancelAuthRetry,
                        Effect.OpenSession(event.ssid),
                    )
                }

            is ConnEvent.WifiGaveUp -> wifiGaveUp(event.reason)

            is ConnEvent.GiveUpDue -> giveUp(GIVE_UP_REASON)
            is ConnEvent.UserDisconnect -> disconnect(farewell = true)
            // NOT a no-op here, unlike [ConnState.Streaming]. Once the retry budget is
            // spent this state does nothing at all until the give-up timer, and a rider
            // pressing "connect" into that would get nothing for up to two minutes — while
            // the controller had already opened a fresh ride file on their behalf. "Live
            // connection" in spec/fsm.md means one that is working, not one that is trying.
            is ConnEvent.UserConnect -> restart(event.ssid)
            else -> state to emptyList()
        }

        is ConnState.Streaming -> when (event) {
            // The stream lasted, so the budget it spent getting here is forgiven. No
            // effects: the countdown was cancelled on the way in, and nothing else is owed.
            is ConnEvent.StreamSettled -> state.copy(settled = true, authRetries = 0) to emptyList()

            // Scenario C: the dash stopped answering behind a Wi-Fi link that is still up.
            // Nothing below notices, so the retry budget continues here — unspent if this
            // stream lasted, carried if it did not, which is the whole difference between a
            // connection that broke and one that never worked. ArmGiveUp because entering
            // Streaming cancelled the countdown and something has to end this.
            is ConnEvent.SessionEnded -> {
                val budget = if (state.settled) 0 else state.authRetries
                val base = listOf(Effect.CancelSettle, Effect.ArmGiveUp)
                ConnState.Handshaking(state.ssid, budget) to
                    if (budget < MAX_AUTH_RETRIES) base + Effect.ArmAuthRetry else base
            }

            is ConnEvent.WifiDown ->
                ConnState.WaitingForWifi(state.ssid) to
                    listOf(Effect.StopSession(farewell = false), Effect.CancelSettle, Effect.ArmGiveUp)

            is ConnEvent.WifiGaveUp -> wifiGaveUp(event.reason)

            is ConnEvent.UserDisconnect -> disconnect(farewell = true)
            // Including UserConnect: spec/fsm.md promises "connect() — no-op" on a live
            // connection, and it is not a promise about tidiness. Re-running the cycle tore
            // down the Wi-Fi link the running session's sockets are bound to.
            else -> state to emptyList()
        }

        is ConnState.GaveUp -> when (event) {
            is ConnEvent.UserConnect -> restart(event.ssid)
            // "Отключить" has to work here too. Giving up stops the retrying; it does not
            // undo the WifiNetworkSpecifier request, and while that is registered the phone
            // stays on the dash's no-internet network with nothing using it.
            is ConnEvent.UserDisconnect -> ConnState.Idle to listOf(Effect.ReleaseWifi(linger = false))
            else -> state to emptyList()
        }
    }

    /**
     * Start the whole cycle again, from wherever we were.
     *
     * [Effect.CancelGiveUp] before [Effect.ArmGiveUp] because arming is idempotent — it
     * no-ops while a countdown is already running — so without the cancel a second tap
     * inherits the remains of the first attempt's two minutes. A tap at t=115s would be
     * killed five seconds later, which is the defect the controller's own
     * `cancelGiveupTimer()` used to prevent before that call moved in here.
     *
     * **No [Effect.ReleaseWifi], and that is the whole of the 2026-09-20 Huawei finding.**
     * It stood here for one day and had to go: releasing unregisters the `NetworkCallback`,
     * and re-registering one is what makes Android raise
     * `NetworkRequestDialogActivity` — confirmed in the field 2026-08-27, where it appeared
     * in exactly the one reconnect of nineteen that went through a release, and nowhere
     * else (spec/wifi_retry_policy.md, scenario D). With the release in place every tap of
     * "Подключить" took that path, so the rider got a system dialog in the case they hit
     * most often: dash restarted, session dead, Wi-Fi still up.
     *
     * Nothing is lost by leaving it out. `DashWifiManager.connect` decides for itself
     * whether to reuse the live request or build a new one, and its "keeping the live
     * request" branch is the only thing that avoids the dialog — a release beforehand makes
     * that branch unreachable by construction.
     *
     * **What that branch costs, and why it still works.** Reusing the request returns
     * without touching the Wi-Fi layer's `StateFlow`, so no `WifiUp` is emitted for it. The
     * restart would sit in [ConnState.WaitingForWifi] for ever if the state were the only
     * source — it is not: `DashEngineController.connect` relaunches the Wi-Fi feed, and a
     * fresh `StateFlow` collector is handed the current CONNECTED value. That holds only
     * because the controller queues `UserConnect` before either feed coroutine can run,
     * which is true while `connect()` is called on the main thread and the scope is
     * `Dispatchers.Main` rather than `.immediate`.
     */
    private fun restart(ssid: String) = ConnState.WaitingForWifi(ssid) to listOf(
        Effect.StopSession(farewell = false),
        Effect.CancelAuthRetry,
        Effect.CancelGiveUp,
        Effect.RequestWifi(ssid),
        Effect.ArmGiveUp,
    )

    /**
     * Ending it deliberately: stop the session first, then let the link go.
     *
     * The order is the point, and it is scenario D. `StopSession` carries the farewell —
     * two packets that have to reach the dash before its socket closes, or it sits on the
     * last frame until its own timeout — and releasing the Wi-Fi first would take the
     * network those packets travel on.
     */
    private fun disconnect(farewell: Boolean) = ConnState.Idle to listOf(
        Effect.StopSession(farewell),
        Effect.ReleaseWifi(linger = true),
        Effect.CancelGiveUp,
        Effect.CancelAuthRetry,
        Effect.CancelSettle,
    )

    /**
     * The Wi-Fi layer stopped trying — scenario B, and an exit nothing else can drive.
     *
     * [Effect.ReleaseWifi] is not decoration: the layer clears its own `wantConnected` but
     * leaves the request registered, so without this the rider is left on a no-internet
     * network that nothing is using and nothing will drop.
     */
    private fun wifiGaveUp(reason: String) = ConnState.GaveUp(reason) to listOf(
        Effect.StopSession(farewell = false),
        Effect.ReleaseWifi(linger = false),
        Effect.CancelGiveUp,
        Effect.CancelAuthRetry,
        Effect.CancelSettle,
        Effect.Report(reason),
        Effect.StandDown,
    )

    private fun giveUp(reason: String) = ConnState.GaveUp(reason) to listOf(
        Effect.StopSession(farewell = false),
        Effect.ReleaseWifi(linger = false),
        Effect.CancelAuthRetry,
        Effect.CancelSettle,
        Effect.Report(reason),
        Effect.StandDown,
    )

    /**
     * "Working", not "reached STREAMING", and the difference is a statement that has to stay
     * true of every ride this line can appear on.
     *
     * The countdown is cancelled on entry to [ConnState.Streaming] and re-armed by the
     * spent-budget branch of [ConnState.Handshaking], so it can now end a ride where the dash
     * accepted five handshakes and every stream died inside its settle window. "Never reached
     * STREAMING" would be false about that ride, and it was already false about a real one:
     * the 2026-09-22 Huawei log carries this line after twenty-four minutes of streaming.
     */
    private const val GIVE_UP_REASON = "gave up — too long without a working stream"
}

/**
 * Short, stable names for the ride file.
 *
 * Separate from `toString()` on purpose. The generated one prints every field, which here
 * means the SSID — already on the `[wifi]` line beside it, so a second copy is noise — and
 * it changes shape whenever a field is added, which breaks a log a human greps. These
 * labels carry exactly the part that is not visible anywhere else: which state, for
 * [ConnState.Handshaking] how much of the retry budget is spent, and for
 * [ConnState.Streaming] whether the stream has outlived its settle window and how much of
 * the budget it inherited. `Streaming?#3` is a stream that has not lasted yet, started on the
 * fourth handshake of this link — the shape a flap loop has, and the one thing the rest of
 * the ride file cannot show, since every other line about it looks like a healthy stream.
 * `grep Streaming` still finds both.
 */
internal val ConnState.label: String
    get() = when (this) {
        is ConnState.Idle -> "Idle"
        is ConnState.WaitingForWifi -> "WaitingForWifi"
        is ConnState.Handshaking -> "Handshaking#$authRetries"
        is ConnState.Streaming -> if (settled) "Streaming" else "Streaming?#$authRetries"
        is ConnState.GaveUp -> "GaveUp"
    }

internal val ConnEvent.label: String
    get() = when (this) {
        is ConnEvent.UserConnect -> "UserConnect"
        is ConnEvent.UserDisconnect -> "UserDisconnect"
        is ConnEvent.WifiUp -> "WifiUp"
        is ConnEvent.WifiDown -> "WifiDown"
        is ConnEvent.WifiGaveUp -> "WifiGaveUp"
        is ConnEvent.SessionReady -> "SessionReady"
        // The one flag worth carrying: it is what decides whether an SSID gets blacklisted.
        is ConnEvent.SessionEnded -> if (handshakeRefused) "SessionEnded(refused)" else "SessionEnded"
        is ConnEvent.AuthRetryDue -> "AuthRetryDue"
        is ConnEvent.StreamSettled -> "StreamSettled"
        is ConnEvent.GiveUpDue -> "GiveUpDue"
    }

internal val Effect.label: String
    get() = when (this) {
        is Effect.RequestWifi -> "RequestWifi"
        is Effect.ReleaseWifi -> if (linger) "ReleaseWifi(linger)" else "ReleaseWifi"
        is Effect.OpenSession -> "OpenSession"
        is Effect.StopSession -> if (farewell) "StopSession(farewell)" else "StopSession"
        is Effect.StartStream -> "StartStream"
        is Effect.ArmGiveUp -> "ArmGiveUp"
        is Effect.CancelGiveUp -> "CancelGiveUp"
        is Effect.ArmAuthRetry -> "ArmAuthRetry"
        is Effect.ArmSettle -> "ArmSettle"
        is Effect.CancelSettle -> "CancelSettle"
        is Effect.CancelAuthRetry -> "CancelAuthRetry"
        is Effect.RejectSsidGuess -> "RejectSsidGuess"
        is Effect.Report -> "Report"
        is Effect.StandDown -> "StandDown"
    }
