package com.opendash.opendash_dash_engine.media

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MediaMetadataDispatchTest {
    private fun card(metadata: MediaMetadataSnapshot): String =
        "card:${metadata.album}|${metadata.title}|${metadata.artist}"

    @Test
    fun `successful dispatch only enqueues normal metadata effects`() {
        val effects = mutableListOf<String>()
        val metadata = MediaMetadataSnapshot("Song", "Album", "Artist", artJpeg = byteArrayOf(1, 2))
        dispatchMediaMetadata(
            metadata,
            enqueueMetadata = {
                effects.add(card(it))
                effects.add("marker:present")
                effects.add("art:${it.artJpeg!!.joinToString()}")
            },
            enqueueCardOnly = { effects.add("unexpected fallback") },
        )
        assertEquals(listOf("card:Album|Song|Artist", "marker:present", "art:1, 2"), effects)
    }

    @Test
    fun `IllegalStateException appends exact card-only fallback without undoing effects or cache`() = runTest {
        val session = FakeMediaSession()
        val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
        provider.start()
        val before = provider.snapshot.value
        val effects = mutableListOf<String>()
        dispatchMediaMetadata(
            before.metadata!!,
            enqueueMetadata = {
                effects.add(card(it))
                effects.add("marker:present")
                effects.add("art:first")
                throw IllegalStateException("failed after partially enqueuing art")
            },
            enqueueCardOnly = { effects.add(card(it)) },
        )
        assertEquals(
            listOf("card:Album|Song|Artist", "marker:present", "art:first", "card:NowPlaying||"),
            effects,
        )
        assertSame(before, provider.snapshot.value)
        session.metadataTwice(FakeMetadata(rawId = "ID", rawAlbum = "Late album"))
        assertSame(before, provider.snapshot.value, "dispatch failure must not reset committed raw-id/title cache")
    }

    @Test
    fun `safe encoder no-art result uses normal metadata path rather than legacy fallback`() {
        val effects = mutableListOf<String>()
        dispatchMediaMetadata(
            MediaMetadataSnapshot("Song", "Album", "Artist", artJpeg = null),
            enqueueMetadata = {
                effects.add(card(it))
                effects.add(if (it.artJpeg == null) "marker:absent" else "marker:present")
            },
            enqueueCardOnly = { effects.add("unexpected fallback") },
        )
        assertEquals(listOf("card:Album|Song|Artist", "marker:absent"), effects)
    }

    @Test
    fun `other dispatch exceptions escape and fallback failures are not retried`() {
        val metadata = MediaMetadataSnapshot("Song", "Album", "Artist")
        val effects = mutableListOf<String>()
        assertFailsWith<SecurityException> {
            dispatchMediaMetadata(
                metadata,
                enqueueMetadata = { throw SecurityException("not the legacy exception") },
                enqueueCardOnly = { effects.add(card(it)) },
            )
        }
        assertEquals(emptyList(), effects)
        assertFailsWith<IllegalStateException> {
            dispatchMediaMetadata(
                metadata,
                enqueueMetadata = { throw IllegalStateException("normal enqueue") },
                enqueueCardOnly = {
                    effects.add(card(it))
                    throw IllegalStateException("fallback enqueue")
                },
            )
        }
        assertEquals(listOf("card:NowPlaying||"), effects)
    }
}
