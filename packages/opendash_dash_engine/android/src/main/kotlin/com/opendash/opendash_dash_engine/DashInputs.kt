package com.opendash.opendash_dash_engine

import com.opendash.opendash_dash_engine.dash.map.GeoPoint

/**
 * Where the dash is being sent, as one value.
 *
 * `lat` and `lng` are non-null and live in the same object on purpose. They used to be two
 * nullable `@Volatile` fields assigned one after the other, so a frame that read them between
 * the two assignments placed the destination pin at the new latitude and the old longitude —
 * a real defect with no fallback behind it (pipeline.md §4.4). A half-set destination is not
 * representable here.
 *
 * The label lives in [DashInputs.destName] rather than here, because `setDestination` has
 * always accepted a name with no coordinates and stored it anyway; keeping it inside this
 * class would have quietly dropped that case.
 */
internal data class Destination(
    val lat: Double,
    val lng: Double,
)

/**
 * A route and the traffic colouring that belongs to it — together, always.
 *
 * The pairing is the point. [jam] carries one traffic-level code per geometry segment, so
 * entry `i` covers `points[i]..points[i+1]` and a correct array is exactly `points.size - 1`
 * long; the encoding matches Dart's `JamLevel.index`. Empty means "no traffic data for this
 * route", and [OverlayRenderer] then draws a solid line.
 *
 * Keeping them in one object is what stops a frame from drawing a new route with the previous
 * route's colours — the other confirmed torn read of pipeline.md §4.4. That used to be caught,
 * accidentally, by OverlayRenderer's `jam.size == segCount` check, which exists for missing
 * traffic data rather than for a race, and which turned the race into a one-frame colour blink
 * instead of a crash.
 */
internal data class RouteGeometry(
    val points: List<GeoPoint> = emptyList(),
    val jam: List<Int> = emptyList(),
)

/**
 * Everything the Flutter side pushes into the engine that a frame needs to read.
 *
 * One immutable snapshot replacing eleven `@Volatile` fields. The frame loop reads
 * `inputs.value` once at the top of a tick and works from that copy for the whole frame, so
 * every field it draws describes the same instant — which is the property the individual
 * fields could not give however carefully each one was published.
 *
 * Camera state is deliberately absent; see the note next to `DashEngineController.zoom`.
 */
internal data class DashInputs(
    /** The destination's label, which may be set while [dest] is not — see [Destination]. */
    val destName: String? = null,
    val dest: Destination? = null,
    /**
     * Whether the dash should show navigation chrome.
     *
     * Kept as its own flag rather than derived from `dest != null`, because it is also read by
     * [DashSession] off the published state for its own chrome decisions, and the two have
     * drifted apart before: a destination can be set while navigation has not started.
     */
    val navigating: Boolean = false,
    val route: RouteGeometry = RouteGeometry(),
    val remainingM: Double? = null,
    val offRoute: Boolean = false,
    val nowPlayingTitle: String? = null,
    val incomingCaller: String? = null,
    /**
     * True for ANY call — ringing, answered or outgoing — unlike [incomingCaller], which only
     * ever carries a ringing one.
     *
     * Dart's button dispatcher needs the distinction so the dash's reject/hangup button can end
     * a call that has already been answered, matching the original `DashViewModel.onButton`
     * (`call != null`, against `call.incoming == true` for the answer button).
     */
    val hasActiveCall: Boolean = false,
)
