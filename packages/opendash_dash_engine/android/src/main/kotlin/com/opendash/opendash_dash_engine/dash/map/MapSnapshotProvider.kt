package com.opendash.opendash_dash_engine.dash.map

import android.content.Context
import android.graphics.Bitmap
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics
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

    /**
     * What [prepare] was given, kept so [rebuild] can build the same snapshotter
     * again. The style is assembled once per stream and never swapped (see
     * [prepare]), so holding it costs one string for the length of the session.
     */
    private var styleJson: String? = null
    private var frameWidth = 0
    private var frameHeight = 0

    /**
     * Failures in a row, reset by any snapshot that lands.
     *
     * A single failure is a missed redraw. A run of them means the snapshotter
     * itself is refusing work — the 2026-09-04 field mode where every `start()`
     * answers `Map is currently rendering an image` — and no number of further
     * requests will change that. See [rebuild].
     */
    private var consecutiveFailures = 0

    /** Set from a callback; acted on at the top of the next [capture], on the main thread. */
    private var needsRebuild = false

    /** When the last [rebuild] ran, so a failing one cannot be retried every frame. */
    private var lastRebuildAtMs = 0L

    /**
     * Rebuilds since the last snapshot that actually landed.
     *
     * [rebuild] answers "this snapshotter is broken". It has no answer for "the
     * style is broken", and past [REBUILD_GIVE_UP_AFTER] that is the only reading
     * left: a fresh snapshotter parsing the same style hits the same wall, so the
     * next one will too. The 2026-09-05 ride is the case — one glyph range missing
     * from the assets, and every rebuild reloaded a style that still referenced it:
     * wedge, tear off, rebuild, fail, for eleven minutes. See [REBUILD_BACKOFF_MS].
     */
    private var rebuildsSinceSuccess = 0

    /**
     * Which snapshotter a pending request was issued to — bumped per request and
     * whenever the snapshotter is replaced, so a callback can tell whether it
     * still speaks for the current one. See its use in [capture].
     */
    private var snapshotterIssue = 0L

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
     * Times the snapshotter had to be thrown away and built again — see [rebuild].
     *
     * Reported next to the others because it is the difference between "the map
     * came back" and "the map was gone for the rest of the ride": nonzero here
     * means the session hit the dead-snapshotter state and got out of it, and a
     * rising count across a ride means it keeps happening.
     */
    @Volatile var rebuilds = 0L
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
        this@MapSnapshotProvider.styleJson = styleJson
        frameWidth = width
        frameHeight = height
        consecutiveFailures = 0
        rebuildsSinceSuccess = 0
        needsRebuild = false
        resetCounters()
        MapLibre.getInstance(context)
        MapLibreLogBridge.install()
        snapshotter = buildSnapshotter(styleJson, width, height)
    }

    /**
     * Zeroes the counters so a `[map]` line describes the session it appears in.
     *
     * These used to run for the life of the process, on the argument that any of
     * them being nonzero at all is the signal and a per-session view would hide a
     * provider degrading across a whole ride. Reading the 2026-09-05 logs is what
     * settled it the other way: a diagnostics file is written **per session**, and
     * that one opened with `frames=0/? … timeouts=58 skipped=945 wedged=56` on a
     * session thirty-nine seconds old. Every number in it belonged to the ride
     * before. Totals no window can be measured against are not a signal, they are
     * a reader working out which half of each figure to ignore.
     *
     * What the old argument was right about survives as the carry-over line: said
     * once, where "the previous session ended badly" is the whole message, instead
     * of smeared into every window for the rest of the ride. [abandoned] is the
     * one that genuinely outlives a session — its leaked bitmaps are in the
     * process's native heap, not this object's — and that is exactly what the line
     * reports.
     */
    private fun resetCounters() {
        if (timeouts + skipped + abandoned + errors + rebuilds > 0) {
            RideDiagnostics.log(
                "map",
                "previous session left timeouts=$timeouts skipped=$skipped " +
                    "wedged=$abandoned snapErr=$errors rebuilds=$rebuilds" +
                    if (abandoned > 0) " — ${abandoned * BITMAP_KB / 1024} MiB of bitmaps leaked and still held" else "",
            )
        }
        timeouts = 0
        skipped = 0
        abandoned = 0
        errors = 0
        rebuilds = 0
    }

    /**
     * A fresh snapshotter on the style this provider was [prepare]d with.
     *
     * Separate from [prepare] because it is also the recovery path: [rebuild]
     * needs to build the same thing again without bumping [generation], which
     * belongs to the stream, not to the object underneath it.
     */
    private fun buildSnapshotter(styleJson: String, width: Int, height: Int): MapSnapshotter {
        val options = MapSnapshotter.Options(width, height)
            // The frame is 526×300 device pixels exactly — there is no screen
            // density involved, the panel is on the other end of a video stream.
            .withPixelRatio(1f)
            // Both default to ON, so both have to be turned off by name. Decided
            // 2026-09-04 (review-spec.md, C6) without waiting for the hardware:
            // on 526×300 under a round bezel they cost a visible share of the
            // frame, and the attribution line repeats per active source — five
            // installed packs would stack five identical «© OpenMapTiles».
            // The licence obligation moves entirely onto the About card in
            // Settings, which is why it is not optional there.
            .withLogo(false)
            .withAttribution(false)
            .withStyleBuilder(Style.Builder().fromJson(styleJson))
        return MapSnapshotter(context, options)
    }

    /**
     * Throws the snapshotter away and builds another one on the same style.
     *
     * The escape hatch for a snapshotter that will not take work any more. Field
     * logs of 2026-09-04 (plan.md 1.5) show `cancel()` is not enough: after a
     * wedged request was torn off, **468 consecutive** `start()` calls came back
     * `Map is currently rendering an image`, and the map stayed blank for the
     * rest of the session — 40 to 70 seconds of streaming with no map at all,
     * ending only when the rider disconnected. Whatever `cancel()` releases, it
     * is not the thing that makes the next `start()` legal.
     *
     * Costs a style re-parse (~50 layers per pack) on the main thread, which is
     * why it is not the response to a single failure — but against a map that is
     * gone for the whole ride, one stutter is not a price worth arguing about.
     */
    private fun rebuild(reason: String) {
        val json = styleJson
        needsRebuild = false
        consecutiveFailures = 0
        // Anything still owed to us by the outgoing snapshotter now speaks for
        // nobody — see the note where this is captured in [capture].
        snapshotterIssue++
        if (json == null) return // never prepared — nothing to rebuild from
        rebuilds++
        rebuildsSinceSuccess++
        lastRebuildAtMs = System.currentTimeMillis()
        DebugLog.w(TAG) { "rebuilding the snapshotter: $reason" }
        releaseCurrent()
        snapshotter = runCatching { buildSnapshotter(json, frameWidth, frameHeight) }
            .onFailure { DebugLog.e(TAG, { "snapshotter rebuild failed" }, it) }
            .getOrNull()
        // A failed rebuild leaves no snapshotter at all, and [capture] returns
        // early before it can ever reach start() — so nothing would count another
        // failure and nothing would ask for another rebuild. The map would stay
        // blank for the rest of the session on one unlucky allocation. Ask again
        // instead, paced by [REBUILD_COOLDOWN_MS].
        if (snapshotter == null) needsRebuild = true
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
            snapshotter?.let { runCatching { it.cancel() } }
            inFlight = false
            // And do not trust that cancel() gave the snapshotter back: in the
            // field it did not, and every later start() failed. Replace it.
            needsRebuild = true
        }
        if (needsRebuild) {
            // Past [REBUILD_GIVE_UP_AFTER] the fault is in the style, not in the
            // object — replacing it every two seconds is pure cost. Slow down.
            val backingOff = rebuildsSinceSuccess >= REBUILD_GIVE_UP_AFTER
            val cooldown = if (backingOff) REBUILD_BACKOFF_MS else REBUILD_COOLDOWN_MS
            if (now - lastRebuildAtMs < cooldown) {
                // Waiting out the cooldown on a snapshotter we have already called
                // dead. Asking it anyway is what the pre-backoff code did, and at
                // 4 fps through a 60s wait that is 240 guaranteed failures — each
                // one a warn line in the ride log that the real fault has to be
                // found in. Skip like any other frame the map could not redraw.
                skipped++
                return@withContext null
            }
            rebuild(
                "after ${abandoned + errors} snapshot failures" +
                    if (backingOff) " — $rebuildsSinceSuccess rebuilds have not helped, retrying every ${REBUILD_BACKOFF_MS / 1000}s" else "",
            )
        }
        val snapshotter = snapshotter ?: return@withContext null
        var failed = false
        snapshotter.setCameraPosition(camera)
        snapshotter.setPadding(padding[0], padding[1], padding[2], padding[3])
        inFlight = true
        inFlightSince = now
        // Which snapshotter this request belongs to. A callback carries no
        // identity of its own, and a torn-off request can still fire after its
        // snapshotter was replaced — we already know cancel() does not reliably
        // stop one. Without this check that late callback would clear [inFlight]
        // and [consecutiveFailures] belonging to the REPLACEMENT's request,
        // letting the next frame call start() on a snapshotter that is still
        // busy: straight back into "Map is currently rendering an image".
        val issue = ++snapshotterIssue
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
                                if (issue != snapshotterIssue) {
                                    // Belongs to a snapshotter we have since thrown away.
                                    DebugLog.w(TAG) { "snapshot from a replaced snapshotter — dropping" }
                                    runCatching { snapshot.bitmap.recycle() }
                                    return@start
                                }
                                inFlight = false
                                consecutiveFailures = 0
                                rebuildsSinceSuccess = 0
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
                                if (issue != snapshotterIssue) {
                                    DebugLog.w(TAG) { "failure from a replaced snapshotter: $reason" }
                                    return@start
                                }
                                inFlight = false
                                noteFailure()
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
                        noteFailure()
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
    suspend fun release(generation: Long) = withContext(Dispatchers.Main) { releaseNow(generation) }

    /**
     * [release] for callers already on the main thread — and for the one caller
     * that cannot afford a coroutine at all.
     *
     * The plugin's `onDetachedFromEngine` disposes the controller and cancels the
     * scope on the very next line, so a `scope.launch { release(...) }` there had
     * no chance to run: the MapSnapshotter, its parsed style, its GL context and
     * its open `.pmtiles` handles leaked on every detach — which in development
     * is every hot restart.
     */
    fun releaseNow(generation: Long) {
        if (generation != this.generation) {
            DebugLog.i(TAG) { "stale release for gen=$generation, now ${this.generation} — ignored" }
            return
        }
        releaseCurrent()
    }

    /**
     * One more failure in a row; past [REBUILD_AFTER_FAILURES] the snapshotter is
     * treated as dead rather than unlucky.
     *
     * Only raises a flag — the replacement happens at the top of the next
     * [capture], because this runs from MapLibre's callback and [rebuild] must
     * not run while the object it replaces is mid-callback.
     */
    private fun noteFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= REBUILD_AFTER_FAILURES) needsRebuild = true
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
         * so it gets cancelled, leaking its bitmap, which is the lesser harm, and
         * the snapshotter itself is replaced (see [rebuild]) because cancelling
         * alone left it refusing every later request.
         */
        private const val WEDGED_MS = 5_000L

        /**
         * Failures in a row before the snapshotter is replaced rather than asked
         * again.
         *
         * Three, not one: a genuine one-off (a tile that failed to open, a style
         * resource that lost a race) costs a single redraw, and rebuilding on it
         * would trade a stutter for a bigger stutter. Three in a row at 4 fps is
         * under a second of wall clock, so the dead-snapshotter case is still
         * caught long before a rider could notice — while in the field it produced
         * 468 of them.
         */
        private const val REBUILD_AFTER_FAILURES = 3

        /**
         * Shortest gap between two [rebuild] attempts.
         *
         * Only matters when a rebuild itself fails: the flag stays raised, and
         * without a pace the frame loop would re-parse the style four times a
         * second on the main thread — turning a broken map into a broken phone.
         * Two seconds still recovers well inside the time it takes a rider to
         * notice, and only ever applies while the map is already gone.
         */
        private const val REBUILD_COOLDOWN_MS = 2_000L

        /**
         * Rebuilds in a row without a single snapshot landing, after which the
         * pace drops to [REBUILD_BACKOFF_MS].
         *
         * Five, because the recovery this class was built for takes exactly one:
         * a snapshotter that stopped taking work is replaced and the very next
         * frame draws. Needing five means the fault is not in the object being
         * replaced. A handful of extra tries costs a few seconds of a map that is
         * already frozen and covers the case where the two overlap — a wedged
         * request whose late callback poisons the replacement.
         */
        private const val REBUILD_GIVE_UP_AFTER = 5

        /**
         * The pace [rebuild] falls back to once it is clearly not working.
         *
         * Not a full stop, because the cause can pass on its own — a broken tile
         * or an unrenderable label leaves the viewport as the rider moves, and a
         * provider that gave up for good would stay blank across a fault that
         * cured itself. What a full stop is really for is the cost of asking, and
         * that is what this bounds: each attempt re-parses ~50 layers per pack on
         * the main thread and, when it wedges again, abandons a 631 KB bitmap into
         * the native heap. At two seconds that is ~19 MB a minute of leak against
         * a map that is not coming back; at sixty it is one frame's worth.
         */
        private const val REBUILD_BACKOFF_MS = 60_000L

        /**
         * One abandoned snapshot's bitmap, for turning [abandoned] into the number
         * that actually matters: 526×300 at ARGB_8888.
         */
        private const val BITMAP_KB = 526L * 300 * 4 / 1024

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
