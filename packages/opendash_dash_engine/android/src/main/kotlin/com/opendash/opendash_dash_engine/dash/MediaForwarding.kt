package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.DashCommand
import com.opendash.opendash_dash_engine.media.MediaEvent
import com.opendash.opendash_dash_engine.media.MediaMetadataSnapshot
import com.opendash.opendash_dash_engine.media.MediaSnapshot
import com.opendash.opendash_dash_engine.media.dispatchMediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal fun mediaMetadataCommands(metadata: NowPlaying): List<DashCommand> {
    val jpeg = metadata.artJpeg?.takeIf { it.size in 1..65_535 }
    return buildList {
        add(DashCommand.NowPlaying(metadata.title, metadata.album, metadata.artist))
        add(DashCommand.AlbumArtMarker(jpeg != null))
        if (jpeg != null) add(DashCommand.AlbumArt(jpeg))
    }
}

internal fun mediaReplay(chrome: DashChrome): List<DashCommand> = buildList {
    add(DashCommand.MediaActive(chrome.mediaActive))
    chrome.nowPlaying?.let { addAll(mediaMetadataCommands(it)) }
    chrome.mediaPlaying?.let { add(DashCommand.MediaPlayback(it)) }
}

private fun MediaMetadataSnapshot.nowPlaying() = NowPlaying(title, album, artist, mediaId, artJpeg)

/**
 * Serializes media state + event enqueue against the session's initial replay capture.
 * No callback or operation in this monitor suspends; socket writes belong to DashSession.
 */
internal class MediaForwarding(
    private val chrome: MutableStateFlow<DashChrome>,
    private val currentSession: () -> DashSession?,
    private val onChanged: (DashChrome) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayDeque<() -> Unit>()
    private var draining = false
    private var nextGeneration = 0L
    private var generation = 0L
    private var accepting = false
    private var revision = -1L
    // Empty chrome can still have an older card queued (legacy title then null).
    // Only an ordered inactive, not a local title clear, invalidates that queue.
    private var needsInactive = false

    fun begin(): Long = synchronized(lock) {
        val next = ++nextGeneration
        mutate {
            generation = next
            accepting = true
            revision = -1L
        }
        next
    }

    fun acceptSnapshot(generation: Long, snapshot: MediaSnapshot) = mutate {
        if (!accepts(generation, snapshot.revision)) return@mutate
        revision = snapshot.revision
        val next = change {
            it.copy(
                nowPlaying = snapshot.metadata?.nowPlaying(),
                mediaActive = snapshot.active,
                mediaPlaying = snapshot.playing,
            )
        }
        val commands = mediaReplay(next)
        enqueue(commands, snapshot.metadata)
    }

    fun acceptEvent(generation: Long, event: MediaEvent) = mutate {
        if (!accepts(generation, event.revision)) return@mutate
        revision = event.revision
        when (event) {
            is MediaEvent.Active -> {
                change {
                    it.copy(
                        mediaActive = true,
                        nowPlaying = if (event.clearMetadata) null else it.nowPlaying,
                        mediaPlaying = if (event.clearMetadata) null else it.mediaPlaying,
                    )
                }
                enqueue(listOf(DashCommand.MediaActive(true)))
            }
            is MediaEvent.Inactive -> {
                change { it.copy(nowPlaying = null, mediaActive = false, mediaPlaying = null) }
                enqueue(listOf(DashCommand.MediaActive(false)))
            }
            is MediaEvent.Metadata -> {
                val metadata = event.metadata.nowPlaying()
                change { it.copy(nowPlaying = metadata) }
                enqueue(mediaMetadataCommands(metadata), event.metadata)
            }
            is MediaEvent.Playback -> {
                change { it.copy(mediaActive = true, mediaPlaying = event.playing) }
                // An empty update still invalidates a deferred, formerly known replay
                // indicator. It is not a false indicator and emits no 05 19 packet.
                enqueue(event.playing?.let { listOf(DashCommand.MediaPlayback(it)) } ?: emptyList())
            }
        }
    }

    fun updateNowPlaying(title: String?, album: String, artist: String) = mutate {
        // This public legacy input alone suppresses blank titles. Provider metadata may
        // legitimately contain the empty advertisement title and must not use this filter.
        val metadata = title?.takeIf { it.isNotBlank() }?.let {
            MediaMetadataSnapshot(it, album, artist)
        }
        val nowPlaying = metadata?.nowPlaying()
        change { it.copy(nowPlaying = nowPlaying) }
        if (nowPlaying != null) enqueue(mediaMetadataCommands(nowPlaying), metadata)
        else enqueue(emptyList()) // Clear pending art without fabricating an inactive event.
    }

    fun stop() = synchronized(lock) {
        val stoppedGeneration = ++nextGeneration
        mutate {
            generation = stoppedGeneration
            accepting = false
            revision = -1L
            val current = chrome.value
            if (!needsInactive && !current.mediaActive &&
                current.mediaPlaying == null && current.nowPlaying == null
            ) {
                return@mutate
            }
            // onChanged clears inputs and publishes before inactive is ordered on the writer.
            change { it.copy(nowPlaying = null, mediaActive = false, mediaPlaying = null) }
            enqueue(listOf(DashCommand.MediaActive(false)))
        }
    }

    fun startStreaming() = mutate {
        currentSession()?.startStreaming()
    }

    // Event/control calls normally execute inline. This transient drain only orders
    // nested synchronous callbacks; it is not an art queue and has no thread or timer.
    private inline fun mutate(crossinline action: () -> Unit) = synchronized(lock) {
        if (draining) {
            // StateFlow/onChanged may reenter synchronously. Complete the current
            // chrome + enqueue transaction before applying that next operation.
            pending.addLast { action() }
            return@synchronized
        }
        draining = true
        try {
            action()
            while (pending.isNotEmpty()) pending.removeFirst().invoke()
        } finally {
            pending.clear()
            draining = false
        }
    }

    private fun accepts(generation: Long, revision: Long): Boolean =
        accepting && this.generation == generation && revision > this.revision

    private inline fun change(transform: (DashChrome) -> DashChrome): DashChrome {
        chrome.update(transform)
        return chrome.value.also(onChanged)
    }

    private fun enqueue(commands: List<DashCommand>, metadata: MediaMetadataSnapshot? = null) {
        for (command in commands) {
            when (command) {
                is DashCommand.MediaActive -> needsInactive = command.active
                is DashCommand.NowPlaying, is DashCommand.MediaPlayback,
                is DashCommand.AlbumArtMarker, is DashCommand.AlbumArt -> needsInactive = true
                else -> Unit
            }
        }
        val session = currentSession() ?: return
        if (metadata == null) {
            session.sendMedia(commands)
        } else {
            // Getters, thumbnail encoding and the provider's dedup cache commit have
            // already happened. Only the actual writer enqueue has the legacy fallback.
            dispatchMediaMetadata(
                metadata,
                enqueueMetadata = { session.sendMedia(commands) },
                enqueueCardOnly = {
                    session.sendMedia(listOf(DashCommand.NowPlaying(it.title, it.album, it.artist)))
                },
            )
        }
    }
}
