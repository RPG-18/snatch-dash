package com.opendash.opendash_dash_engine.dash.map

import android.content.Context
import android.graphics.Bitmap
import com.opendash.opendash_dash_engine.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter

/**
 * The map layer of the dash frame: MapLibre rendering offscreen out of the
 * installed `.pmtiles` packs, one snapshot per redraw.
 *
 * See spec/drawing_from_local_tiles.md, «Конвейер кадра» and «Цикл ждёт
 * снапшот». Three things about this class are decisions, not incidentals:
 *
 * - **[capture] suspends until the snapshot is ready.** `MapSnapshotter.start`
 *   has no blocking form, and drawing overlays over the *previous* snapshot
 *   would let the map lag behind the route and the rider arrow at speed. Making
 *   the frame loop wait keeps map and overlays from the same instant by
 *   construction — and lets the overlays borrow this snapshot's own projection.
 * - **The wait has a deadline.** Waiting without one means a single snapshot
 *   that never completes freezes the dash, which a rider cannot tell apart from
 *   a dropped link. Note what the deadline does and does not bound: the frame
 *   loop stops waiting, but the snapshotter still owes us that request and takes
 *   no other until it lands or [WEDGED_MS] tears it off, so the map itself can
 *   stand still for far longer than one deadline. [skipped] counts exactly those
 *   frames — read it before deciding this design holds.
 * - **Everything here runs on the main thread.** `MapSnapshotter` checks for it
 *   in debug builds and delivers both callbacks through a main-looper `Handler`
 *   regardless, so this is where the object already lives.
 */
class MapSnapshotProvider(private val context: Context) {

    private var snapshotter: MapSnapshotter? = null

    /**
     * A `start()` whose callback has not fired yet.
     *
     * Outliving its deadline is normal; outliving [WEDGED_MS] is not. Tracked
     * because the snapshotter takes one request at a time — `start()` throws if a
     * callback is still pending — so this decides whether the next frame may ask
     * for a snapshot at all.
     */
    private var inFlight = false
    private var inFlightSince = 0L

    /** Bumped by every [prepare]; see [currentGeneration]. */
    private var generation = 0L

    /** Snapshots that missed their deadline. The frame loop moved on without them. */
    @Volatile var timeouts = 0L
        private set

    /**
     * Frames that never even asked for a snapshot because the previous request was
     * still out — i.e. the map on the dash did not move for that frame.
     *
     * This is the number the "wait for the snapshot" design is actually judged on,
     * and it is NOT [timeouts]: the deadline bounds how long the frame loop waits,
     * not how long the map stands still. A snapshot that overruns its deadline
     * keeps the snapshotter (one request at a time) until it lands or [WEDGED_MS]
     * tears it off, and every frame in between comes back null here. Counted
     * separately so the threshold for moving MapLibre into a SurfaceTexture
     * (spec/drawing_from_local_tiles.md) can be taken from measurements instead of
     * from an argument — timeouts=0 with skipped in the hundreds is exactly the
     * pattern that would otherwise look healthy in the log.
     */
    @Volatile var skipped = 0L
        private set

    /**
     * Snapshots torn off by [WEDGED_MS] because they never came back at all.
     *
     * Counted apart from [timeouts] because this is the one path that leaks: a
     * cancelled request's bitmap is dropped inside MapLibre where we cannot reach
     * it. Nonzero here means both "something is badly wrong" and "native memory
     * is growing".
     */
    @Volatile var abandoned = 0L
        private set

    /** Failures from MapLibre's own `ErrorHandler`; that path is otherwise silent. */
    @Volatile var errors = 0L
        private set

    /**
     * Builds the snapshotter for one streaming session.
     *
     * The style arrives assembled and is never replaced: swapping it means a
     * full reload (`Style::Impl::parse` clears every source, layer and image),
     * which is exactly the mid-ride blank map this whole design avoids. A new
     * pack or a new theme therefore takes effect on the next [prepare], i.e.
     * the next connection.
     */
    suspend fun prepare(styleJson: String, width: Int, height: Int) = withContext(Dispatchers.Main) {
        releaseCurrent()
        generation++
        MapLibre.getInstance(context)
        val options = MapSnapshotter.Options(width, height)
            // The frame is 526×300 device pixels exactly — there is no screen
            // density involved, the panel is on the other end of a video stream.
            .withPixelRatio(1f)
            // Logo and attribution are left at their default (on) deliberately:
            // whether the dash's round bezel crops them is an open MVP question
            // (spec/drawing_from_local_tiles.md, «Что должен показать MVP», п. 7),
            // and turning them off before looking would be a silent decision.
            .withStyleBuilder(Style.Builder().fromJson(styleJson))
        snapshotter = MapSnapshotter(context, options)
    }

