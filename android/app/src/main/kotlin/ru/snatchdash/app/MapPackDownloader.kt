package ru.snatchdash.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.util.Log
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Downloads offline map packs with the system [DownloadManager] and installs them
 * atomically. See spec/remote_map_server.md and spec/drawing_from_local_tiles.md.
 *
 * Why the system downloader: a pack runs 3 MB to 356 MB over mobile data, and
 * DownloadManager gives resume, retries, network-change handling and survival of
 * process death for free. Its cost is the destination — it runs under its own uid
 * and cannot write into the app's internal storage, which is why packs live in
 * `getExternalFilesDir(null)/maps/`.
 *
 * **Resume is safe.** On resume DownloadManager sends `If-Match` with the stored
 * ETag; a corpus rebuilt mid-download makes that fail with 412, which surfaces as
 * `ERROR_CANNOT_RESUME` rather than silently splicing two builds together. That
 * error is therefore a *conflict*, not a failure — re-read the manifest and start
 * over (see [Outcome.CONFLICT]).
 *
 * **Verification happens while the app is alive**, not in a broadcast receiver:
 * hashing 356 MB can outlast the ~10 s a receiver gets. A pack is "downloaded" the
 * moment DownloadManager finishes, but "installed" only after [reconcile] has
 * checked its sha256 and renamed it — so a download that completes with the app
 * closed is finished on the next launch. Until then the file is named `.part` and
 * the render engine, which enumerates `*.pmtiles`, does not see it.
 */
class MapPackDownloader(private val context: Context) {

    /** Terminal result of one pack, as reported back to Dart. */
    enum class Outcome { INSTALLED, CHECKSUM_MISMATCH, CONFLICT, FAILED, CANCELLED }

    private val dm: DownloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Pending downloads: pack code -> enqueued id + what we expect it to be. */
    private val pending = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mapsDir(): File = File(context.getExternalFilesDir(null), MAPS_DIR).apply { mkdirs() }

    private fun partFile(code: String) = File(mapsDir(), "$code$PART_SUFFIX")
    private fun packFile(code: String) = File(mapsDir(), "$code$PACK_SUFFIX")

    /**
     * Free space the system could hand us — [StorageManager.getAllocatableBytes]
     * counts what it can reclaim by evicting other apps' caches, which
     * `File.getUsableSpace()` does not. Checked before starting, because failing
     * at 90% of 356 MB is the worst possible moment.
     */
    fun hasRoomFor(bytes: Long): Boolean = try {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val uuid = sm.getUuidForPath(mapsDir())
        sm.getAllocatableBytes(uuid) >= bytes + HEADROOM_BYTES
    } catch (e: Exception) {
        // An unreadable volume shouldn't block the download outright — let the
        // downloader itself fail with a real reason instead of guessing here.
        Log.w(TAG, "allocatable-bytes check failed: ${e.message}")
        true
    }

    /**
     * Enqueues [code] for download. Returns the DownloadManager id, or throws if
     * the downloader is disabled on this device (`enqueue` throws then, and that
     * is a real user-visible state, not a crash).
     */
    fun start(code: String, url: String, sha256: String, sizeBytes: Long, generatedAt: String, title: String): Long {
        // Any earlier attempt for this code is stale the moment a new one starts.
        cancel(code)

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription(context.getString(R.string.app_name))
            .setDestinationInExternalFilesDir(context, null, "$MAPS_DIR/$code$PART_SUFFIX")
            .setAllowedOverMetered(true) // the interface asks the rider; this layer obeys
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val id = dm.enqueue(request)
        pending.edit()
            .putLong(keyId(code), id)
            .putString(keySha(code), sha256)
            .putLong(keySize(code), sizeBytes)
            .putString(keyGeneratedAt(code), generatedAt)
            .apply()
        Log.i(TAG, "enqueued $code id=$id size=$sizeBytes")
        return id
    }

    /** Stops a download and drops its partial file. Unknown codes are a no-op. */
    fun cancel(code: String) {
        val id = pending.getLong(keyId(code), -1L)
        if (id >= 0) dm.remove(id)
        partFile(code).delete()
        forget(code)
    }

    /** Deletes an installed pack. Returns false when there was nothing to delete. */
    fun delete(code: String): Boolean = packFile(code).delete()

