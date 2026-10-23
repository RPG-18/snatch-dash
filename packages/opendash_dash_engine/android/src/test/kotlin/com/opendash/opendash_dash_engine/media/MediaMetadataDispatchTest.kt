package com.opendash.opendash_dash_engine.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaMetadataDispatchTest {
    @Test
    fun `other dispatch exceptions escape and fallback failures are not retried`() {
        val metadata = MediaMetadataSnapshot("Song", "Album", "Artist")
        var fallbackAttempts = 0
        assertFailsWith<SecurityException> {
            dispatchMediaMetadata(
                metadata,
                enqueueMetadata = { throw SecurityException("not the legacy exception") },
                enqueueCardOnly = { fallbackAttempts++ },
            )
        }
        assertEquals(0, fallbackAttempts)
        assertFailsWith<IllegalStateException> {
            dispatchMediaMetadata(
                metadata,
                enqueueMetadata = { throw IllegalStateException("normal enqueue") },
                enqueueCardOnly = {
                    fallbackAttempts++
                    throw IllegalStateException("fallback enqueue")
                },
            )
        }
        assertEquals(1, fallbackAttempts)
    }
}
