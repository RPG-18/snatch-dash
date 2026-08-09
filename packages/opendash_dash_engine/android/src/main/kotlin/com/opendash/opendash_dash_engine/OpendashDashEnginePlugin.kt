package com.opendash.opendash_dash_engine

import androidx.annotation.NonNull
import com.opendash.opendash_dash_engine.dash.map.GeoPoint
import com.opendash.opendash_dash_engine.util.DebugLog
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null

    private lateinit var logChannel: EventChannel
    private var logSink: EventChannel.EventSink? = null

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

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var controller: DashEngineController? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "opendash_dash_engine")
        channel.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, "opendash_dash_engine/state")
        eventChannel.setStreamHandler(this)

        logChannel = EventChannel(binding.binaryMessenger, "opendash_dash_engine/log")
        logChannel.setStreamHandler(logStreamHandler)
        val sink: (String, String, String) -> Unit = { tag, level, message ->
            scope.launch {
                logSink?.success(mapOf("tag" to tag, "level" to level, "message" to message))
            }
        }
        debugLogSink = sink
        DebugLog.sink = sink

        controller = DashEngineController(
            context = binding.applicationContext,
            scope = scope,
            onState = { state -> scope.launch { eventSink?.success(state) } },
        ).also { c ->
            c.onButton = { code -> scope.launch { eventSink?.success(mapOf("button" to code)) } }
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
                    maneuver = call.argument<Int>("maneuver") ?: 0x0B,
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

            "setWallpaper" -> {
                c.setWallpaper(
                    call.argument<String>("path"),
                    call.argument<String>("kind"),
                    call.argument<String>("fit"),
                    (call.argument<Double>("biasX") ?: 0.0).toFloat(),
                    (call.argument<Double>("biasY") ?: 0.0).toFloat(),
                )
                result.success(null)
            }
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
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        logChannel.setStreamHandler(null)
        if (DebugLog.sink === debugLogSink) DebugLog.sink = null
        debugLogSink = null
        controller?.dispose()
        controller = null
        job.cancel()
    }
}
