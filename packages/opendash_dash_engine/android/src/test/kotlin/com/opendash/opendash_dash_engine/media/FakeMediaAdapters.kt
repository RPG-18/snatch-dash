package com.opendash.opendash_dash_engine.media

internal class FakeMetadata(
    private val rawTitle: String? = "Song",
    private val rawAlbum: String? = "Album",
    private val rawArtist: String? = "Artist",
    private val rawId: String? = "id",
    private val ad: Long? = null,
    private val art: ByteArray? = null,
    var failureAt: String? = null,
) : MediaMetadataSource {
    var artReads = 0
    var advertisementReads = 0
    override val title: String? get() = read("title", rawTitle)
    override val album: String? get() = read("album", rawAlbum)
    override val artist: String? get() = read("artist", rawArtist)
    override val mediaId: String? get() = read("id", rawId)
    override val advertisement: Long?
        get() {
            advertisementReads++
            return read("ad", ad)
        }

    override fun encodeArt(): ByteArray? {
        artReads++
        return read("art", art)
    }

    private fun <T> read(field: String, value: T): T {
        check(failureAt != field) { "framework getter $field" }
        return value
    }
}

internal class FakeMediaSession(
    override val token: Any = "session",
    var currentMetadata: MediaMetadataSource? = FakeMetadata(),
    var currentPlayback: MediaPlayback? = null,
    private val order: MutableList<String> = mutableListOf(),
) : MediaSessionAdapter {
    val callbacks = mutableListOf<MediaSessionCallback>()
    val calls = mutableListOf<String>()
    var failureAt: String? = null
    var unregisterCount = 0
    var onRegister: (() -> Unit)? = null
    var onUnregister: ((MediaSessionCallback) -> Unit)? = null
    val callback: MediaSessionCallback get() = callbacks.last()

    override val metadata: MediaMetadataSource?
        get() {
            fail("metadata")
            return currentMetadata
        }
    override val playback: MediaPlayback?
        get() {
            fail("playback")
            return currentPlayback
        }

    override fun registerCallback(callback: MediaSessionCallback) {
        order.add("register:$token")
        callbacks.add(callback)
        onRegister?.invoke()
        fail("register")
    }

    override fun unregisterCallback(callback: MediaSessionCallback) {
        order.add("unregister:$token")
        unregisterCount++
        onUnregister?.invoke(callback)
        fail("unregister")
    }

    fun metadataTwice(metadata: MediaMetadataSource?) {
        currentMetadata = metadata
        callback.metadataChanged(metadata)
        callback.metadataChanged(metadata)
    }

    fun playbackChanged(playback: MediaPlayback?) {
        currentPlayback = playback
        callback.playbackChanged(playback)
    }

    override fun play() = call("play")
    override fun pause() = call("pause")
    override fun skipNext() = call("next")
    override fun skipPrevious() = call("previous")
    override fun adjustVolume(direction: Int, flags: Int) = call("volume:$direction:$flags")

    private fun call(action: String) {
        fail(action)
        calls.add(action)
    }

    private fun fail(action: String) {
        check(failureAt != action) { "adapter $action" }
    }
}

internal class FakeMediaPlatform(
    var sessions: List<MediaSessionAdapter> = emptyList(),
    var access: Boolean = true,
) : MediaPlatform {
    val listeners = mutableListOf<MediaSessionsListener>()
    var removals = 0
    var activeReads = 0
    var muted = false
    var failureAt: String? = null
    val volumeCalls = mutableListOf<Pair<Int, Int>>()
    val listener: MediaSessionsListener get() = listeners.last()

    override fun hasAccess(): Boolean {
        fail("access")
        return access
    }

    override fun activeSessions(): List<MediaSessionAdapter> {
        activeReads++
        fail("sessions")
        return sessions
    }

    override fun addSessionsListener(listener: MediaSessionsListener) {
        listeners.add(listener)
        fail("add")
    }

    override fun removeSessionsListener(listener: MediaSessionsListener) {
        removals++
        fail("remove")
    }

    fun changeSessions(next: List<MediaSessionAdapter>?) {
        sessions = next.orEmpty()
        listener.changed(next)
    }

    override fun isMusicMuted(): Boolean {
        fail("muteGetter")
        return muted
    }

    override fun adjustMusicVolume(direction: Int, flags: Int) {
        fail("muteSetter")
        volumeCalls.add(direction to flags)
        muted = direction == -100
    }

    private fun fail(action: String) {
        check(failureAt != action) { "platform $action" }
    }
}