    /** Live progress for every pending download, for the screen to poll. */
    fun progress(): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        for (code in pendingCodes()) {
            val id = pending.getLong(keyId(code), -1L)
            if (id < 0) continue
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return@use
                val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                out += mapOf(
                    "code" to code,
                    "bytesSoFar" to soFar,
                    // -1 until the server's Content-Length lands; fall back to the
                    // manifest size so the bar has something to grow against.
                    "totalBytes" to if (total > 0) total else pending.getLong(keySize(code), 0L),
                )
            }
        }
        return out
    }

    /**
     * Finishes everything DownloadManager has completed since we last looked:
     * verifies sha256 and renames into place. **Blocking and slow** — hashing a
     * large pack takes seconds — so callers must keep it off the main thread.
     */
    fun reconcile(): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for (code in pendingCodes()) {
            // One pack's failure must not take the batch with it. Without this,
            // an IOException out of Files.move or sha256Of threw away `results`
            // whole — including codes already renamed and forgotten, whose
            // INSTALLED never reached Dart. The file is then on disk and drawn by
            // the engine while the registry and the navigation gate deny it
            // exists, and the culprit's pending entry throws again on every poll
            // tick, 700 ms apart, forever.
            results += try {
                reconcileOne(code)
            } catch (e: Exception) {
                Log.w(TAG, "reconcile failed for $code: ${e.message}", e)
                // Where the throw landed decides what actually happened. Anything
                // after a successful Files.move leaves the pack on disk under its
                // final name, which the engine enumerates and draws — reporting
                // FAILED there would tell Dart to keep no registry row for a map
                // the dash is already showing, and the rider gets an error over a
                // download that worked.
                val installed = packFile(code)
                val verified = pending.getString(keySha(code), null)
                val outcome = if (installed.exists() && verified != null) {
                    Log.i(TAG, "reconcile for $code threw after the move — the pack is installed")
                    mapOf(
                        "code" to code,
                        "outcome" to Outcome.INSTALLED.name,
                        "sha256" to verified,
                        "generatedAt" to (pending.getString(keyGeneratedAt(code), "") ?: ""),
                        "sizeBytes" to installed.length(),
                    )
                } else {
                    // Give up on this pack completely, not just on its bookkeeping.
                    // Dropping only the pending entry would strand the `.part` file
                    // — up to a few hundred megabytes that nothing ever looks at
                    // again, since `reconcile` walks pending codes — and leave a
                    // finished DownloadManager row behind it.
                    runCatching { partFile(code).delete() }
                    result(code, Outcome.FAILED, e.message)
                }
                val strandedId = pending.getLong(keyId(code), -1L)
                if (strandedId >= 0) runCatching { dm.remove(strandedId) }
                forget(code)
                listOf(outcome)
            }
        }
        return results
    }

    /** One pending code; see [reconcile] for why this is separable. */
    private fun reconcileOne(code: String): List<Map<String, Any?>> {
        val id = pending.getLong(keyId(code), -1L)
        if (id < 0) {
            forget(code)
            return emptyList()
        }
        val status = queryStatus(id)
        if (status == null) {
            // The row is gone — the rider cleared it from the system downloads
            // UI, or it was never really enqueued. Nothing left to finish.
            partFile(code).delete()
            forget(code)
            return listOf(result(code, Outcome.CANCELLED, null))
        }

        return when (status.first) {
            DownloadManager.STATUS_SUCCESSFUL -> listOf(install(code, id))
            DownloadManager.STATUS_FAILED -> {
                val reason = status.second
                val outcome =
                    if (reason == DownloadManager.ERROR_CANNOT_RESUME) Outcome.CONFLICT else Outcome.FAILED
                Log.w(TAG, "download failed $code reason=$reason -> $outcome")
                partFile(code).delete()
                dm.remove(id)
                forget(code)
                listOf(result(code, outcome, "reason=$reason"))
            }
            else -> emptyList() // still pending/running/paused — nothing to finish yet
        }
    }

    private fun install(code: String, id: Long): Map<String, Any?> {
        val part = partFile(code)
        val expected = pending.getString(keySha(code), null)
        if (!part.exists() || expected == null) {
            val installed = packFile(code)
            // The `.part` can be missing because the move already happened and the
            // process was killed before forget() — the install is two writes, and
            // only the first one is atomic. Reporting FAILED here put a "couldn't
            // download the map" in front of the rider on a launch where the pack is
            // present and about to be drawn.
            if (expected != null && installed.exists()) {
                val size = installed.length()
                val generatedAt = pending.getString(keyGeneratedAt(code), "") ?: ""
                dm.remove(id)
                forget(code)
                Log.i(TAG, "install of $code had already completed ($size B) — finishing its bookkeeping")
                return mapOf(
                    "code" to code,
                    "outcome" to Outcome.INSTALLED.name,
                    "sha256" to expected,
                    "generatedAt" to generatedAt,
                    "sizeBytes" to size,
                )
            }
            dm.remove(id)
            forget(code)
            return result(code, Outcome.FAILED, "part file missing")
        }

        val actual = sha256Of(part)
        if (!actual.equals(expected, ignoreCase = true)) {
            // Not necessarily corruption: the corpus may have been rebuilt while we
            // downloaded. Dart re-reads the manifest and decides (see the optimistic
            // locking in spec/remote_map_server.md).
            Log.w(TAG, "sha256 mismatch for $code: expected $expected got $actual")
            // Read before forget() wipes it. Dart needs the manifest generation
            // this download was started against to tell "the corpus was rebuilt
            // under us" (retry) from "the object in the bucket is corrupt" (do not
            // retry — see spec/remote_map_server.md, «Порядок скачивания», п. 5).
            val startedAgainst = pending.getString(keyGeneratedAt(code), "") ?: ""
            part.delete()
            dm.remove(id)
            forget(code)
            return mapOf(
                "code" to code,
                "outcome" to Outcome.CHECKSUM_MISMATCH.name,
                "detail" to actual,
                "generatedAt" to startedAgainst,
            )
        }

        // Belt and braces against the interleaving in MainActivity.onMapsWorker:
        // everything now runs on one thread, so `start()` cannot have replaced this
        // download while we hashed — but if that ever stops being true, renaming
        // someone else's half-written file into the final name is the one mistake
        // here that reaches the rider as a corrupt map. Cheap to rule out.
        val idNow = pending.getLong(keyId(code), -1L)
        if (idNow != id) {
            Log.w(TAG, "pending id for $code changed under install ($id -> $idNow) — not installing")
            return result(code, Outcome.CONFLICT, "download restarted")
        }

        // Within one directory, so it is a rename and not a copy of hundreds of
        // megabytes. `renameTo` would return a bare false; this reports why, and
        // promises atomicity that `renameTo` does not.
        try {
            Files.move(
                part.toPath(),
                packFile(code).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // Not every filesystem an external volume can be formatted with offers
            // one; the pack is verified either way, and a non-atomic rename inside
            // one directory still beats refusing to install.
            Log.w(TAG, "atomic move unavailable for $code, falling back: ${e.message}")
            Files.move(part.toPath(), packFile(code).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val size = packFile(code).length()
        val generatedAt = pending.getString(keyGeneratedAt(code), "") ?: ""
        // Only now that the file is in place under its final name: remove() would
        // have deleted it had we called it first.
        dm.remove(id)
        forget(code)
        Log.i(TAG, "installed $code ($size B)")
        return mapOf(
            "code" to code,
            "outcome" to Outcome.INSTALLED.name,
            "sha256" to expected,
            "generatedAt" to generatedAt,
            "sizeBytes" to size,
        )
    }

    /** Packs present on disk under their final name — what the engine will see. */
    fun installedFiles(): List<Map<String, Any?>> =
        mapsDir().listFiles { f -> f.isFile && f.name.endsWith(PACK_SUFFIX) }
            ?.map { mapOf("code" to it.name.removeSuffix(PACK_SUFFIX), "sizeBytes" to it.length()) }
            ?: emptyList()

    private fun queryStatus(id: Long): Pair<Int, Int>? =
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            status to reason
        }

    private fun result(code: String, outcome: Outcome, detail: String?): Map<String, Any?> =
        mapOf("code" to code, "outcome" to outcome.name, "detail" to detail)

    private fun pendingCodes(): List<String> =
        pending.all.keys.filter { it.endsWith(SUFFIX_ID) }.map { it.removeSuffix(SUFFIX_ID) }

    private fun forget(code: String) = pending.edit()
        .remove(keyId(code)).remove(keySha(code)).remove(keySize(code)).remove(keyGeneratedAt(code))
        .apply()

    private fun keyId(code: String) = "$code$SUFFIX_ID"
    private fun keySha(code: String) = "$code.sha"
    private fun keySize(code: String) = "$code.size"
    private fun keyGeneratedAt(code: String) = "$code.generated"

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "MapPackDownloader"
        const val PREFS = "map_pack_downloads"
        const val MAPS_DIR = "maps"
        const val PACK_SUFFIX = ".pmtiles"
        const val PART_SUFFIX = ".pmtiles.part"
        const val SUFFIX_ID = ".id"
        const val BUFFER_BYTES = 1 shl 16

        /** Never fill the volume to the brim — leave the system room to breathe. */
        const val HEADROOM_BYTES = 64L * 1024 * 1024
    }
}
