package com.opendash.opendash_dash_engine.dash.map

import kotlin.math.atan
import kotlin.math.tan

/**
 * Where the camera target lands on the dash frame.
 *
 * MapLibre offers exactly one working way to say it — padding carried in
 * `CameraPosition.Builder.padding` (NOT `MapSnapshotter.setPadding`, which the
 * 13.6.0 core applies only alongside a region — see MapSnapshotProvider.capture)
 * — so both things that used to move the picture around go through here: the
 * nav view's bias toward the bottom of the frame (what was `pivotY` and a
 * translated canvas origin) and the joystick pan (what was `panX`/`panY` added
 * to the canvas). Expressing them as camera state instead of a transform over
 * a finished raster is what lets the overlays take their projection straight
 * off the snapshot — see spec/drawing_from_local_tiles.md, «Камера, а не
 * поворот растра».
 */
object DashCamera {

    /**
     * Vertical position of the camera target in heading-up mode, as a fraction
     * of frame height: pushed down to 0.85h — 15% above the bottom edge of the
     * 526×300 frame — so the road AHEAD fills the frame, the way Google Maps
     * navigation does it. How this reads on the round panel is unmodeled: the
     * bezel crops the frame's corners and the frame→visible-circle mapping
     * lives nowhere in code, so the value is confirmed by ride
     * (plans/active/2026-10-01-forward-view.md, stage 2). North-up centres
     * instead — with the map not turning, there is no "ahead" to favour.
     */
    const val NAV_PIVOT_Y = 0.85f

    /**
     * The lowest the camera target may sit, as a fraction of frame height.
     *
     * Applies when the joystick pans up on top of [NAV_PIVOT_Y]'s bias: without
     * the clamp the pair pushes the target to 0.97h and the rider marker — a
     * 17 px halo under a kite — slides off the frame, under the bezel and the
     * firmware's widgets. 0.90 keeps it on: 0.90·300 + 17 = 287 < 300. Pan is
     * unwired today; the clamp is the only thing that keeps the pair legal the
     * day it is wired. [NAV_PIVOT_Y] itself must stay at or below this value,
     * or the clamp silently overrides the pivot — asserted in DashCameraTest.
     */
    const val MAX_TARGET_Y_FRACTION = 0.90f

    /**
     * How far the joystick may pan, as a fraction of the frame.
     *
     * Pan used to be unbounded because it only translated a canvas. As camera
     * padding the inset is twice the shift of the target — the core moves the
     * perspective center by half the inset (`getCenterOffset`), and it stacks
     * on top of [NAV_PIVOT_Y]'s own inset. Padding does NOT eat viewport: the
     * snapshotter renders the whole frame at any padding, the inset only moves
     * where the target lands — the old "a third of the frame left to render
     * into" margin was built on that false model and was dropped with it. What
     * the bound now protects, together with [MAX_TARGET_Y_FRACTION], is the
     * marker staying on the frame. Nothing is lost by the bound: the view
     * snaps back to the rider a few seconds after the last nudge, and as of
     * today nothing in the app calls `panBy` at all (the dash's joystick is
     * wired to zoom only).
     */
    const val MAX_PAN_FRACTION = 0.12f

    /**
     * Native 13.6.0 further limits pitch when padding moves the perspective centre.
     * Match Transform.getMaxPitchForEdgeInsets before capture so the snapshot,
     * rider arrow and camera log use one angle. CameraPosition.Builder alone does
     * not apply this limit (60 requested with our 210 px top inset becomes 48.896°).
     */
    fun effectiveTilt(tilt: Double, fieldOfView: Double, height: Int, padding: IntArray): Double {
        val halfFov = Math.toRadians(fieldOfView) / 2.0
        val offsetY = (padding[1] - padding[3]) / 2.0
        val fovAboveCenter = atan((1.0 + 2.0 * offsetY / height) * tan(halfFov))
        val maximum = 60.0 + Math.toDegrees(halfFov - fovAboveCenter)
        return tilt.coerceIn(0.0, minOf(60.0, maximum))
    }

    /**
     * Padding as MapLibre wants it: `[left, top, right, bottom]`, frame pixels.
     *
     * The camera target lands in the middle of whatever padding leaves over, so
     * an inset of `2·d` on one side shifts the target by `d` toward it. Only one
     * side of each axis is ever padded — the opposite one stays 0, keeping the
     * numbers non-negative and the remaining viewport as large as it can be.
     *
     * [panX]/[panY] are the joystick's offset of the *map*, so the target moves
     * the other way, exactly as when they were added to the canvas origin.
     * Callers keep them within [MAX_PAN_FRACTION] of the frame.
     */
    fun padding(
        width: Int,
        height: Int,
        headingUp: Boolean,
        panX: Float,
        panY: Float,
    ): IntArray {
        val pivotY = if (headingUp) height * NAV_PIVOT_Y else height / 2f
        val dx = -panX
        val targetY = (pivotY - panY).coerceAtMost(height * MAX_TARGET_Y_FRACTION)
        val dy = targetY - height / 2f
        return intArrayOf(
            if (dx > 0) (2 * dx).toInt() else 0,
            if (dy > 0) (2 * dy).toInt() else 0,
            if (dx < 0) (-2 * dx).toInt() else 0,
            if (dy < 0) (-2 * dy).toInt() else 0,
        )
    }
}
