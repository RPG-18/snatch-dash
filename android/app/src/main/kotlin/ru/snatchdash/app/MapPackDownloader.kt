package ru.snatchdash.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.util.Log
import java.io.File
import java.io.IOException
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

    /**
     * `<externalFilesDir>/maps`, created if missing.
     *
     * Throws rather than improvises when the volume is unavailable:
     * `getExternalFilesDir(null)` returns null on ejected or unmounted storage,
     * and `File(null, "maps")` is not an error — it is the *relative* path
     * `maps`, resolved against the process's working directory. Everything then
     * appears to work on a phantom directory the DownloadManager cannot write
     * to, which is the failure the spec asks to be made loud.
     */
    fun mapsDir(): File {
        val base = context.getExternalFilesDir(null)
            ?: throw IOException("external files dir unavailable — storage not mounted?")
        val dir = File(base, MAPS_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("could not create ${dir.absolutePath}")
        }
        return dir
    }

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
        // Not left to whoever called hasRoomFor() first: DownloadManager will not
        // create the destination directory, and "the Dart side happens to have
        // touched it" is not a precondition worth depending on.
        mapsDir()
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
        // commit(), not apply(): this is the one write whose loss equals the loss
        // of the download. The transfer is already enqueued in the system service
        // and will finish on its own; if the process dies before an async apply()
        // reaches disk, `reconcile` — which walks pending codes — never learns the
        // pack exists, and a few hundred megabytes land in a `.part` nobody claims.
        pending.edit()
            .putLong(keyId(code), id)
            .putString(keySha(code), sha256)
            .putLong(keySize(code), sizeBytes)
            .putString(keyGeneratedAt(code), generatedAt)
            .commit()
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
        // Before the loop: it only ever deletes files no pending code claims, so it
        // cannot touch anything the loop below is about to install.
        runCatching { sweepOrphanParts() }
            .onFailure { Log.w(TAG, "orphan sweep failed: ${it.message}") }
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
                // FAILED, without trying to work out how far install() got. The
                // "a pack file exists, so the move must have happened" shortcut
                // belongs in install(), where it can afford to verify the hash; a
                // guess here would adopt the OLD build of a pack being updated.
                // Whatever is on disk stays there — the next reconcile, or the
                // orphan sweep, deals with it.
                //
                // Every step wrapped: partFile()/packFile() go through mapsDir(),
                // which throws when external storage is gone, and a second throw
                // escaping this handler would discard the whole batch — precisely
                // what the per-pack catch exists to prevent.
                runCatching { partFile(code).delete() }
                runCatching {
                    val strandedId = pending.getLong(keyId(code), -1L)
                    if (strandedId >= 0) dm.remove(strandedId)
                }
                runCatching { forget(code) }
                listOf(result(code, Outcome.FAILED, e.message))
            }
        }
        return results
    }

    /**
     * Deletes `.part` files no pending entry claims.
     *
     * Every race and crash in this file leaves its residue under the same name:
     * a partial pack of up to a few hundred megabytes that [reconcile] will never
     * look at, because it walks *pending codes*, not the directory. Nothing in
     * the interface shows them either — the screen lists the registry — so they
     * accumulate silently on a device whose owner is watching free space go down.
     *
     * Called once per [reconcile], which is cheap: one directory listing against
     * a prefs map, no hashing, no I/O per pack.
     */
    private fun sweepOrphanParts() {
        val claimed = pendingCodes().toSet()
        val parts = runCatching { mapsDir().listFiles { f -> f.isFile && f.name.endsWith(PART_SUFFIX) } }
            .getOrNull() ?: return
        for (part in parts) {
            val code = part.name.removeSuffix(PART_SUFFIX)
            if (code in claimed) continue
            val size = part.length()
            if (part.delete()) {
                Log.i(TAG, "swept orphan part for $code ($size B)")
            } else {
                Log.w(TAG, "could not sweep orphan part ${part.absolutePath}")
            }
        }
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
            // only the first one is atomic. Reporting FAILED there put a "couldn't
            // download the map" in front of the rider on a launch where the pack is
            // present and about to be drawn.
            //
            // But "a file with the right name exists" is NOT that proof. On an
            // update the old build sits under exactly that name, and adopting it
            // would file the new manifest's sha256 against the previous build's
            // bytes — a registry row that describes a pack nobody has, and an
            // update that is never offered again because the hashes now "match".
            // So hash it. Expensive (seconds), and only on this path.
            if (expected != null && installed.exists() &&
                sha256Of(installed).equals(expected, ignoreCase = true)
            ) {
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
