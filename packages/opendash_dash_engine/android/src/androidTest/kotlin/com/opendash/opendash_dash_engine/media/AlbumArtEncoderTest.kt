package com.opendash.opendash_dash_engine.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumArtEncoderTest {
    @Test
    fun jpegIs75By75AndBorrowedInputRemainsUsable() {
        for ((width, height) in listOf(75 to 75, 300 to 300, 360 to 120, 120 to 360, 20 to 30)) {
            val input = bitmap(width, height)
            try {
                // Repeat to catch accidental recycling when a framework result aliases input.
                repeat(2) {
                    val bytes = requireNotNull(AlbumArtEncoder.encode(input))
                    assertTrue(bytes.size in 1..65535)
                    assertEquals(0xFF, bytes[0].toInt() and 0xFF)
                    assertEquals(0xD8, bytes[1].toInt() and 0xFF)
                    val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try {
                        assertEquals(75, decoded.width)
                        assertEquals(75, decoded.height)
                    } finally {
                        decoded.recycle()
                    }
                    assertInputUsable(input)
                }
            } finally {
                input.recycle()
            }
        }
    }

    @Test
    fun missingOrRecycledInputHasNoArt() {
        assertNull(AlbumArtEncoder.encode(null))
        val input = bitmap(75, 75)
        input.recycle()
        assertNull(AlbumArtEncoder.encode(input))
        assertTrue(input.isRecycled)
    }

    @Test
    fun failedCompressionDiscardsPartialBytesWithoutRecyclingInput() {
        assertRejected { _, output ->
            output.write(byteArrayOf(1, 2, 3))
            false
        }
    }

    @Test
    fun emptyCompressionHasNoArt() {
        assertRejected { _, _ -> true }
    }

    @Test
    fun compressorExceptionHasNoArtAndReleasesOwnedBitmap() {
        assertRejected { _, output ->
            output.write(1)
            throw IllegalStateException("compression unavailable")
        }
    }

    @Test
    fun oversizedSingleWriteHasNoArt() {
        assertRejected { _, output ->
            output.write(ByteArray(65536))
            true
        }
    }

    @Test
    fun overflowAfterFullPayloadCannotBecomeValidAgain() {
        assertRejected { _, output ->
            output.write(ByteArray(65535))
            output.write(1)
            output.write(byteArrayOf(2, 3))
            true
        }
    }

    @Test
    fun overflowAcrossChunksHasNoArt() {
        assertRejected { _, output ->
            val chunk = ByteArray(4096)
            repeat(16) { output.write(chunk) }
            true
        }
    }

    @Test
    fun largestPermittedCompressionResultIsPreserved() {
        val input = bitmap(360, 120)
        val expected = ByteArray(65535) { it.toByte() }
        var compressed: Bitmap? = null
        try {
            val result = AlbumArtEncoder.encode(input) { image, output ->
                compressed = image
                assertFalse(image.isRecycled)
                output.write(expected, 0, 32768)
                output.write(expected, 32768, expected.size - 32768)
                true
            }
            assertArrayEquals(expected, result)
            assertInputUsable(input)
            assertOwnedBitmapReleased(input, requireNotNull(compressed))
        } finally {
            input.recycle()
        }
    }

    private fun assertRejected(compress: (Bitmap, OutputStream) -> Boolean) {
        for ((width, height) in listOf(75 to 75, 360 to 120)) {
            val input = bitmap(width, height)
            var compressed: Bitmap? = null
            try {
                assertNull(AlbumArtEncoder.encode(input) { image, output ->
                    compressed = image
                    assertFalse(image.isRecycled)
                    compress(image, output)
                })
                assertInputUsable(input)
                assertOwnedBitmapReleased(input, requireNotNull(compressed))
            } finally {
                input.recycle()
            }
        }
    }

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }

    private fun assertInputUsable(input: Bitmap) {
        assertFalse(input.isRecycled)
        assertEquals(Color.GREEN, input.getPixel(0, 0))
        input.setPixel(0, 0, Color.BLUE)
        assertEquals(Color.BLUE, input.getPixel(0, 0))
        input.setPixel(0, 0, Color.GREEN)
    }

    private fun assertOwnedBitmapReleased(input: Bitmap, compressed: Bitmap) {
        if (compressed !== input) assertTrue(compressed.isRecycled)
    }
}
