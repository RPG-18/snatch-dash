package com.opendash.opendash_dash_engine.dash.video

import android.graphics.Canvas

/**
 * The encoder as the frame loop sees it — four calls, no MediaCodec.
 *
 * [DashEncoder] is the only implementation that matters; the interface exists so
 * [com.opendash.opendash_dash_engine.dash.FrameStreamer] can be driven by a fake on the JVM.
 * That is not a hypothetical convenience: the loop's deadline, its drop accounting and the
 * RTP timestamp rule of spec/video.md are all properties of the loop rather than of the
 * codec, and until this seam existed none of them could be checked without a phone, a dash
 * and a ride (pipeline.md §4.8).
 *
 * Nothing here is allowed to block for long: every call happens inside the frame's budget.
 */
// Public rather than internal for one mechanical reason: [DashEncoder] is public, and Kotlin
// refuses to let a public class inherit from a less visible supertype.
interface FrameEncoder {
    /** Draw one frame into the encoder's input surface. */
    fun renderFrame(draw: (Canvas) -> Unit)

    /** Pull whatever the codec has ready; returns how many FRAMES came out (config excluded). */
    fun drain(): Int

    /** Retarget the running encoder without rebuilding it or forcing an IDR. */
    fun requestBitrate(bps: Int)

    fun release()
}
