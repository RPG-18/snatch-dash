package com.opendash.opendash_dash_engine.media

import android.app.PendingIntent

enum class MediaPlayback {
    PLAYING,
    PAUSED,
    OTHER;

    val playing: Boolean?
        get() = when (this) {
            PLAYING -> true
            PAUSED -> false
            OTHER -> null
        }
}

/** JPEG bytes are owned by this snapshot and must not be mutated by consumers. */
data class MediaMetadataSnapshot(
    val title: String,
    val album: String,
    val artist: String,
    val mediaId: String? = null,
    val artJpeg: ByteArray? = null,
)

data class MediaSnapshot(
    val revision: Long = 0,
    val active: Boolean = false,
    val playing: Boolean? = null,
    val metadata: MediaMetadataSnapshot? = null,
)

sealed interface MediaEvent {
    val revision: Long

    data class Active(
        override val revision: Long,
        val clearMetadata: Boolean,
    ) : MediaEvent
    data class Inactive(override val revision: Long) : MediaEvent
    data class Metadata(
        override val revision: Long,
        val metadata: MediaMetadataSnapshot,
    ) : MediaEvent
    data class Playback(
        override val revision: Long,
        val playback: MediaPlayback?,
    ) : MediaEvent {
        val playing: Boolean? get() = playback?.playing
    }
}

data class IncomingCall(
    val caller: String,
    val incoming: Boolean = true,
    val answerIntent: PendingIntent? = null,
    val declineIntent: PendingIntent? = null,
)
