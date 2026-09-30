package com.opendash.opendash_dash_engine

import com.opendash.opendash_dash_engine.dash.DashState
import com.opendash.opendash_dash_engine.dash.WifiConnStatus

/**
 * The engine's own enums, as the generated boundary names them.
 *
 * **No `else` branch anywhere in this file, on purpose.** These are the one place a
 * native enum meets its schema twin, and an exhaustive `when` makes adding a case to
 * either side a compile error here — which is the whole reason for describing the
 * boundary in a schema rather than passing `.name` strings that a reader on the far
 * side matched with a `switch` and a `_ =>` default. Under that arrangement a new
 * `DashState` arrived in Dart as `idle` and nothing said so.
 */
internal fun DashState.toStage(): DashStage = when (this) {
    DashState.IDLE -> DashStage.IDLE
    DashState.CONNECTING -> DashStage.CONNECTING
    DashState.AUTHENTICATING -> DashStage.AUTHENTICATING
    DashState.READY -> DashStage.READY
    DashState.STREAMING -> DashStage.STREAMING
    DashState.ERROR -> DashStage.ERROR
}

internal fun WifiConnStatus.toWifiStatus(): WifiStatus = when (this) {
    WifiConnStatus.IDLE -> WifiStatus.IDLE
    WifiConnStatus.REQUESTING -> WifiStatus.REQUESTING
    WifiConnStatus.CONNECTED -> WifiStatus.CONNECTED
    WifiConnStatus.ERROR -> WifiStatus.ERROR
}

/**
 * `DebugLog`'s single-letter level, as the schema's enum.
 *
 * A `String` here rather than an enum because that is what `DebugLog.sink` has always
 * handed out; unknown letters fall back to [DashLogLevel.INFO] rather than throwing,
 * since a logging path is the last thing that should take the app down.
 */
internal fun String.toLogLevel(): DashLogLevel = when (this) {
    "D" -> DashLogLevel.DEBUG
    "W" -> DashLogLevel.WARNING
    "E" -> DashLogLevel.ERROR
    else -> DashLogLevel.INFO
}
