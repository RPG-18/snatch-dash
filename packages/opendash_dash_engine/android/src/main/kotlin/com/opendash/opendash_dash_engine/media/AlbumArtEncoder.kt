package com.opendash.opendash_dash_engine.media

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import java.io.ByteArrayOutputStream
import java.io.OutputStream

internal object AlbumArtEncoder {
    private const val SIZE = 75
    private const val MAX_BYTES = 65535

    fun encode(bitmap: Bitmap?): ByteArray? = encode(bitmap) { image, output ->
        image.compress(Bitmap.CompressFormat.JPEG, 50, output)
    }

    // Only compression is replaceable: ownership tests still exercise real Android bitmaps.
    internal fun encode(bitmap: Bitmap?, compress: (Bitmap, OutputStream) -> Boolean): ByteArray? {
        if (bitmap == null || bitmap.isRecycled) return null
        var thumbnail: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            thumbnail = ThumbnailUtils.extractThumbnail(bitmap, SIZE, SIZE) ?: return null
            scaled = Bitmap.createScaledBitmap(thumbnail, SIZE, SIZE, true)
            val output = ArtOutput()
            if (!compress(scaled, output) || output.overflow || output.size() == 0) {
                null
            } else {
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            // Both framework calls may return their input. Never recycle borrowed art,
            // and release an aliased thumbnail/scaled bitmap only once, after compression.
            if (scaled != null && scaled !== bitmap) scaled.recycle()
            if (thumbnail != null && thumbnail !== bitmap && thumbnail !== scaled) thumbnail.recycle()
        }
    }

    private class ArtOutput : ByteArrayOutputStream(4096) {
        var overflow = false
            private set

        override fun write(value: Int) {
            if (overflow) return
            if (count == MAX_BYTES) {
                overflow = true
                return
            }
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException()
            }
            if (overflow) return
            if (length > MAX_BYTES - count) {
                // Native compression may keep writing; bound retained output regardless.
                overflow = true
                return
            }
            super.write(bytes, offset, length)
        }
    }
}
