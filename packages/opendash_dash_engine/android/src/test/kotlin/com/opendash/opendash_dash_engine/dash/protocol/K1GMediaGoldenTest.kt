package com.opendash.opendash_dash_engine.dash.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Static media contract, not hardware captures; firmware acceptance remains a separate gate. */
class K1GMediaGoldenTest {
    @Test
    fun `active playback and art markers keep distinct tags and boolean polarity`() {
        val cases = listOf(
            DashCommand.MediaActive(true) to "0016000200000000020100054b314720000517000155",
            DashCommand.MediaActive(false) to "0016000200000000020100054b3147200005170001aa",
            DashCommand.MediaPlayback(true) to "0016000200000000020100054b314720000519000155",
            DashCommand.MediaPlayback(false) to "0016000200000000020100054b3147200005190001aa",
            DashCommand.AlbumArtMarker(true) to "0016000200000000020100054b314720000558000155",
            DashCommand.AlbumArtMarker(false) to "0016000200000000020100054b3147200005580001aa",
        )
        for ((command, expected) in cases) {
            assertEquals(expected, K1GCodec.encode(command).toHex(), command.toString())
        }
    }

    @Test
    fun `art packets preserve first intermediate and final values`() {
        val cases = listOf(
            byteArrayOf(0x00, 0x03, 0xe9.toByte(), 0x12, 0x34) to
                "001a000200000000020100054b31472000054000050003e91234",
            byteArrayOf(0x01, 0x56, 0x78) to
                "0018000200000000020100054b3147200005400003015678",
            byteArrayOf(0xff.toByte(), 0x9a.toByte()) to
                "0017000200000000020100054b3147200005400002ff9a",
            byteArrayOf(0xff.toByte()) to
                "0016000200000000020100054b3147200005400001ff",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, K1GCodec.encode(DashCommand.AlbumArtChunk(value)).toHex())
        }
    }

    @Test
    fun `logical art cannot bypass chunking through scalar codec`() {
        assertFailsWith<IllegalArgumentException> {
            K1GCodec.encode(DashCommand.AlbumArt(byteArrayOf(0x12, 0x34)))
        }
    }

    @Test
    fun `heartbeat encodes independent volume levels without changing other TLVs`() {
        assertEquals(
            "0049000b00000000020100054b314720" +
                "0006080001050610000141060300015506040001a2060f0001aa" +
                "0601000101054c000110052d00020000051b00011a0521000132054d000132",
            K1GCodec.encode(DashCommand.Heartbeat(musicVolume = 0x10, callVolume = 0x1a)).toHex(),
        )
        assertEquals(
            "0049000b00000000020100054b314720" +
                "0006080001050610000141060300015506040001a2060f0001aa" +
                "0601000101054c00011a052d00020000051b0001100521000132054d000132",
            K1GCodec.encode(DashCommand.Heartbeat(musicVolume = 0x1a, callVolume = 0x10)).toHex(),
        )
    }
}
