package com.opendash.opendash_dash_engine.dash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.LruCache
import com.opendash.opendash_dash_engine.BuildConfig
import com.opendash.opendash_dash_engine.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Raster tile provider with memory + disk cache, for the off-screen dash-stream
 * renderer only (the in-app map uses `yandex_mapkit` separately — see the
 * project's migration notes on why the two are NOT the same tile source).
 *
 * Tiles are darkened ONCE at load (invert + desaturate + dim) and the dark bitmap
 * is cached, so the render loop never re-runs the colour matrix per frame — a key
 * power win at 4 fps.
 *
 * While riding, the process is bound to the Tripper's WiFi (no internet), so tiles
 * must come from cache — [prefetch]/[prefetchRoute] populate it while internet is
 * still reachable. Cache misses fetch through whichever network has connectivity
 * (cellular when bound to the dash WiFi), rate-limited to avoid hot loops.
 */
class TileProvider(context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "TileProvider"
        // Licensed raster source (MapTiler), keyed via BuildConfig.MAPTILER_API_KEY —
        // see android/maptiler.defaults.properties for the bring-your-own-key template.
        // This used to hit tile.openstreetmap.org directly, OSM's own dev-only tile
        // server; that stopped working once OSM added anti-hotlink protection (requires
        // a rotating TOTP token minted by JS on openstreetmap.org, so any direct request
        // — this one included — gets rejected with HTTP 400). Tiles come back at 512px
        // here vs. the old 256px; that's fine, MapRenderer draws each into a fixed
        // Mercator.TILE_SIZE-sized dst Rect so Canvas.drawBitmap scales it regardless.
        // Deliberately NOT the in-app Yandex MapKit map — see the map-renderer finding
        // in the project's migration plan for why the two can't share a tile source.
        // Not `const` — BuildConfig.MAPTILER_API_KEY isn't a Kotlin compile-time constant.
        //
        // .webp over .png: BitmapFactory decodes it natively (no extra dependency),
        // and it's ~55-60% smaller than the same tile as .png on populated tiles
        // (streets/buildings/labels) — measured across Moscow/SPB/rural samples at
        // various zooms. The one place .png would win is a near-blank tile (open
        // water, solid forest), where .webp's container overhead briefly exceeds
        // .png's near-perfect flat-color compression — but that's a swing of a few
        // hundred bytes on tiles that are already tiny, versus tens of KB saved on
        // every tile that actually has content. Net effect on a real ride's cache
        // is close to the populated-tile number, not the outlier.
        private val URL_TEMPLATE =
            "https://api.maptiler.com/maps/streets-v2/%d/%d/%d.webp?key=${BuildConfig.MAPTILER_API_KEY}"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val MAX_PREFETCH_TILES = 600
        private const val MIN_FETCH_GAP_MS = 60L // be gentle on the tile host + the radio

        // Disk cache is unbounded otherwise — a full ride's worth of prefetch plus
        // months of ad-hoc riding would otherwise grow tiles_dash_webp/ forever. Evict
        // down to 90% of budget rather than exactly to it, so a write that's just
        // over the line doesn't trigger another eviction pass on the very next one.
        private const val MAX_DISK_CACHE_BYTES = 200L * 1024 * 1024
        private const val DISK_EVICT_TARGET_BYTES = MAX_DISK_CACHE_BYTES * 9 / 10

        // Sized in decoded bytes, not entry count — tiles come back at 512px now
        // (see above), so a flat entry cap under- or over-commits RAM depending on
        // how compressible the source imagery is.
        private const val MEMORY_CACHE_BYTES = 24 * 1024 * 1024
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // Cache dir per tile source so a source switch doesn't serve stale tiles.
    // "_webp" suffix specifically because the .png -> .webp switch changes the
    // decoded bytes for the same z/x/y key — reusing tiles_dash/ would let old
    // .png files sit there forever (diskFile() no longer names or globs them,
    // so they'd never be counted in diskBytes or reachable by maybeEvict either).
    private val diskDir = File(context.cacheDir, "tiles_dash_webp").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastFetchAt = 0L

    // Running total of tiles_dash_webp/ bytes on disk; seeded once by scanning the dir
    // (cheap — at most a few thousand small files) and kept in sync by every write
    // and eviction after that, so [maybeEvict] never has to re-walk the tree.
    private val diskBytes = AtomicLong(0)
    private val evicting = AtomicBoolean(false)

    init {
        scope.launch(Dispatchers.IO) {
            var bytes = 0L
            // Also sweeps up any `.tmp` orphaned by a process kill between
            // writeBytes() and renameTo() in a previous run — those aren't valid
            // tiles and [maybeEvict] never touches `.tmp` names, so nothing else
            // would ever reclaim them.
            diskDir.listFiles()?.forEach { f -> if (f.name.endsWith(".tmp")) f.delete() else bytes += f.length() }
            diskBytes.set(bytes)
        }
    }

    /** Non-blocking: returns the cached tile, else kicks off async load. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val xw = ((x % max) + max) % max
        val key = "$z/$xw/$y"

        memory.get(key)?.let { return it }

        if (inflight.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val raw = loadDisk(key) ?: fetch(z, xw, y, key)
                    if (raw != null) memory.put(key, raw)
                } finally {
                    inflight.remove(key)
                }
            }
        }
        return null
    }

    /** Prefetch tiles around a point (and optionally a straight corridor) into disk. */
    fun prefetch(lat: Double, lng: Double, fromLat: Double? = null, fromLng: Double? = null) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            // 11..20 so the rider's vicinity has tiles at every zoom level offline
            // (default nav zoom is now 19, max 20).
            for (z in 11..20) {
                val radius = if (z in 15..16) 2 else 1
                count += prefetchAround(lat, lng, z, radius)
                if (fromLat != null && fromLng != null) {
                    count += prefetchAround(fromLat, fromLng, z, radius)
                    if (z in 12..13) for (i in 1..8) {
                        val f = i / 9.0
                        count += prefetchAround(fromLat + (lat - fromLat) * f, fromLng + (lng - fromLng) * f, z, 1)
                    }
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            DebugLog.i(TAG) { "Prefetch (point) done — ~$count tiles ensured" }
        }
    }

    /** Prefetch tiles along the actual route polyline so offline riding has coverage. */
    fun prefetchRoute(route: List<GeoPoint>) {
        if (route.size < 2) return
        scope.launch(Dispatchers.IO) {
            var count = 0
            // Sample the polyline so we don't fetch a tile for every vertex.
            for (z in 12..16) {
                val seen = HashSet<String>()
                val step = if (z >= 15) 1 else 3
                var i = 0
                while (i < route.size) {
                    val p = route[i]
                    val cx = Mercator.lngToTileX(p.lng, z).toInt()
                    val cy = Mercator.latToTileY(p.lat, z).toInt()
                    val r = if (z >= 15) 1 else 0
                    for (dx in -r..r) for (dy in -r..r) {
                        val key = "$z/${cx + dx}/${cy + dy}"
                        if (seen.add(key) && !diskFile(key).exists()) {
                            fetchKey(z, cx + dx, cy + dy, key); count++
                        }
                    }
                    i += step
                    if (count > MAX_PREFETCH_TILES) break
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            DebugLog.i(TAG) { "Prefetch (route) done — ~$count tiles ensured" }
        }
    }

    private fun prefetchAround(lat: Double, lng: Double, z: Int, radius: Int): Int {
        val cx = Mercator.lngToTileX(lng, z).toInt()
        val cy = Mercator.latToTileY(lat, z).toInt()
        var n = 0
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val x = cx + dx; val y = cy + dy
            if (y < 0 || y >= (1 shl z)) continue
            val key = "$z/$x/$y"
            if (!diskFile(key).exists()) { fetchKey(z, x, y, key); n++ }
        }
        return n
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun diskFile(key: String) = File(diskDir, key.replace('/', '_') + ".webp")

    private fun loadDisk(key: String): Bitmap? {
        val f = diskFile(key)
        if (!f.exists()) return null
        // Bump recency on read too, not just on write — otherwise a tile fetched
        // once and then re-read every ride (the common case) looks "old" to
        // [maybeEvict] and gets thrown out ahead of tiles nobody's used since.
        f.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun fetch(z: Int, x: Int, y: Int, key: String): Bitmap? = fetchKey(z, x, y, key)

    private fun fetchKey(z: Int, x: Int, y: Int, key: String): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        // Rate-limit network fetches to avoid hot loops on the radio.
        val now = System.currentTimeMillis()
        val wait = MIN_FETCH_GAP_MS - (now - lastFetchAt)
        if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        lastFetchAt = System.currentTimeMillis()

        val net = internetNetwork()
        return try {
            val url = URL(URL_TEMPLATE.format(z, x, ((y % max) + max) % max))
            val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            writeDiskAtomic(key, bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Tile $key fetch failed: ${e.message}" }
            null
        }
    }

    /**
     * Write-then-rename so a killed process or dropped connection mid-write never
     * leaves a truncated PNG sitting in the cache as if it were a valid tile —
     * [loadDisk]/`BitmapFactory` would otherwise happily decode-fail on it forever.
     */
    private fun writeDiskAtomic(key: String, bytes: ByteArray) {
        val target = diskFile(key)
        val tmp = File(diskDir, "${key.replace('/', '_')}.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
            diskBytes.addAndGet(bytes.size.toLong())
            maybeEvict()
        } catch (e: Exception) {
            tmp.delete()
            DebugLog.w(TAG) { "Tile $key disk write failed: ${e.message}" }
        }
    }

    /** Kicks off an async LRU sweep once [diskBytes] crosses [MAX_DISK_CACHE_BYTES]. */
    private fun maybeEvict() {
        if (diskBytes.get() <= MAX_DISK_CACHE_BYTES) return
        if (!evicting.compareAndSet(false, true)) return // a sweep is already in flight
        scope.launch(Dispatchers.IO) {
            try {
                val files = diskDir.listFiles { f -> f.isFile && f.name.endsWith(".webp") } ?: return@launch
                var evicted = 0
                for (f in files.sortedBy { it.lastModified() }) {
                    if (diskBytes.get() <= DISK_EVICT_TARGET_BYTES) break
                    val len = f.length()
                    if (f.delete()) {
                        diskBytes.addAndGet(-len)
                        evicted++
                    }
                }
                if (evicted > 0) DebugLog.i(TAG) { "Disk cache evicted $evicted tile(s), now ~${diskBytes.get() / 1024}KB" }
            } finally {
                evicting.set(false)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun internetNetwork(): Network? =
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
}
