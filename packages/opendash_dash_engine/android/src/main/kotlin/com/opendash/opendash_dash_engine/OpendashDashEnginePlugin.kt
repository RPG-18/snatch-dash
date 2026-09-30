package com.opendash.opendash_dash_engine

import com.opendash.opendash_dash_engine.dash.map.GeoPoint
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.media.MediaInfoProvider
import com.opendash.opendash_dash_engine.util.BatteryOptimisation
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bridges Dart to [DashEngineController] — the native dash protocol/video pipeline.
 *
 * **Generated boundary, since 2026-09-30 (task 7.6).** Commands arrive through the
 * Pigeon-generated [DashEngineApi] instead of a `when (call.method)` over strings, and
 * the three streams out — state, joystick buttons, and every [DebugLog] line for the
 * Dart-side Talker log — are Pigeon event channels. The schema is
 * `pigeons/dash_engine.dart`; nothing here or in `Messages.g.kt` is edited by hand.
 *
 * What that bought: an argument read with the wrong name used to return null and a
 * method renamed on one side used to reach `notImplemented()`, both at runtime, on a
 * dash mounted on a moving motorcycle. Now neither compiles.
 */
class OpendashDashEnginePlugin :
    FlutterPlugin, DashEngineApi, ActivityAware {
    private companion object {
        private const val TAG = "OpendashDashEnginePlugin"

        /** How long a session boundary waits for [rideErrorWriter] before giving up. */
        private const val DRAIN_TIMEOUT_MS = 250L
    }

    /**
     * The sinks of the three generated event channels.
     *
     * Pigeon generates a handler per stream and a `register` for it, but no
     * *un*register, so [onDetachedFromEngine] drops the sinks rather than tearing the
     * channels down. A handler with no sink delivers nothing, and the messenger it is
     * registered on is leaving with the engine.
     *
     * **That assumes one plugin instance per engine**, which is how Flutter registers
     * this one. Were an instance ever reused across two engines, the second attach
     * would register handlers sharing these same holders, and a late `onCancel` from
     * the first engine would drop the second engine's sink. Nothing needs guarding
     * today; this is here so that the day it changes, the reason is written down.
     */
    private val stateEvents = DashEventSink<DashEngineState>()
    private val buttonEvents = DashEventSink<DashButtonEvent>()
    private val logEvents = DashEventSink<DashLogEntry>()

    /**
     * Our own [DebugLog.sink] lambda, kept so detach can clear the global only
     * when it's still ours — with a second FlutterEngine attached, the first one
     * to detach would otherwise silence native logging for the engine that's
     * still running.
     */
    private var debugLogSink: ((String, String, String) -> Unit)? = null
    private val stateStreamHandler = object : StateStreamHandler() {
        override fun onListen(p0: Any?, sink: PigeonEventSink<DashEngineState>) =
            stateEvents.attach(sink)

        override fun onCancel(p0: Any?) = stateEvents.detach()
    }

    private val buttonStreamHandler = object : ButtonStreamHandler() {
        override fun onListen(p0: Any?, sink: PigeonEventSink<DashButtonEvent>) =
            buttonEvents.attach(sink)

        override fun onCancel(p0: Any?) = buttonEvents.detach()
    }

    private val logStreamHandler = object : LogStreamHandler() {
        override fun onListen(p0: Any?, sink: PigeonEventSink<DashLogEntry>) {
            logEvents.attach(sink)
            // DebugLog is wired HERE, not at attach, and that is the whole point of the
            // buffer on the other side: DebugLog flushes its pre-attach lines the moment a
            // sink appears, and a sink installed at attach forwards into a [logSink] that is
            // still null — Dart subscribes later, from attachNativeLogBridge() once the
            // entrypoint runs. Flushing into that would drop precisely the cold-start lines
            // the buffer exists for: ExitInfoCollector's "last exit was an ANR", the
            // StrictMode install, CrashGuard's confirmation.
            debugLogSink?.let { DebugLog.sink = it }
        }

        override fun onCancel(p0: Any?) {
            logEvents.detach()
            // Back to buffering rather than to a sink that drops: Dart can resubscribe.
            if (DebugLog.sink === debugLogSink) DebugLog.sink = null
        }
    }

    // SupervisorJob + a handler, deliberately: with a plain Job a single uncaught throw in any
    // dash coroutine cancels this scope for good — and a cancelled Job cannot be reused, so
    // every later launch() silently no-ops and the engine is dead until the process restarts.
    // The handler also keeps such a throw off Android's default handler, which would crash the
    // app mid-ride.
    private val job = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        DebugLog.e("DashEnginePlugin", { "Uncaught exception in dash coroutine" }, e)
    }
    private val scope = CoroutineScope(Dispatchers.Main + job + exceptionHandler)
    private var controller: DashEngineController? = null

    /**
     * The host Activity, while there is one.
     *
     * The plugin needs it for exactly one thing: showing the battery-optimisation
     * prompt. `Context.startActivity` from the application context would need
     * `FLAG_ACTIVITY_NEW_TASK` and lands the system dialog in its own task, where
     * several OEM skins put it behind the app instead of over it. Held as a plain
     * reference and cleared on detach — an Activity leaked from a plugin outlives the
     * screen rotation that created it.
     */
    private var activity: android.app.Activity? = null

    /** Application context, kept for the calls that need one without an Activity. */
    private var appContext: android.content.Context? = null

    /** Kept only so detach can hand [DashEngineApi.setUp] a null api on the same one. */
    private var messenger: BinaryMessenger? = null

    /**
     * Flutter's own errors, into the ride file — off the platform thread.
     *
     * Dart has no other way in: `RideDiagnostics` is Kotlin, and until 2026-09-29
     * nothing carried a Dart-side failure to it. That gap cost a day on 28.09 — the
     * error that actually broke the settings screen reached neither logcat nor
     * `app_log.txt` and was found only by attaching `flutter run`.
     *
     * **Why a thread of its own.** `onMethodCall` runs on the Android main thread,
     * which is also where MapLibre's snapshot callbacks run, and the write it would
     * perform there opens, appends to and closes a file on FUSE-backed external
     * storage while holding the lock the `[map]`/`[stream]`/`[mem]` writers share.
     * A mid-ride Flutter error would show up as `wakeLate`/`overrun` with nothing in
     * the file to explain it — the frame loop paying for the log that was supposed to
     * diagnose it. The rest of the engine already keeps this work off Main; this is
     * the one path that would not have.
     *
     * **One thread, not a pool**, and not `Dispatchers.IO`: the ride file is read as a
     * sequence, so the lines have to land in the order they were reported, and a
     * single worker is what guarantees that without a lock of its own.
     */
    private val rideErrorWriter: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "ride-flutter-log") }

    /**
     * Let the writer catch up before a ride file is closed.
     *
     * Bounded twice over: the queue holds at most what the Dart-side budget let
     * through (six lines per ten seconds), and the wait gives up after
     * [DRAIN_TIMEOUT_MS] rather than hold a session boundary hostage to a stuck
     * filesystem. Runs at connect and disconnect only, not per line.
     */
    private val drainRideErrors: () -> Unit = {
        val landed = CountDownLatch(1)
        runCatching { rideErrorWriter.execute { landed.countDown() } }
            .onFailure { landed.countDown() }
        runCatching { landed.await(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        messenger = binding.binaryMessenger
        DashEngineApi.setUp(binding.binaryMessenger, this)
        StateStreamHandler.register(binding.binaryMessenger, stateStreamHandler)
        ButtonStreamHandler.register(binding.binaryMessenger, buttonStreamHandler)
        LogStreamHandler.register(binding.binaryMessenger, logStreamHandler)

        val sink: (String, String, String) -> Unit = { tag, level, message ->
            scope.launch {
                logEvents.emit(DashLogEntry(tag, level.toLogLevel(), message))
            }
        }
        // Kept, not installed: [logStreamHandler] installs it when Dart subscribes.
        debugLogSink = sink
        // Registered with the engine, not at construction: it is a promise to drain
        // [rideErrorWriter], and that promise is only good while this plugin is
        // attached to hold it.
        RideDiagnostics.appendBarrier = drainRideErrors

        // Process-lifecycle marker: the only direct signal for "was this a genuinely fresh
        // process, or just the plugin/engine reattaching within one that's been alive a
        // while" — see spec/wifi_retry_policy.md's 2026-08-28 log analysis, where this had to
        // be inferred indirectly (LocationTracker.start() being a no-op if already running).
        // processUptimeMs small (a few seconds) ⇒ cold start/process respawn; large ⇒ just an
        // engine/activity recreation in a process that was already running.
        val pid = android.os.Process.myPid()
        val processUptimeMs = android.os.SystemClock.elapsedRealtime() -
            android.os.Process.getStartElapsedRealtime()
        DebugLog.i(TAG) { "Plugin attached — pid=$pid processUptime=${processUptimeMs}ms" }

        appContext = binding.applicationContext
        controller = DashEngineController(
            context = binding.applicationContext,
            scope = scope,
            onState = { state -> scope.launch { stateEvents.emit(state) } },
        ).also { c ->
            // Its own stream now. Sharing the state channel meant every reader of the
            // state had to know about buttons in order to skip them, and one that forgot
            // read a button event as a state update with every field null.
            c.onButton = { code ->
                scope.launch { buttonEvents.emit(DashButtonEvent(code.toLong())) }
            }
        }
    }

    /**
     * The engine, or the failure Dart used to receive as `NO_ENGINE`.
     *
     * A thrown [DashPigeonError] becomes a `PlatformException` with this code on the
     * Dart side, which is what the string-keyed handler returned by hand from its
     * `?: return result.error(...)`. The difference is that forgetting it is now a
     * compile error rather than a method that answers `null`.
     */
    private fun engine(): DashEngineController =
        controller ?: throw DashPigeonError("NO_ENGINE", "Engine not attached")

    override fun getPlatformVersion(): String = "Android ${android.os.Build.VERSION.RELEASE}"

    override fun connect() = engine().connect()

    /**
     * Suspends, and Dart waits for the whole thing: the farewell packets have to
     * actually leave before the socket closes (see [DashEngineController.disconnect]).
     *
     * **Every path answers.** A call that is never replied to leaves its Dart future
     * pending for the life of the process — the rider taps "Отключить" and the button
     * spins forever. The generated wrapper covers that: it catches `Throwable`,
     * cancellation included, and replies either way. The hand-written version had to
     * spell it out and grew a bug doing so.
     *
     * **Run on [scope], not on the caller's.** Pigeon dispatches a suspend method on a
     * `CoroutineScope(Dispatchers.Main)` of its own making, which nothing here owns —
     * so `job.cancel()` at detach would not reach an in-flight disconnect, and it could
     * resume, after a suspension in `Dispatchers.IO`, into a controller that
     * [onDetachedFromEngine] has already disposed. `async` + `await` puts the work back
     * under this plugin's job while still handing the result (or the cancellation) to
     * the wrapper that has to answer.
     */
    override suspend fun disconnect() {
        // Checked explicitly, for the error code. Past detach `scope.async` returns an
        // already-cancelled Deferred and `await` would throw a bare
        // `CancellationException` — an answer, but not one that says what happened.
        if (!scope.isActive) {
            throw DashPigeonError("ENGINE_GONE", "Plugin detached before disconnect could run")
        }
        val c = engine()
        scope.async { c.disconnect() }.await()
    }

    override fun setDestination(name: String?, lat: Double?, lng: Double?) =
        engine().setDestination(name, lat, lng)

    override fun clearDestination() = engine().clearDestination()

    override fun setNavState(
        remainingMeters: Double?,
        nextTurnMeters: Double?,
        maneuver: Long,
        etaHHMM: String?,
        offRoute: Boolean,
        points: List<NavPoint>,
        jamSegments: List<Long>,
    ) = engine().setNavState(
        remainingMeters = remainingMeters,
        nextTurnMeters = nextTurnMeters,
        // Pigeon carries Dart's `int` as `Long`; the protocol writes a byte.
        maneuver = maneuver.toInt(),
        etaHHMM = etaHHMM,
        isOffRoute = offRoute,
        points = points.map { GeoPoint(it.lat, it.lng) },
        jamSegments = jamSegments.map { it.toInt() },
    )

    override fun setFollowMode(enabled: Boolean) = engine().setFollowMode(enabled)

    override fun panBy(dx: Double, dy: Double) = engine().panBy(dx.toFloat(), dy.toFloat())

    override fun zoomIn() = engine().zoomIn()

    override fun zoomOut() = engine().zoomOut()

    override fun toggleHeadingUp() = engine().toggleHeadingUp()

    override fun recenter() = engine().recenter()

    override fun forgetDash() = engine().forgetDash()

    override fun setSsid(ssid: String) = engine().setSsid(ssid)

    override fun setWifiPassword(password: String) = engine().setWifiPassword(password)

    override fun getConfig(): DashConfigView = engine().currentConfig()

    /**
     * Answered without the engine, deliberately.
     *
     * Behind the [engine] guard this failed with `NO_ENGINE` whenever the controller was
     * between lives, and the settings card — whose whole job is to explain why a ride
     * died — vanished exactly when it was wanted.
     *
     * One [BatteryOptimisation.isIgnoring] and at most one `resolveActivity` per call:
     * both are binder round trips, this runs on the platform thread that the frame
     * pipeline's own calls share, and the status is re-read on every resume while
     * Settings is open.
     */
    override fun batteryOptimisationStatus(): BatteryOptimisationStatus {
        val ctx = appContext ?: throw DashPigeonError("no_context", "Plugin is detached")
        val ignoring = BatteryOptimisation.isIgnoring(ctx)
        return BatteryOptimisationStatus(
            ignoring = ignoring,
            // Separate from `ignoring`: a device with no handler for the action is
            // neither exempt NOR askable, and a button that opens nothing is worse than
            // no button.
            canAsk = !ignoring && BatteryOptimisation.requestIntent(ctx) != null,
            emuiWorkaroundNeeded = BatteryOptimisation.emuiWorkaroundNeeded(),
        )
    }

    override fun requestIgnoreBatteryOptimisations(): Boolean {
        val ctx = appContext ?: throw DashPigeonError("no_context", "Plugin is detached")
        // Not an error: the caller asked for something already true. Saying so lets the
        // UI refresh instead of showing a failure.
        if (BatteryOptimisation.isIgnoring(ctx)) return false
        val intent = BatteryOptimisation.requestIntent(ctx)
            ?: throw DashPigeonError("no_handler", "Nothing handles the battery prompt")
        val a = activity
            ?: throw DashPigeonError("no_activity", "No activity to show the prompt over")
        return runCatching { a.startActivity(intent) }
            .map { true }
            .getOrElse { throw DashPigeonError("launch_failed", it.message) }
    }

    /**
     * Also answered without the engine: an error worth recording is likelier, not less
     * likely, while the engine is between lives, and `NO_ENGINE` would drop exactly those.
     *
     * Routed here, appended on [rideErrorWriter]. Deferring the whole call would race
     * with the `start`/`stop` that run on this same thread — see
     * [RideDiagnostics.fromFlutterDeferred].
     */
    override fun rideError(message: String) {
        RideDiagnostics.fromFlutterDeferred(message)?.let { append ->
            runCatching { rideErrorWriter.execute(append) }
        }
    }

    override fun updateNowPlaying(title: String?, album: String, artist: String) =
        engine().updateNowPlaying(title, album, artist)

    override fun updateCall(caller: String?) = engine().updateCall(caller)

    override fun playChime() = engine().playChime()

    override fun answerCall(): Boolean = engine().answerCall()

    override fun hangupCall(): Boolean = engine().hangupCall()

    override fun skipNext(): Boolean = engine().skipNext()

    override fun skipPrevious(): Boolean = engine().skipPrevious()

    override fun isNotificationAccessGranted(): Boolean = engine().isNotificationAccessGranted()

    /**
     * Through the Activity when there is one, for the same reason the battery prompt is:
     * a system screen started from the application context gets its own task, and several
     * OEM skins then put it behind the app. The controller keeps its app-context path as
     * the fallback — it predates this and works — so the two are ordered, not inconsistent.
     */
    override fun openNotificationAccessSettings() {
        val c = engine()
        val a = activity
        if (a != null) {
            runCatching { a.startActivity(MediaInfoProvider.accessSettingsIntent()) }
                .onFailure { c.openNotificationAccessSettings() }
        } else {
            c.openNotificationAccessSettings()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Logcat only, and knowingly. This was written as "the last line guaranteed to
        // reach app_log.txt", which it never was: the sink hands its line to
        // `scope.launch`, and `job.cancel()` three lines down runs before the main
        // looper gets another turn, so the coroutine never starts. Emitting it
        // synchronously instead would let a main-thread line overtake one already
        // queued from a background thread, and the order of these lines is what a
        // post-mortem reads them for.
        DebugLog.i(TAG) { "Plugin detaching — controller disposed" }
        messenger?.let { DashEngineApi.setUp(it, null) }
        messenger = null
        // Pigeon generates no unregister for an event channel, so the sinks are dropped
        // instead: a handler with no sink delivers nothing, and the messenger it sits on
        // is leaving with the engine.
        stateEvents.detach()
        buttonEvents.detach()
        logEvents.detach()
        if (DebugLog.sink === debugLogSink) DebugLog.sink = null
        debugLogSink = null
        controller?.dispose()
        controller = null
        appContext = null
        job.cancel()
        if (RideDiagnostics.appendBarrier === drainRideErrors) {
            RideDiagnostics.appendBarrier = null
        }
        // `shutdown`, not `shutdownNow`: queued lines are errors that have already
        // happened, and the last one before a detach is the likeliest to matter.
        rideErrorWriter.shutdown()
    }

}
