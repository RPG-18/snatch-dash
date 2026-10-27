package com.opendash.opendash_dash_engine.dash

import android.content.Context
import android.media.AudioManager
import com.opendash.opendash_dash_engine.dash.protocol.DashCommand
import com.opendash.opendash_dash_engine.dash.protocol.VolumeLevel

/** Controller-side AudioManager seam; the codec only receives already encoded volume bytes. */
internal class LiveHeartbeat(private val readVolume: (Int) -> Int?) {
    constructor(context: Context) : this({ stream ->
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio == null) null else VolumeLevel.encode(
            current = audio.getStreamVolume(stream),
            min = audio.getStreamMinVolume(stream),
            max = audio.getStreamMaxVolume(stream),
        )
    })

    fun currentHeartbeat(): DashCommand.Heartbeat = DashCommand.Heartbeat(
        tempC = 25,
        musicVolume = level(AudioManager.STREAM_MUSIC, 0x13),
        callVolume = level(AudioManager.STREAM_VOICE_CALL, 0x19),
    )

    private fun level(stream: Int, fallback: Int): Int = try {
        readVolume(stream) ?: fallback
    } catch (_: Exception) {
        // Failure of one stream must neither omit its TLV nor replace the other live level.
        fallback
    }
}
