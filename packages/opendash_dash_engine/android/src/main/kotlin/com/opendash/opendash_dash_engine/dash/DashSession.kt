package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.map.Percentiles
import com.opendash.opendash_dash_engine.dash.protocol.DashCommand
import com.opendash.opendash_dash_engine.dash.protocol.DashMessage
import com.opendash.opendash_dash_engine.dash.protocol.K1GCodec
import com.opendash.opendash_dash_engine.dash.protocol.MalformedCounter
import com.opendash.opendash_dash_engine.dash.protocol.Scripts
import com.opendash.opendash_dash_engine.dash.protocol.Step
import com.opendash.opendash_dash_engine.dash.protocol.timeSyncNow
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

enum class DashState { IDLE, CONNECTING, AUTHENTICATING, READY, STREAMING, ERROR }

/**
 * What a session tells its owner, beyond the state it is in.
 *
 * A mailbox, not a broadcast: these are delivered through a `Channel`, so nothing is lost
 * to a collector that subscribes a moment late. The plan asked for a `SharedFlow`, and with
 * `replay = 0` a [Failed] emitted before the controller's `flatMapLatest` attached would be
 * dropped — the rider would see a session go to ERROR with no message on the Dash screen —
 * while any `replay > 0` would re-deliver a [Button] on re-subscribe, i.e. press it twice.
 * A single-consumer mailbox has neither problem, and there is exactly one consumer.
 */
internal sealed interface SessionEvent {
    /** Handshake complete and nav mode entered — the caller may start streaming. */
    data object Ready : SessionEvent

    /** Joystick press, already acknowledged to the dash. */
    data class Button(val code: Int) : SessionEvent

    /**
     * The session is over and will not recover on its own; [reason] is what to show the
     * rider. The state says which kind: ERROR for a fault of ours (the handshake timed out,
     * a port was taken), IDLE for a link that died under a socket that was working.
     *
     * @param handshakeRefused the link worked and the dash still never completed the
     *   handshake. That is a statement about WHO is on the other end, which is why it is
     *   reported separately: it is the only failure that can incriminate an SSID guessed
     *   from a scan. A socket error or a taken port says nothing about the name.
     */
    data class Failed(
        val reason: String,
        val handshakeRefused: Boolean = false,
    ) : SessionEvent

    /**
     * Nothing has arrived from the dash for [DashSession.RX_IDLE_TIMEOUT_MS].
     *
     * Distinct from [Failed] because the cause is on the far side: the link and our own
     * sockets are as healthy as they were a second ago. The state goes to IDLE, not ERROR —
     * a reconnect is expected to succeed.
     */
    data object DashSilent : SessionEvent
}

/**
 * One connection to the dash, from the first datagram to the last.
 *
 * **One object per connection.** There is no `connect`, no `reset` and no way back: a
 * session that has ended stays ended, and a reconnect is a new object. That is the whole
 * point of this class's shape, and it replaces the machinery the previous version needed to
 * survive being reused — a `sessionSeq` token checked at every teardown, a `farewellSocket`
 * claim, `sendIfCurrent` identity checks on every periodic send, eight `Job` fields cancelled
 * in three places, and two counters (`staleSends`, `supersededTeardowns`) that existed to
 * report when those guards fired. None of it is needed when a dead session holds nothing the
 * live one can reach: its scope is cancelled and its transport is closed.
 *
 * The sequence is unchanged and remains invariant 5 of network-refactoring.md:
 *   1. Transport opens (RX :2002 bound before anything is sent).
 *   2. Initial burst on :2000, nine packets 20 ms apart — [Scripts.initialBurst].
 *   3. RX loop answers 07 00 / 07 03 with q3c.d and waits for 07 01 01.
 *   4. Nav entry — [Scripts.enterNavMode]; z2 exactly once.
 *   5. [startStreaming] turns on the 4 Hz projection keep-alive; RTP is the caller's.
 *
 * **One writer.** Every control packet leaves through [outbox] and the single [sendLoop]
 * that drains it, so "the order of `send()` calls" and "the order on the wire" are the same
 * sentence. RTP does not: it is a different socket at a hundred times the rate, and putting
 * it in this queue would make a stalled control send stall video.
 */
