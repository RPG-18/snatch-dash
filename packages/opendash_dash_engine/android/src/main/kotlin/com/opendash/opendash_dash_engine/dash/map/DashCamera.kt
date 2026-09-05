package com.opendash.opendash_dash_engine.dash.map

/**
 * Where the camera target lands on the dash frame.
 *
 * MapLibre offers exactly one way to say it — padding — so both things that
 * used to move the picture around go through here: the nav view's bias toward
 * the lower third (what was `pivotY` and a translated canvas origin) and the
 * joystick pan (what was `panX`/`panY` added to the canvas). Expressing them as
 * camera state instead of a transform over a finished raster is what lets the
 * overlays take their projection straight off the snapshot — see
 * spec/drawing_from_local_tiles.md, «Камера, а не поворот растра».
 */
object DashCamera {

    /**
     * Vertical position of the camera target in heading-up mode, as a fraction
     * of frame height: pushed into the lower third so the road AHEAD fills the
     * frame, the way Google Maps navigation does it. North-up centres instead —
     * with the map not turning, there is no "ahead" to favour.
     */
    const val NAV_PIVOT_Y = 0.66f

    /**
     * How far the joystick may pan, as a fraction of the frame.
     *
     * Pan used to be unbounded because it only translated a canvas. As camera
     * padding it costs viewport — the inset is twice the shift, and it stacks on
     * top of [NAV_PIVOT_Y]'s own inset — so this is set where the worst case
     * (nav view, panned fully up) still leaves MapLibre more than a third of the
     * frame height to render into. Nothing is lost by the bound: the view
     * snaps back to the rider a few seconds after the last nudge, and as of
     * today nothing in the app calls `panBy` at all (the dash's joystick is
     * wired to zoom only).
     */
    const val MAX_PAN_FRACTION = 0.12f

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
        val dy = (pivotY - panY) - height / 2f
        return intArrayOf(
            if (dx > 0) (2 * dx).toInt() else 0,
            if (dy > 0) (2 * dy).toInt() else 0,
            if (dx < 0) (-2 * dx).toInt() else 0,
            if (dy < 0) (-2 * dy).toInt() else 0,
        )
    }
}
