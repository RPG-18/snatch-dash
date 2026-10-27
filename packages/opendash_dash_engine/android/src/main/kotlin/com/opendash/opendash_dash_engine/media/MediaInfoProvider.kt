package com.opendash.opendash_dash_engine.media

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class MediaInfoProvider internal constructor(
    private val platform: MediaPlatform,
    scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) : this(AndroidMediaPlatform(context), scope)

    private val lock = Any()
    private val stream = MediaEventStream(lock, scope)
    val snapshot: StateFlow<MediaSnapshot> = stream.snapshot
    val events: SharedFlow<MediaEvent> = stream.events

    private var started = false
    private var runGeneration = 0L
    private var bindingGeneration = 0L
    private var sessionsListener: MediaSessionsListener? = null
    private var controller: MediaSessionAdapter? = null
    private var controllerToken: Any? = null
    private var controllerCallback: MediaSessionCallback? = null
    private var metadataCounter = 0
    private var cachedId: String? = null
    private var cachedTitle: String? = null
    private var inactivePublished = false

    /** Register first, replay once, then deliver only events newer than that atomic snapshot. */
    suspend fun collectWithSnapshot(
        onSnapshot: suspend (MediaSnapshot) -> Unit,
        onEvent: suspend (MediaEvent) -> Unit,
    ): Nothing = stream.collectWithSnapshot(onSnapshot, onEvent)

    fun start() = synchronized(lock) {
        if (started || safely { platform.hasAccess() } != true) return@synchronized
        started = true
        val generation = ++runGeneration
        val listener = MediaSessionsListener { sessions ->
            synchronized(lock) {
                if (started && runGeneration == generation) bind(sessions?.firstOrNull())
            }
        }
        sessionsListener = listener
        try {
            platform.addSessionsListener(listener)
            val sessions = platform.activeSessions()
            if (started && runGeneration == generation) bind(sessions.firstOrNull())
        } catch (_: Exception) {
            // Registration can partially succeed; stop invalidates callbacks before cleanup.
            stop()
        }
    }

    fun stop() = synchronized(lock) {
        if (!started) return@synchronized
        started = false
        ++runGeneration
        val oldListener = sessionsListener
        sessionsListener = null
        detach()
        if (oldListener != null) safely { platform.removeSessionsListener(oldListener) }
        inactive()
    }

    fun skipNext(): Boolean = synchronized(lock) {
        controller?.let { safely { it.skipNext(); true } } ?: false
    }

    fun skipPrevious(): Boolean = synchronized(lock) {
        controller?.let { safely { it.skipPrevious(); true } } ?: false
    }

    fun playPause(): Unit = synchronized(lock) {
        safely {
            val current = controller ?: return@safely
            when (current.playback) {
                null -> Unit
                MediaPlayback.PLAYING -> current.pause()
                MediaPlayback.PAUSED, MediaPlayback.OTHER -> current.play()
            }
        }
        Unit
    }

    fun volumeUp(): Unit = adjustVolume(1)

    fun volumeDown(): Unit = adjustVolume(-1)

    private fun adjustVolume(direction: Int): Unit = synchronized(lock) {
        safely { controller?.adjustVolume(direction, 1) }
        Unit
    }

    fun mute(): Unit = synchronized(lock) {
        safely { platform.adjustMusicVolume(if (platform.isMusicMuted()) 100 else -100, 0) }
        Unit
    }

    private fun bind(next: MediaSessionAdapter?) {
        val token = if (next == null) null else try {
            next.token
        } catch (_: Exception) {
            return
        }
        if (next != null && controller != null && controllerToken == token) return
        if (next == null && controller == null) {
            inactive()
            return
        }
        detach()
        if (next == null) {
            inactive()
            return
        }
        controller = next
        controllerToken = token
        val generation = bindingGeneration
        val callback = object : MediaSessionCallback {
            override fun metadataChanged(metadata: MediaMetadataSource?) = synchronized(lock) {
                if (!isCurrent(generation)) return@synchronized
                metadataCounter++
                if (metadataCounter == 2) {
                    metadataCounter = 0
                    readMetadata(metadata, generation)
                }
            }

            override fun playbackChanged(playback: MediaPlayback?) = synchronized(lock) {
                if (isCurrent(generation)) publishPlayback(playback, generation)
            }

            override fun destroyed() = synchronized(lock) {
                if (isCurrent(generation)) {
                    detach()
                    inactive()
                }
            }
        }
        controllerCallback = callback
        try {
            next.registerCallback(callback)
        } catch (_: Exception) {
            detach()
            inactive()
            return
        }
        if (!isCurrent(generation)) return
        active(clearMetadata = true)
        if (!isCurrent(generation)) return
        try {
            val metadata = next.metadata
            if (isCurrent(generation)) readMetadata(metadata, generation)
        } catch (_: Exception) {
            // Framework getter failures are not metadata null and not legacy send failures.
        }
        if (!isCurrent(generation)) return
        val playback = safely { next.playback }
        if (isCurrent(generation) && playback?.playing != null) publishPlayback(playback, generation)
    }

    private fun isCurrent(generation: Long): Boolean =
        started && controller != null && bindingGeneration == generation

    private fun detach() {
        ++bindingGeneration
        val old = controller
        val callback = controllerCallback
        controller = null
        controllerToken = null
        controllerCallback = null
        metadataCounter = 0
        cachedId = null
        cachedTitle = null
        if (old != null && callback != null) safely { old.unregisterCallback(callback) }
    }

    private fun readMetadata(source: MediaMetadataSource?, generation: Long) {
        if (source == null) {
            clearMetadata()
            return
        }
        val title: String?
        val id: String?
        val metadata: MediaMetadataSnapshot
        try {
            title = source.title
            val artist = source.artist
            val album = source.album
            if (!isCurrent(generation)) return
            if (title == null && artist == null && album == null) {
                clearMetadata()
                return
            }
            id = source.mediaId
            if (!isCurrent(generation)) return
            if (id != null && title != null && id.equals(cachedId, ignoreCase = true) &&
                title == cachedTitle
            ) return
            val advertisement = source.advertisement
            val art = source.encodeArt()
            if (!isCurrent(generation)) return
            metadata = when {
                advertisement != null && advertisement != 0L -> MediaMetadataSnapshot(
                    title = "", album = "Advertisement", artist = "", mediaId = id, artJpeg = art,
                )
                title.isNullOrEmpty() -> MediaMetadataSnapshot(
                    title = "Now Playing", album = "", artist = "", mediaId = id, artJpeg = art,
                )
                else -> MediaMetadataSnapshot(title, album.orEmpty(), artist.orEmpty(), id, art)
            }
        } catch (_: Exception) {
            // Getters/thumbnail are outside the legacy enqueue catch. Keep the prior snapshot.
            return
        }
        // The original cache commits before dispatch; a later send failure must not undo it.
        cachedId = id
        cachedTitle = title
        val current = snapshot.value
        val revision = current.revision + 1
        stream.publish(
            MediaEvent.Metadata(revision, metadata),
            current.copy(revision = revision, metadata = metadata),
        )
    }

    private fun clearMetadata() {
        cachedId = null
        cachedTitle = null
        inactive()
    }

    private fun active(clearMetadata: Boolean = false) {
        inactivePublished = false
        val current = snapshot.value
        val revision = current.revision + 1
        stream.publish(
            MediaEvent.Active(revision, clearMetadata),
            current.copy(
                revision = revision,
                active = true,
                playing = if (clearMetadata) null else current.playing,
                metadata = if (clearMetadata) null else current.metadata,
            ),
        )
    }

    private fun publishPlayback(playback: MediaPlayback?, generation: Long) {
        active()
        if (!isCurrent(generation)) return
        val current = snapshot.value
        val revision = current.revision + 1
        stream.publish(
            MediaEvent.Playback(revision, playback),
            current.copy(revision = revision, active = true, playing = playback?.playing),
        )
    }

    private fun inactive() {
        val current = snapshot.value
        if (inactivePublished && !current.active && current.metadata == null && current.playing == null) return
        inactivePublished = true
        val revision = current.revision + 1
        stream.publish(MediaEvent.Inactive(revision), MediaSnapshot(revision = revision))
    }

    companion object {
        fun isAccessGranted(context: Context): Boolean = safely {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return@safely false
            enabled.split(':').any {
                android.content.ComponentName.unflattenFromString(it)?.packageName == context.packageName
            }
        } ?: false

        fun accessSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private inline fun <T> safely(block: () -> T): T? = try {
            block()
        } catch (_: Exception) {
            null
        }
    }
}