internal class DashSession private constructor(
    private val ssid: String,
    private val chrome: StateFlow<DashChrome>,
    private val clock: () -> Long,
    parent: Job,
    context: CoroutineContext,
) {
    companion object {
        private const val TAG = "DashSession"
        private const val AUTH_TIMEOUT = 15_000L

        /**
         * How often to ask for auth again while the dash stays completely silent.
         *
         * The burst used to be asked once, and a dash that was not ready to answer it
         * never got a second chance — the 2026-09-19 Huawei log has 15 s of telemetry,
         * a decoder-open ack and the `0B 02` restart blob from a dash that simply never
         * sent its RSA key, followed by an answer in 64 ms the moment a fresh burst went
         * out. The dash's screen still said "connected", because the hostname announce
         * in the same burst DID land: the rider sees a connected dash showing nothing.
         *
         * 2 s: the dash answers in 64-200 ms when it can, so this never stacks a second
         * request on one in flight, and it costs at most seven extra packets across the
         * whole window — in a window where we otherwise send nothing at all.
         */
        private const val AUTH_REASK_INTERVAL_MS = 2_000L

        /**
         * While connected, a healthy dash keeps sending SOMETHING on :2002 — heartbeat
         * replies, 0C/0F telemetry, button events. Silence this long means it is gone even
         * though the socket and the Wi-Fi link still look fine locally (still associated but
         * out of range, or the dash itself powered off or hung). DashWifiManager's
         * NetworkCallback never fires for that case, so this is the only place that notices.
         *
         * NOT about frame-decode acks (09 06/04 55) — see [idrAckCount]: a 2026-08-29 field
         * session found the map updating fine on a physical dash while those were silent for
         * most of a ride, so their silence is not this silence.
         *
         * Measured before it was widened past STREAMING: across the ten auth→stream windows
         * of the 2026-09-13 ride the dash sent 4-13 packets per window with a worst gap of
         * 1010 ms, against this 10 s threshold. Over the whole log the gaps ran median
         * 144 ms / p95 489 ms / p99 813 ms, and the only eight above 10 s were real link
         * losses.
         */
        const val RX_IDLE_TIMEOUT_MS = 10_000L

        /**
         * The one clock this session runs on. Everything periodic is a divisor of it.
         *
         * 250 ms because the projection keep-alive must match the encoder's 4 fps; the 1 Hz
         * senders are every fourth tick and the clock sync every 120th (30 s). Five separate
         * coroutines used to do this, each with its own `delay`, each drifting apart from the
         * others — so what the dash received in one second was five loops' opinion of "now"
         * rather than one snapshot.
         */
        private const val TICK_MS = 250L
        private const val TICKS_PER_SECOND = 4
        private const val TICKS_PER_TIME_SYNC = 120   // 30 s

        private const val HOSTNAME = "OpenDash"

        /** Five retries, then stop: a dash that keeps rejecting will not start accepting. */
        private const val MAX_AUTH_REJECT_RETRIES = 5

        /** How long [close] waits for the farewell packets to actually leave. */
        private const val FAREWELL_TIMEOUT_MS = 1_000L

        /** How often [ackCounterLog] reports the frame-decode-ack delta. */
        private const val ACK_LOG_INTERVAL_MS = 60_000L

        /**
         * How often the RX loop summarises the gaps between incoming packets.
         *
         * The number this exists to produce: whether 802.11 power save is chewing the link.
         * On Android 14+ our Wi-Fi lock is a no-op with the screen off (see
         * DashKeepAliveService.acquireLocks), and until this existed the only way to tell was
         * the rider's impression of the picture. Baseline off the 2026-09-13 debug log
         * (Android 12, screen ON, 7675 samples): median 144 ms, p95 489 ms, max ~1 s.
         */
        private const val RX_GAP_LOG_INTERVAL_MS = 60_000L

        /**
         * Start a session and let it run.
         *
         * @param clock monotonic milliseconds, with NO default — the same rule
         *   [com.opendash.opendash_dash_engine.dash.FrameStreamer] states: a default of
         *   `::monotonicMs` here would make the watchdog's threshold untestable and would let
         *   a caller pass the wall clock by omission, which is the bug this project keeps
         *   re-learning (see `util/Clock.kt`).
         * @param transport called once, on the caller's thread. A throw here (a
         *   `BindException` when the previous session's sockets are somehow still open) is
         *   reported through the returned session like any other failure, so the caller has
         *   one error path rather than two.
         */
        fun open(
            ssid: String,
            chrome: StateFlow<DashChrome>,
            clock: () -> Long,
            parent: Job,
            context: CoroutineContext = Dispatchers.IO,
            transport: () -> DashTransport,
        ): DashSession =
            DashSession(ssid, chrome, clock, parent, context).also { it.start(transport) }
    }

    private val job = SupervisorJob(parent)

    /**
     * Everything this session does runs here, and cancelling [job] stops all of it.
     *
     * [SupervisorJob] and not a plain one: a child that dies — the ack-counter log throwing
     * on a full disk, say — must not take the RX loop with it. The failures that SHOULD end
     * the session go through [fail], which cancels deliberately.
     *
     * A supervisor stops the SIBLINGS from dying, not the process: an unhandled throw still
     * reaches Android's default handler unless a `CoroutineExceptionHandler` is in [context].
     * The plugin installs one for exactly that reason, and the caller is expected to pass it
     * through — see [DashEngineController.openSession].
     */
    private val scope = CoroutineScope(job + context + CoroutineName("dash-session"))

    private val _state = MutableStateFlow(DashState.CONNECTING)
    val state: StateFlow<DashState> = _state.asStateFlow()

    private val eventChannel = Channel<SessionEvent>(Channel.UNLIMITED)
    val events: Flow<SessionEvent> = eventChannel.receiveAsFlow()

    /** The single path onto the control socket — see the class doc's "One writer". */
    private val outbox = Channel<DashCommand>(Channel.UNLIMITED)

    private val auth = DashAuth(ssid)
    private val authConfirmed = CompletableDeferred<Unit>()
    private var authRejectRetries = 0

    /** Set once, before anything can read it, and never replaced. */
    @Volatile private var transport: DashTransport? = null
    private var sendJob: Job? = null

    /** Guards [close] and [fail] against running twice — whichever gets here first wins. */
    private val finished = AtomicBoolean(false)

    /** So two concurrent [close] calls cannot cancel the first one's farewell mid-flight. */
    private val closeMutex = Mutex()

    @Volatile private var lastRxAtMs = clock()

    /**
     * Counts the dash's own "I decoded a frame" notifies (09 06 55 IDR / 09 04 55 P-frame).
     * Added after a 2026-08-28 field session where the nav bubble kept updating correctly for
     * tens of minutes while these went quiet.
     *
     * CORRECTION (2026-08-30): originally documented as "the only signal that the live map
     * video is actually landing on screen" — i.e. zero acks == frozen map. A 2026-08-29 field
     * session directly falsified that (see spec/video.md's "09 06/04 55 — НЕ ack на каждый
     * кадр"). So `09 06/04 55` is most likely a ONE-TIME "decoder opened" milestone, not a
     * per-frame heartbeat the way better-dash's naming implied — this still tracks something
     * real (whether the dash ever confirms it is decoding), just not "is the map frozen now".
     */
    private val idrAckCount = AtomicInteger(0)
    private val pFrameAckCount = AtomicInteger(0)
    private var loggedFirstIdrAck = false

    /**
     * Datagrams that did not parse cleanly, reported with the ack counters.
     *
     * Both numbers were zero across 7692 packets of the 2026-09-13 log — the parser's
     * leniency has never actually been needed — but leniency nobody counts is silence.
     */
    private val malformed = MalformedCounter()

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Begin the 4 Hz projection keep-alive and the 1 Hz cards.
     *
     * Only meaningful once [SessionEvent.Ready] has been delivered; from any other state it
     * is ignored, because the dash opens its decoder as part of nav-mode entry and a
     * projection frame before that is a packet it has no state for.
     */
    fun startStreaming() {
        if (_state.value != DashState.READY) return
        setState(DashState.STREAMING)
    }

    /**
     * How to write RTP for this session, or null if the transport never opened.
     *
     * No identity check and no counter: the returned function captures a transport that this
     * object owns for its whole life and closes on the way out, so a packet written through
     * it either reaches this session's socket or fails inside it. The previous version needed
     * both because the socket was a mutable field a reconnect could replace underneath the
     * frame loop.
     */
    fun rtpSender(): ((ByteArray) -> Unit)? = transport?.let { t -> { pkt -> t.sendRtp(pkt) } }

    /** Queue one command. Ignored once the session has ended. */
    fun send(cmd: DashCommand) {
        outbox.trySend(cmd)
    }

    /**
     * End the session.
     *
     * @param farewell send `projectionStop` + `projectionOff` first and wait for them to
     *   leave. Without them the dash stays in projection, showing the last frame it got until
     *   its own timeout — which is what a rider reads as "the map froze". Skip it only when
     *   the link is already gone, where the two packets can only be written into a dead
     *   socket.
     *
     * Idempotent, and safe to call from any thread. Suspends until everything this session
     * started has stopped and the transport is closed — which is what makes "no two live
     * sessions" checkable instead of hoped for.
     */
    suspend fun close(farewell: Boolean) = closeMutex.withLock {
        if (finished.compareAndSet(false, true) && farewell) {
            outbox.trySend(DashCommand.ProjectionStop)
            outbox.trySend(DashCommand.ProjectionOff)
            outbox.close()
            // Bounded: the farewell is two datagrams to a link-local broadcast, so a second
            // is four orders of magnitude of headroom. If the radio really is wedged, a
            // disconnect must still return — the rider is waiting on it.
            withTimeoutOrNull(FAREWELL_TIMEOUT_MS) { sendJob?.join() }
        }
        outbox.close()
        job.cancelAndJoin()
        setState(DashState.IDLE)
        DebugLog.i(TAG) { "Session closed" }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    private fun start(transportFactory: () -> DashTransport) {
        val t = try {
            transportFactory()
        } catch (e: Exception) {
            // Including BindException, which now means something: without SO_REUSEADDR a
            // port still held by a previous session is an error rather than a silent
            // second listener stealing half the dash's traffic.
            fail("${e.javaClass.simpleName}: ${e.message}")
            return
        }
        transport = t
        // **Closed on cancellation, not on completion — and that ordering is the whole
        // point.** [receiveLoop] parks in a blocking `DatagramSocket.receive()` with no
        // timeout, and cancellation cannot interrupt it: only closing the socket can. A
        // handler on `job.invokeOnCompletion` runs AFTER every child has finished, so it
        // would wait for the loop it is supposed to release — `close()` would never return,
        // :2000 and :2002 would stay bound (and with SO_REUSEADDR gone, no reconnect could
        // ever bind them again), and `dispose()`'s runBlocking would hang the main thread.
        //
        // This child instead parks until something cancels the scope and closes the
        // transport on its way out, which unblocks the receive and lets the join finish.
        // `DashSessionTest` has a transport that reproduces the socket's uninterruptible
        // parking, because a Channel-backed fake cannot show this at all.
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { t.close() }
            }
        }
        // Belt and braces for the one path the child above cannot cover: a scope already
        // cancelled before it ever ran. Closing twice is a no-op.
        job.invokeOnCompletion { runCatching { t.close() } }
        sendJob = scope.launch { sendLoop(t) }
        scope.launch { run(t) }
    }

    private suspend fun run(t: DashTransport) {
        try {
            scope.launch { receiveLoop(t) }
            scope.launch { tickLoop() }
            scope.launch { watchdog() }
            scope.launch { ackCounterLog() }

            setState(DashState.AUTHENTICATING)
            DebugLog.i(TAG) { "Sending initial burst…" }
            RideDiagnostics.log("auth", "initial burst sent — waiting up to ${AUTH_TIMEOUT}ms for 07 01 01")
            sendScript(Scripts.initialBurst(HOSTNAME, timeSyncNow()))

            DebugLog.i(TAG) { "Waiting up to ${AUTH_TIMEOUT}ms for auth (07 01 01)…" }
            withTimeout(AUTH_TIMEOUT) {
                // Ask again while the dash says nothing — see AUTH_REASK_INTERVAL_MS. It
                // stops for good the instant the dash offers any part of its key: from
                // there it is mid-handshake, and another q3c.e would restart its side while
                // DashAuth.keySent keeps ours from answering the second offer. "For good"
                // is why this reads [DashAuth.dashHasSpoken] and not the live key fields —
                // a rejection clears those, and prodding a dash that is busy rejecting us
                // would walk straight past MAX_AUTH_REJECT_RETRIES.
                val nagger = launch {
                    var asks = 0
                    while (!auth.dashHasSpoken) {
                        delay(AUTH_REASK_INTERVAL_MS)
                        if (auth.dashHasSpoken) break
                        outbox.trySend(DashCommand.AuthRequest)
                        asks++
                        if (asks == 1) {
                            RideDiagnostics.log(
                                "auth",
                                "no answer to the burst — re-asking every ${AUTH_REASK_INTERVAL_MS}ms",
                            )
                        }
                    }
                }
                try {
                    authConfirmed.await()
                } finally {
                    nagger.cancel()
                }
            }
            DebugLog.i(TAG) { "Authenticated ✓" }
            RideDiagnostics.log("auth", "authenticated (07 01 01) — entering nav mode")

            // Always nav-mode entry, idle or not: the dash opens its video decoder only as
            // part of nav mode, so there is no separate idle mode (see spec/fsm.md).
            sendScript(Scripts.enterNavMode(chrome.value.destinationName))
            DebugLog.i(TAG) { "Nav mode kick sent" }

            setState(DashState.READY)
            eventChannel.trySend(SessionEvent.Ready)
        } catch (e: TimeoutCancellationException) {
            // Before the CancellationException clause below, and that order is load-bearing:
            // withTimeout signals by throwing a CancellationException subclass, so the
            // general clause would treat a real auth timeout as a deliberate teardown and
            // report nothing at all.
            // The one failure that implicates the SSID: datagrams flowed for the whole
            // window and the far end still never said 07 01 01.
            fail(
                "Auth timed out — no 07 01 01 from dash. Check SSID matches '$ssid'.",
                handshakeRefused = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, { "Session error" }, e)
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Ends the session with an error, once.
     *
     * Cancelling [job] from inside one of its own children is intentional: everything else
     * this session started stops at its next suspension point, the transport closes in the
     * completion handler, and the caller learns why from the event rather than from a
     * callback that could fire twice.
     */
    private fun fail(reason: String, handshakeRefused: Boolean = false) {
        if (!finished.compareAndSet(false, true)) return
        DebugLog.e(TAG, { "ERROR — $reason" })
        RideDiagnostics.log("error", "session fail: $reason")
        setState(DashState.ERROR)
        eventChannel.trySend(SessionEvent.Failed(reason, handshakeRefused))
        outbox.close()
        job.cancel()
    }

    /** The dash stopped talking, or the socket died under us. Recoverable; ERROR is not. */
    private fun linkLost(event: SessionEvent) {
        if (!finished.compareAndSet(false, true)) return
        setState(DashState.IDLE)
        eventChannel.trySend(event)
        outbox.close()
        job.cancel()
    }

    private fun setState(next: DashState) {
        val prev = _state.value
        if (prev == next) return
        DebugLog.i(TAG) { "state $prev -> $next" }
        _state.value = next
    }

    // ── The one writer ────────────────────────────────────────────────────

    private suspend fun sendLoop(t: DashTransport) {
        // Drains what is already queued after close(), which is what makes the farewell
        // reliable: close() puts two commands in and closes the channel, and this loop is
        // the thing that guarantees they reach the wire before the job is cancelled.
        for (cmd in outbox) {
            // Encoding can refuse: `AuthSendKey` requires a 128-byte block, so a dash
            // offering a key that is not RSA-1024 throws here. Before stage 4 that throw
            // happened inside the RX loop, which caught it and failed the session; letting it
            // out of the ONE writer instead would drop every later control packet — the
            // farewell included — with no error state anywhere.
            val bytes = try {
                K1GCodec.encode(cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e(TAG, { "Refusing to send $cmd" }, e)
                fail("${e.javaClass.simpleName}: ${e.message}")
                return
            }
            t.send(bytes)
        }
    }

    /**
     * Play a script, honouring its pauses.
     *
     * The pauses are between DATAGRAMS, not between enqueues — [sendLoop] drains an unbounded
     * channel a step ahead of this loop, so the two are the same thing in practice, and the
     * alternative (queue all nine at once) would put the whole burst on the wire in one go
     * and break invariant 4.
     */
    private suspend fun sendScript(script: List<Step>) {
        for (step in script) {
            outbox.send(step.cmd)
            if (step.pauseAfterMs > 0) delay(step.pauseAfterMs)
        }
    }

    // ── The one clock ─────────────────────────────────────────────────────

    /**
     * Everything periodic, in one loop with a fixed order inside each tick.
     *
     * The order is the part worth pinning (and [DashSessionTest] pins it): the route card
     * carries the destination and the projection flag, the nav packet carries the figures
     * drawn on top of it, and the dash renders whatever arrived last. Five independent
     * coroutines could deliver them in any order at all.
     *
     * The heartbeat and the clock sync run from the moment the session opens — as they did
     * before — while everything else waits for STREAMING, because the dash has no projection
     * to keep alive until then.
     */
    private suspend fun tickLoop() {
        var n = 0L
        while (currentCoroutineContext().isActive) {
            val streaming = _state.value == DashState.STREAMING
            val c = chrome.value
            if (streaming) outbox.trySend(DashCommand.ProjectionFrame)
            if (n % TICKS_PER_SECOND == 0L) {
                if (streaming) {
                    outbox.trySend(routeCard(c, projectionOn = true))
                    c.nav?.let { outbox.trySend(activeNav(it)) }
                    val caller = c.caller
                    when {
                        caller != null -> outbox.trySend(DashCommand.CallNotify(caller))
                        // Only when one WAS showing: the dash needs the card cleared once,
                        // not a clear every second for the whole ride.
                        sentCaller != null -> outbox.trySend(DashCommand.CallClear)
                    }
                    sentCaller = caller
                    c.nowPlaying?.let {
                        outbox.trySend(DashCommand.NowPlaying(it.title, it.album, it.artist))
                    }
                }
                outbox.trySend(DashCommand.Heartbeat())
            }
            // Keep the dash clock correct — it has no source of its own and shows whatever
            // the phone last fed it.
            if (n % TICKS_PER_TIME_SYNC == 0L) outbox.trySend(timeSyncNow())
            n++
            delay(TICK_MS)
        }
    }

    /** The call card is only cleared once, when a call that WAS showing goes away. */
    private var sentCaller: String? = null

    private fun routeCard(c: DashChrome, projectionOn: Boolean): DashCommand {
        val nav = c.nav ?: return DashCommand.RouteCard(c.destinationName, projectionOn)
        // The captured template's own figures (7.9 km / glyph 0x3C / ETA 03:03) must never
        // reach the dash once real guidance is running: this card repeats at 1 Hz and would
        // stomp the live numbers every second.
        return DashCommand.RouteCard(
            title = c.destinationName,
            projectionOn = projectionOn,
            maneuver = nav.maneuver,
            primaryUnit = nav.primaryUnit,
            totalDist = nav.totalDist,
            totalUnit = nav.totalUnit,
            etaHHMM = nav.etaHHMM,
        )
    }

    private fun activeNav(nav: NavFigures) = DashCommand.ActiveNav(
        maneuver = nav.maneuver,
        primaryDist = nav.primaryDist,
        primaryUnit = nav.primaryUnit,
        totalDist = nav.totalDist,
        totalUnit = nav.totalUnit,
    )

    // ── Watchdog ──────────────────────────────────────────────────────────

    /**
     * Tears the session down when the dash goes quiet — but only after auth, because before
     * it there is nothing to be quiet about: the dash answers the burst when it feels like
     * it, and on 2026-09-18 one answered after 19 s.
     *
     * [clock] is monotonic, never the wall clock: the latter steps on an NTP correction, and
     * a correction right after data returns from a dead zone is routine. A forward step over
     * 10 s would tear down a healthy session; a backward one would hide a real silence.
     */
    private suspend fun watchdog() {
        authConfirmed.await()
        while (currentCoroutineContext().isActive) {
            delay(TICK_MS * TICKS_PER_SECOND)
            val silentMs = clock() - lastRxAtMs
            if (silentMs > RX_IDLE_TIMEOUT_MS) {
                DebugLog.w(TAG) { "No data from dash for ${silentMs}ms" }
                // The state is part of the finding: silence in READY means the dash never
                // got as far as showing anything, silence in STREAMING means it stopped
                // mid-ride. Additive to the existing text so the line stays greppable
                // (инвариант 11).
                RideDiagnostics.log(
                    "error",
                    "RX watchdog: dash silent ${silentMs / 1000}s → link lost (state=${_state.value})",
                )
                linkLost(SessionEvent.DashSilent)
                return
            }
        }
    }

    // ── RX ────────────────────────────────────────────────────────────────

    private suspend fun receiveLoop(t: DashTransport) {
        // Sampled AND drained here, in the one coroutine that owns them: Percentiles is not
        // thread-safe. 1024, not the default 256 — at the measured ~7 packets a second a 60 s
        // window holds about 420 samples, and an early long gap (the exact thing this metric
        // exists to catch) would be the part a smaller ring dropped.
        val rxGaps = Percentiles(capacity = 1024)
        var rxGapCount = 0
        var lastGapReportAtMs = clock()

        while (currentCoroutineContext().isActive) {
            val pkt = try {
                t.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A deliberate teardown closes the transport out from under this blocking
                // read, so an exception here is the NORMAL way the loop ends. ensureActive
                // tells the two apart: if the scope is already going down, this rethrows as
                // cancellation and nothing is reported.
                currentCoroutineContext().ensureActive()
                DebugLog.w(TAG) { "RX loop stopped — socket error: ${e.message}" }
                RideDiagnostics.log("error", "RX loop stopped — socket error: ${e.message}")
                linkLost(SessionEvent.Failed("Lost connection to dash"))
                return
            }
            val nowMs = clock()
            if (nowMs - lastGapReportAtMs >= RX_GAP_LOG_INTERVAL_MS && rxGapCount > 0) {
                lastGapReportAtMs = nowMs
                RideDiagnostics.log(
                    TAG,
                    "rx gap p50/p95/max=${rxGaps.drain()}ms n=$rxGapCount " +
                        "in the last ${RX_GAP_LOG_INTERVAL_MS / 1_000}s",
                )
                rxGapCount = 0
            }
            rxGaps.add(nowMs - lastRxAtMs)
            rxGapCount++
            lastRxAtMs = nowMs
            // Nothing downstream may escape: a malformed or hostile datagram must fail this
            // session, not the engine.
            try {
                dispatch(pkt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e(TAG, { "Error handling incoming packet" }, e)
                fail("${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }
    }

    private fun dispatch(pkt: ByteArray) {
        val msgs = K1GCodec.decode(pkt, malformed)
        // Dump the full raw packet for anything that ISN'T just the decoder-opened notifies —
        // those can fire several times a second and would drown the log. This captures
        // joystick events, telemetry, and any unknown TLV in full hex, so a single
        // `adb logcat -s DashSession` is enough to reverse the protocol.
        val onlyAcks = msgs.isNotEmpty() && msgs.all { it is DashMessage.DecoderOpened }
        if (!onlyAcks) DebugLog.i(TAG) { "RX RAW (${pkt.size}B): ${pkt.toHexFull()}" }
        for (msg in msgs) when (msg) {
            is DashMessage.AuthModulus, is DashMessage.AuthExponent, is DashMessage.AuthResult ->
                when (val ev = auth.ingest(msg)) {
                    is AuthEvent.SendKey -> {
                        DebugLog.i(TAG) { "Got RSA pubkey — sending q3c.d" }
                        outbox.trySend(DashCommand.AuthSendKey(ev.cipher))
                    }
                    AuthEvent.Confirmed -> authConfirmed.complete(Unit)
                    AuthEvent.Rejected -> {
                        authRejectRetries++
                        DebugLog.w(TAG) { "Auth rejected — retry #$authRejectRetries" }
                        RideDiagnostics.log("auth", "REJECTED — retry #$authRejectRetries")
                        auth.reset()
                        if (authRejectRetries <= MAX_AUTH_REJECT_RETRIES) {
                            outbox.trySend(DashCommand.AuthRequest)
                        }
                    }
                    AuthEvent.None -> {}
                }

            // The dash opened its decoder and waits for the matching reply. NOT a per-frame
            // ack, whatever better-dash called it — see DashMessage.DecoderOpened.
            is DashMessage.DecoderOpened -> {
                outbox.trySend(DashCommand.DecoderOpenedAck(keyFrame = msg.keyFrame))
                if (msg.keyFrame) {
                    idrAckCount.incrementAndGet()
                    if (!loggedFirstIdrAck) {
                        loggedFirstIdrAck = true
                        RideDiagnostics.log("dash", "dash DECODED first IDR (09 06 55) — video accepted ✓")
                    }
                } else {
                    pFrameAckCount.incrementAndGet()
                }
            }

            is DashMessage.Button -> {
                // Ack first, report second (invariant 7): the dash is waiting on the echo,
                // and the ride-file line below appends to external storage.
                outbox.trySend(DashCommand.ButtonAck(msg.code))
                RideDiagnostics.log(
                    "joystick",
                    "09 00 code=0x${msg.code.toString(16).uppercase()} full=${msg.raw.toHexFull()}",
                )
                eventChannel.trySend(SessionEvent.Button(msg.code))
            }

            // ── 0F: vehicle identity, AES-256-CBC under the session key (IV = first 16
            //    bytes). better-dash only logs the ciphertext — we decrypt and log the
            //    plaintext for field-mapping. Working hypothesis for `sub`, from independent
            //    RE against the same Royal Enfield Tripper/K-Dash hardware (behavioural
            //    inference on the OFFICIAL app, no hex dump, no mention of the RSA/AES
            //    handshake we already have — so it confirms the field layout, not our
            //    crypto): 0F01 chassis number, 0F02 serial, 0F05 BSSID (6 B), 0F06
            //    manufacturing date, 0F07 hardware version, 0F08 part number, 0F0A FOTA
            //    version; 0F03/0F04/0F09 not covered there. Source:
            //    https://www.mihaiblaga.dev/reverse-engineering-royal-enfields-connected-bike-stack
            //    — a lead to verify against our own plaintext, not ground truth. ──
            is DashMessage.Identity -> {
                val key = auth.sessionKey
                val plain = key?.let { aesDecryptCbc(msg.cipher, it) }
                DebugLog.i(TAG) {
                    "DASH TELEMETRY 0F sub=0x%02X enc(%dB)=%s  dec=%s".format(
                        msg.sub, msg.cipher.size, msg.cipher.toHexFull(),
                        plain?.toHexFull() ?: "<key=${key != null}; decrypt failed>",
                    )
                }
            }

            // ── 0C xx: dash → app telemetry (trip/odo/fuel/temp). Unmapped even by the
            //    independent RE above, which lists 0x0B/0x0C as "present but not fully
            //    mapped" too — this needs our own sweep. ──
            is DashMessage.Telemetry -> DebugLog.i(TAG) {
                "DASH TELEMETRY 0C sub=0x%02X (%dB) val=%s"
                    .format(msg.sub, msg.value.size, msg.value.toHexFull())
            }

            // Everything else in FULL so its TLV can be identified — the dash's 'exit
            // navigation' selection, the 0x0B blob it sends when it restarts mid-ride
            // (network-refactoring.md §0.1), and the 26 subtypes nobody has swept.
            is DashMessage.Unknown -> DebugLog.i(TAG) {
                "DASH EVENT type=0x%02X sub=0x%02X (%dB) val=%s".format(
                    msg.tlv.type, msg.tlv.sub, msg.tlv.value.size, msg.tlv.value.toHexFull(),
                )
            }
        }
    }

    /**
     * Every [ACK_LOG_INTERVAL_MS], how many frame-decode acks went out — raw data only, at
     * INFO regardless of the count.
     *
     * Used to be logged at WARNING with "dash has stopped decoding video (map likely frozen)"
     * when zero; the 2026-08-29 field session falsified that. Zero here is apparently the
     * NORMAL steady state — see [idrAckCount] and spec/video.md. Kept as plain info in case
     * the pattern (none EVER, say) turns out to matter for something else.
     */
    private suspend fun ackCounterLog() {
        var lastIdr = 0
        var lastP = 0
        while (currentCoroutineContext().isActive) {
            delay(ACK_LOG_INTERVAL_MS)
            if (_state.value != DashState.STREAMING) continue
            val idr = idrAckCount.get()
            val p = pFrameAckCount.get()
            val intervalS = ACK_LOG_INTERVAL_MS / 1_000
            DebugLog.i(TAG) {
                "Frame decode acks: IDR=${idr - lastIdr} P=${p - lastP} in the last ${intervalS}s"
            }
            lastIdr = idr
            lastP = p
            // Silent tolerance of a malformed datagram is indistinguishable from never having
            // seen one, so the parser's leniency reports itself — but only when it fired.
            val bad = malformed.drain()
            if (!bad.isEmpty) {
                RideDiagnostics.warn(
                    TAG,
                    "malformed RX in the last ${intervalS}s: ${bad.lengthMismatch} " +
                        "datagram(s) whose declared length disagreed with their size, " +
                        "${bad.truncatedTlv} TLV(s) cut off by the end of the datagram",
                )
            }
        }
    }

    /** Full hex dump (no truncation) — used for protocol-capture logging. */
    private fun ByteArray.toHexFull(): String = joinToString(" ") { "%02X".format(it) }

    /** AES-256-CBC/PKCS5 decrypt of an [iv(16) ‖ ciphertext] blob under the session key. */
    private fun aesDecryptCbc(ivAndCt: ByteArray, key: ByteArray): ByteArray? = runCatching {
        if (ivAndCt.size <= 16) return null
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(ivAndCt.copyOfRange(0, 16)),
        )
        cipher.doFinal(ivAndCt.copyOfRange(16, ivAndCt.size))
    }.getOrNull()
}
