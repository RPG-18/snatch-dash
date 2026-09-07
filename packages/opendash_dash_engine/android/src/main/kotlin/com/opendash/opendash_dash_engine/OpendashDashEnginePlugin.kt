package com.opendash.opendash_dash_engine

import androidx.annotation.NonNull
import com.opendash.opendash_dash_engine.dash.map.GeoPoint
import com.opendash.opendash_dash_engine.dash.protocol.DashCommands
import com.opendash.opendash_dash_engine.util.DebugLog
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bridges Dart to [DashEngineController] — the native dash protocol/video
 * pipeline. Method channel `opendash_dash_engine` for commands, event channel
 * `opendash_dash_engine/state` for the live state stream (connection stage,
 * GPS, joystick events), event channel `opendash_dash_engine/log` mirroring
 * every [DebugLog] line so it also lands in the Dart-side Talker log.
 */
class OpendashDashEnginePlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    private companion object {
        private const val TAG = "OpendashDashEnginePlugin"
    }

    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null

    private lateinit var logChannel: EventChannel
    private var logSink: EventChannel.EventSink? = null

    /**
     * Composed dash frames for the debug screen (spec/dash_screen_debug.md).
     *
     * A separate channel from the state stream on purpose: the payload is a PNG
     * per frame at 2-4 Hz, and subscribing to it is what makes the engine start
     * paying for it at all — see [DashEngineController.onFramePreview]. Nobody
     * listening, nothing produced.
     */
    private lateinit var frameChannel: EventChannel

    /**
     * Held rather than pushed straight at the controller, because the two have no
     * ordering: [onAttachedToEngine] builds the channel before the controller, and
     * a re-attach rebuilds the controller under a subscription that never went
     * away. Keeping the lambda here and applying it at both moments means the
     * preview cannot end up wired to a controller that has since been replaced —
     * or silently unwired because Dart happened to subscribe first.
     */
    private var frameSink: ((Map<String, Any?>) -> Unit)? = null

    private val frameStreamHandler = object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            // Hops to Main: EventSink.success must be called on the platform
            // thread, and the frame loop runs on Dispatchers.Default.
            val sink: (Map<String, Any?>) -> Unit = { payload -> scope.launch { events?.success(payload) } }
            frameSink = sink
            controller?.onFramePreview = sink
        }

        override fun onCancel(arguments: Any?) {
            frameSink = null
            controller?.onFramePreview = null
        }
    }

    /**
     * Our own [DebugLog.sink] lambda, kept so detach can clear the global only
     * when it's still ours — with a second FlutterEngine attached, the first one
     * to detach would otherwise silence native logging for the engine that's
     * still running.
     */
    private var debugLogSink: ((String, String, String) -> Unit)? = null
    private val logStreamHandler = object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { logSink = events }
        override fun onCancel(arguments: Any?) { logSink = null }
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

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "opendash_dash_engine")
        channel.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, "opendash_dash_engine/state")
        eventChannel.setStreamHandler(this)

        logChannel = EventChannel(binding.binaryMessenger, "opendash_dash_engine/log")
        logChannel.setStreamHandler(logStreamHandler)

        frameChannel = EventChannel(binding.binaryMessenger, "opendash_dash_engine/frames")
        frameChannel.setStreamHandler(frameStreamHandler)
        val sink: (String, String, String) -> Unit = { tag, level, message ->
            scope.launch {
                logSink?.success(mapOf("tag" to tag, "level" to level, "message" to message))
            }
        }
        debugLogSink = sink
        DebugLog.sink = sink

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

        // Reattach a subscription that outlived the previous controller.
        controller?.onFramePreview = null
        controller = DashEngineController(
            context = binding.applicationContext,
            scope = scope,
            onState = { state -> scope.launch { eventSink?.success(state) } },
        ).also { c ->
            c.onButton = { code -> scope.launch { eventSink?.success(mapOf("button" to code)) } }
            c.onFramePreview = frameSink
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val c = controller ?: return result.error("NO_ENGINE", "Engine not attached", null)
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")

            "connect" -> { c.connect(); result.success(null) }
            "disconnect" -> { c.disconnect(); result.success(null) }

            "setDestination" -> {
                c.setDestination(
                    call.argument<String>("name"),
                    call.argument<Double>("lat"),
                    call.argument<Double>("lng"),
                )
                result.success(null)
            }
            "clearDestination" -> { c.clearDestination(); result.success(null) }

            "setNavState" -> {
                val rawPoints = call.argument<List<List<Double>>>("points").orEmpty()
                val rawJam = call.argument<List<Int>>("jamSegments").orEmpty()
                c.setNavState(
                    remainingMeters = call.argument<Double>("remainingMeters"),
                    nextTurnMeters = call.argument<Double>("nextTurnMeters"),
                    maneuver = call.argument<Int>("maneuver") ?: DashCommands.NAV_MANEUVER_STRAIGHT,
                    etaHHMM = call.argument<String>("etaHHMM"),
                    isOffRoute = call.argument<Boolean>("offRoute") ?: false,
                    points = rawPoints.map { GeoPoint(it[0], it[1]) },
                    jamSegments = rawJam,
                )
                result.success(null)
            }

            "setFollowMode" -> { c.setFollowMode(call.argument<Boolean>("enabled") ?: true); result.success(null) }
            "panBy" -> {
                c.panBy(
                    (call.argument<Double>("dx") ?: 0.0).toFloat(),
                    (call.argument<Double>("dy") ?: 0.0).toFloat(),
                )
                result.success(null)
            }
            "zoomIn" -> { c.zoomIn(); result.success(null) }
            "zoomOut" -> { c.zoomOut(); result.success(null) }
            "toggleHeadingUp" -> { c.toggleHeadingUp(); result.success(null) }
            "recenter" -> { c.recenter(); result.success(null) }

            "forgetDash" -> { c.forgetDash(); result.success(null) }
            "setSsid" -> { c.setSsid(call.argument<String>("ssid") ?: ""); result.success(null) }
            "setWifiPassword" -> { c.setWifiPassword(call.argument<String>("password") ?: ""); result.success(null) }
            "getConfig" -> result.success(c.currentConfig())

            "updateNowPlaying" -> {
                c.updateNowPlaying(
                    call.argument<String>("title"),
                    call.argument<String>("album") ?: "",
                    call.argument<String>("artist") ?: "",
                )
                result.success(null)
            }
            "updateCall" -> { c.updateCall(call.argument<String>("caller")); result.success(null) }
            "playChime" -> { c.playChime(); result.success(null) }

            "answerCall" -> result.success(c.answerCall())
            "hangupCall" -> result.success(c.hangupCall())
            "skipNext" -> result.success(c.skipNext())
            "skipPrevious" -> result.success(c.skipPrevious())
            "isNotificationAccessGranted" -> result.success(c.isNotificationAccessGranted())
            "openNotificationAccessSettings" -> { c.openNotificationAccessSettings(); result.success(null) }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Logged BEFORE tearing down the sink below — this is the last line that's
        // guaranteed to actually reach app_log.txt for this engine instance.
        DebugLog.i(TAG) { "Plugin detaching — controller disposed" }
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        logChannel.setStreamHandler(null)
        frameChannel.setStreamHandler(null)
        // Both, not just the controller's copy: the sink closes over an EventSink
        // that dies with this engine, so keeping it would let the next attach
        // re-arm a dead lambda — and the controller would then PNG-compress every
        // frame, for nobody, for the rest of the process.
        frameSink = null
        controller?.onFramePreview = null
        if (DebugLog.sink === debugLogSink) DebugLog.sink = null
        debugLogSink = null
        controller?.dispose()
        controller = null
        job.cancel()
    }
}