    /**
     * One frame's worth of map, or null if it failed, missed [deadlineMs], or the
     * previous request is still out.
     *
     * Null means "keep the frame that is already on screen": a missed redraw is
     * a stutter, the alternative — a blank frame — is a map that vanished.
     *
     * **A snapshot that misses the deadline is not cancelled, it is outrun.** The
     * frame loop stops waiting, but the request stays alive and its callback stays
     * ours, so when the snapshot does arrive we are the ones holding it and can
     * recycle it. Cancelling instead would clear the callback inside MapLibre, and
     * the late snapshot would be dropped there — with its bitmap, which lives in
     * the native heap where the Java GC applies no pressure. At 631 KB a frame
     * that adds up precisely when snapshots are already struggling.
     */
    suspend fun capture(
        camera: CameraPosition,
        padding: IntArray,
        deadlineMs: Long,
    ): MapSnapshot? = withContext(Dispatchers.Main) {
        val snapshotter = snapshotter ?: return@withContext null
        val now = System.currentTimeMillis()
        if (inFlight) {
            if (now - inFlightSince < WEDGED_MS) {
                // Still out, but not for long enough to call it dead. Skip this
                // frame rather than pile a second request on a busy snapshotter —
                // `start()` throws while a callback is pending.
                skipped++
                return@withContext null
            }
            // Long past slow. Tear it off and take the leak: a frozen map is worse.
            abandoned++
            DebugLog.w(TAG) { "snapshot wedged for ${now - inFlightSince}ms — cancelling, its bitmap is lost" }
            runCatching { snapshotter.cancel() }
            inFlight = false
        }
        var failed = false
        snapshotter.setCameraPosition(camera)
        snapshotter.setPadding(padding[0], padding[1], padding[2], padding[3])
        inFlight = true
        inFlightSince = now
        try {
            val snapshot = withTimeoutOrNull(deadlineMs) {
                suspendCancellableCoroutine { cont ->
                    // A synchronous throw out of start() means no callback is coming, and
                    // leaving [inFlight] set would silently skip every frame until
                    // WEDGED_MS — the exact freeze this class is built to avoid, minus
                    // even a log line.
                    try {
                        snapshotter.start(
                            { snapshot ->
                                inFlight = false
                                if (cont.isActive) {
                                    cont.resume(snapshot)
                                } else {
                                    // Arrived after the frame loop gave up on it. Nobody
                                    // will draw this one, so free it here — see the note
                                    // on the native heap above.
                                    runCatching { snapshot.bitmap.recycle() }
                                }
                            },
                            { reason ->
                                inFlight = false
                                // Only while the frame loop is still waiting on this one.
                                // A failure that arrives after the deadline was already
                                // counted as a timeout, and counting it twice made a single
                                // slow-then-failed snapshot read as two separate problems.
                                if (cont.isActive) {
                                    failed = true
                                    errors++
                                    DebugLog.w(TAG) { "snapshot failed: $reason" }
                                    cont.resume(null)
                                } else {
                                    DebugLog.w(TAG) { "snapshot failed after its deadline: $reason" }
                                }
                            },
                        )
                    } catch (e: Exception) {
                        inFlight = false
                        failed = true
                        errors++
                        DebugLog.e(TAG, { "snapshotter.start() threw" }, e)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (snapshot == null && !failed) {
                timeouts++
                DebugLog.w(TAG) { "snapshot missed its ${deadlineMs}ms deadline" }
            }
            snapshot
        } catch (e: CancellationException) {
            // The stream is going away — nothing is left to receive a late snapshot,
            // so clearing the callback is the right call here even at the cost of
            // its bitmap.
            runCatching { snapshotter.cancel() }
            inFlight = false
            throw e
        }
    }

    /**
     * The snapshotter this provider is on, as a token for [release].
     *
     * Exists so that "tear down the stream I started" cannot become "tear down
     * whatever is running now". `disconnect()` releases without waiting while the
     * next `startStream()` waits on [prepare]; today the plugin's scope is
     * confined to the main thread and the two stay in order, but that is a
     * coupling in another file, and getting it wrong would null out a live
     * snapshotter. `capture()` would then return null forever, freezing the frame
     * without logging a thing.
     */
    suspend fun currentGeneration(): Long = withContext(Dispatchers.Main) { generation }

    /** Releases the snapshotter [generation] refers to; a no-op once it has moved on. */
    suspend fun release(generation: Long) = withContext(Dispatchers.Main) {
        if (generation != this@MapSnapshotProvider.generation) {
            DebugLog.i(TAG) { "stale release for gen=$generation, now ${this@MapSnapshotProvider.generation} — ignored" }
            return@withContext
        }
        releaseCurrent()
    }

    private fun releaseCurrent() {
        snapshotter?.let { runCatching { it.cancel() } }
        snapshotter = null
        inFlight = false
    }

    companion object {
        private const val TAG = "MapSnapshotProvider"

        /**
         * How long a snapshot may stay out past its deadline before it counts as
         * wedged rather than slow.
         *
         * Missing a 500 ms deadline is a stutter and the request is left to finish
         * on its own. Missing this one means it is never coming, and holding the
         * only snapshotter hostage would freeze the map for the rest of the ride —
         * so it gets cancelled, leaking its bitmap, which is the lesser harm.
         */
        private const val WEDGED_MS = 5_000L

        /**
         * Whether the snapshot came back with no map on it — every sampled pixel
         * identical, which for these styles means the `background` layer alone
         * painted. That one signal covers all three ways the map can go missing:
         * no pack downloaded, riding off the edge of every pack, and a source
         * that failed to open. Without it they are indistinguishable from a
         * render bug, because the overlays keep drawing either way and the rider
         * sees a route hanging in emptiness.
         *
         * Sampled well inside the frame: MapLibre's logo and attribution sit
         * along the bottom edge and would make an empty frame look populated.
         */
        fun isBlank(bitmap: Bitmap): Boolean {
            val first = bitmap.getPixel(bitmap.width / 5, bitmap.height / 5)
            for (ix in 1..4) {
                for (iy in 1..3) {
                    val x = bitmap.width * ix / 5
                    val y = bitmap.height * iy / 5
                    if (bitmap.getPixel(x, y) != first) return false
                }
            }
            return true
        }
    }
}
