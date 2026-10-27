package com.opendash.opendash_dash_engine.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

internal fun interface MediaSessionsListener {
    fun changed(sessions: List<MediaSessionAdapter>?)
}

internal interface MediaPlatform {
    fun hasAccess(): Boolean
    fun activeSessions(): List<MediaSessionAdapter>
    fun addSessionsListener(listener: MediaSessionsListener)
    fun removeSessionsListener(listener: MediaSessionsListener)
    fun isMusicMuted(): Boolean
    fun adjustMusicVolume(direction: Int, flags: Int)
}

internal interface MediaSessionCallback {
    fun metadataChanged(metadata: MediaMetadataSource?)
    fun playbackChanged(playback: MediaPlayback?)
    fun destroyed()
}

internal interface MediaSessionAdapter {
    val token: Any
    val metadata: MediaMetadataSource?
    val playback: MediaPlayback?
    fun registerCallback(callback: MediaSessionCallback)
    fun unregisterCallback(callback: MediaSessionCallback)
    fun play()
    fun pause()
    fun skipNext()
    fun skipPrevious()
    fun adjustVolume(direction: Int, flags: Int)
}

/** Lazy getters keep dedup before bitmap access, and framework failures outside dispatch. */
internal interface MediaMetadataSource {
    val title: String?
    val album: String?
    val artist: String?
    val mediaId: String?
    val advertisement: Long?
    fun encodeArt(): ByteArray?
}

internal class AndroidMediaPlatform(context: Context) : MediaPlatform {
    private val context = context.applicationContext
    private val manager by lazy { this.context.getSystemService(MediaSessionManager::class.java) }
    private val audio by lazy { this.context.getSystemService(AudioManager::class.java) }
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val component = ComponentName(this.context, OpenDashNotificationListener::class.java)
    private val listeners = HashMap<MediaSessionsListener, MediaSessionManager.OnActiveSessionsChangedListener>()

    override fun hasAccess(): Boolean = MediaInfoProvider.isAccessGranted(context)

    override fun activeSessions(): List<MediaSessionAdapter> =
        manager?.getActiveSessions(component)?.map { AndroidMediaSession(it, handler) }.orEmpty()

    override fun addSessionsListener(listener: MediaSessionsListener) {
        val callback = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            listener.changed(sessions?.map { AndroidMediaSession(it, handler) })
        }
        listeners[listener] = callback
        manager?.addOnActiveSessionsChangedListener(callback, component, handler)
    }

    override fun removeSessionsListener(listener: MediaSessionsListener) {
        val callback = listeners.remove(listener) ?: return
        manager?.removeOnActiveSessionsChangedListener(callback)
    }

    override fun isMusicMuted(): Boolean = audio?.isStreamMute(AudioManager.STREAM_MUSIC) ?: false

    override fun adjustMusicVolume(direction: Int, flags: Int) {
        audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, flags)
    }
}

private class AndroidMediaSession(
    private val controller: MediaController,
    private val handler: Handler,
) : MediaSessionAdapter {
    override val token: Any get() = controller.sessionToken
    override val metadata: MediaMetadataSource? get() = controller.metadata?.let(::AndroidMediaMetadata)
    override val playback: MediaPlayback? get() = controller.playbackState.toMediaPlayback()
    private val callbacks = HashMap<MediaSessionCallback, MediaController.Callback>()

    override fun registerCallback(callback: MediaSessionCallback) {
        val frameworkCallback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) =
                callback.metadataChanged(metadata?.let(::AndroidMediaMetadata))

            override fun onPlaybackStateChanged(state: PlaybackState?) =
                callback.playbackChanged(state.toMediaPlayback())

            override fun onSessionDestroyed() = callback.destroyed()
        }
        callbacks[callback] = frameworkCallback
        controller.registerCallback(frameworkCallback, handler)
    }

    override fun unregisterCallback(callback: MediaSessionCallback) {
        val frameworkCallback = callbacks.remove(callback) ?: return
        controller.unregisterCallback(frameworkCallback)
    }

    override fun play() = controller.transportControls.play()
    override fun pause() = controller.transportControls.pause()
    override fun skipNext() = controller.transportControls.skipToNext()
    override fun skipPrevious() = controller.transportControls.skipToPrevious()
    override fun adjustVolume(direction: Int, flags: Int) = controller.adjustVolume(direction, flags)
}

private fun PlaybackState?.toMediaPlayback(): MediaPlayback? {
    if (this == null) return null
    return when (state) {
        PlaybackState.STATE_PLAYING -> MediaPlayback.PLAYING
        PlaybackState.STATE_PAUSED -> MediaPlayback.PAUSED
        else -> MediaPlayback.OTHER
    }
}

private class AndroidMediaMetadata(private val metadata: MediaMetadata) : MediaMetadataSource {
    override val title: String? get() = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
    override val album: String? get() = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
    override val artist: String? get() = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
    override val mediaId: String? get() = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
    override val advertisement: Long get() = metadata.getLong("android.media.metadata.ADVERTISEMENT")

    override fun encodeArt(): ByteArray? {
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return AlbumArtEncoder.encode(bitmap)
    }
}
