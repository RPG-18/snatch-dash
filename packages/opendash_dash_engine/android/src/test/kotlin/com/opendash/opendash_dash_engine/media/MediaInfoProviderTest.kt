package com.opendash.opendash_dash_engine.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaInfoProviderTest {
    private class Rig(
        val provider: MediaInfoProvider,
        val platform: FakeMediaPlatform,
        val events: MutableList<MediaEvent>,
    )

    private fun TestScope.rig(platform: FakeMediaPlatform): Rig {
        val provider = MediaInfoProvider(
            platform,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        val events = mutableListOf<MediaEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.events.collect { events.add(it) }
        }
        return Rig(provider, platform, events)
    }

    @Test
    fun `no access and access getter errors do nothing and can be retried`() = runTest {
        val r = rig(FakeMediaPlatform(access = false))
        r.provider.start()
        r.provider.stop()
        assertEquals(MediaSnapshot(), r.provider.snapshot.value)
        assertTrue(r.events.isEmpty())
        assertTrue(r.platform.listeners.isEmpty())
        r.platform.access = true
        r.platform.failureAt = "access"
        r.provider.start()
        assertTrue(r.platform.listeners.isEmpty())
        r.platform.failureAt = null
        r.provider.start()
        assertEquals(1, r.platform.listeners.size)
        assertIs<MediaEvent.Inactive>(r.events.single())
    }

    @Test
    fun `empty cold start and repeated start stop publish only one initial inactive`() = runTest {
        val r = rig(FakeMediaPlatform())
        r.provider.start()
        r.provider.start()
        r.platform.changeSessions(emptyList())
        r.platform.changeSessions(null)
        r.provider.stop()
        r.provider.stop()
        assertIs<MediaEvent.Inactive>(r.events.single())
        assertEquals(1, r.platform.listeners.size)
        assertEquals(1, r.platform.removals)
    }

    @Test
    fun `first session initial metadata bypasses counter and known playback is replayed`() = runTest {
        for (playback in listOf(MediaPlayback.PLAYING, MediaPlayback.PAUSED)) {
            val first = FakeMediaSession(currentPlayback = playback)
            val second = FakeMediaSession(token = "ignored")
            val r = rig(FakeMediaPlatform(listOf(first, second)))
            r.provider.start()
            assertEquals("Song", r.provider.snapshot.value.metadata?.title)
            assertEquals(playback.playing, r.provider.snapshot.value.playing)
            assertTrue(r.provider.snapshot.value.active)
            assertEquals(listOf("Active", "Metadata", "Active", "Playback"), r.events.map { it.javaClass.simpleName })
            assertTrue(assertIs<MediaEvent.Active>(r.events[0]).clearMetadata)
            assertFalse(assertIs<MediaEvent.Active>(r.events[2]).clearMetadata)
            assertEquals(1, first.callbacks.size)
            assertTrue(second.callbacks.isEmpty())
            first.callback.metadataChanged(FakeMetadata(rawTitle = "New"))
            assertEquals("Song", r.provider.snapshot.value.metadata?.title)
            first.callback.metadataChanged(FakeMetadata(rawTitle = "New"))
            assertEquals("New", r.provider.snapshot.value.metadata?.title)
        }
    }

    @Test
    fun `same token and repeated start are idempotent and rebind unregisters first`() = runTest {
        val order = mutableListOf<String>()
        val a = FakeMediaSession(token = "a", order = order)
        val b = FakeMediaSession(token = "b", currentMetadata = FakeMetadata(rawTitle = "B"), order = order)
        val r = rig(FakeMediaPlatform(listOf(a)))
        r.provider.start()
        val before = r.events.toList()
        r.provider.start()
        val alias = FakeMediaSession(token = "a", currentMetadata = FakeMetadata(rawTitle = "Ignored"))
        r.platform.changeSessions(listOf(alias))
        assertEquals(before, r.events)
        assertTrue(alias.callbacks.isEmpty())
        a.callback.metadataChanged(FakeMetadata(rawTitle = "A half callback"))
        val stale = a.callback
        r.platform.changeSessions(listOf(b))
        assertEquals(listOf("register:a", "unregister:a", "register:b"), order)
        assertEquals("B", r.provider.snapshot.value.metadata?.title)
        b.callback.metadataChanged(FakeMetadata(rawTitle = "B first"))
        assertEquals("B", r.provider.snapshot.value.metadata?.title)
        b.callback.metadataChanged(FakeMetadata(rawTitle = "B second"))
        assertEquals("B second", r.provider.snapshot.value.metadata?.title)
        val current = r.provider.snapshot.value
        stale.metadataChanged(FakeMetadata(rawTitle = "Stale"))
        stale.metadataChanged(FakeMetadata(rawTitle = "Stale"))
        stale.playbackChanged(MediaPlayback.PLAYING)
        stale.destroyed()
        assertSame(current, r.provider.snapshot.value)
    }

    @Test
    fun `empty list and destroy clear controller state and allow same token rebind`() = runTest {
        for (destroy in listOf(false, true)) {
            val session = FakeMediaSession(currentPlayback = MediaPlayback.PLAYING)
            val r = rig(FakeMediaPlatform(listOf(session)))
            r.provider.start()
            val stale = session.callback
            if (destroy) stale.destroyed() else r.platform.changeSessions(emptyList())
            assertEquals(1, session.unregisterCount)
            assertFalse(r.provider.snapshot.value.active)
            assertNull(r.provider.snapshot.value.metadata)
            assertNull(r.provider.snapshot.value.playing)
            assertFalse(r.provider.skipNext())
            stale.playbackChanged(MediaPlayback.PLAYING)
            assertFalse(r.provider.snapshot.value.active)
            r.platform.changeSessions(listOf(session))
            assertEquals(2, session.callbacks.size)
            assertEquals("Song", r.provider.snapshot.value.metadata?.title)
        }
    }

    @Test
    fun `stop invalidates session and global callbacks even after next start`() = runTest {
        val session = FakeMediaSession(currentPlayback = MediaPlayback.PLAYING)
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        val oldSession = session.callback
        val oldListener = r.platform.listener
        session.callback.metadataChanged(FakeMetadata(rawTitle = "Half"))
        r.provider.stop()
        val stopped = r.provider.snapshot.value
        r.provider.stop()
        oldSession.playbackChanged(MediaPlayback.PLAYING)
        oldListener.changed(listOf(session))
        assertSame(stopped, r.provider.snapshot.value)
        r.provider.start()
        val restarted = r.provider.snapshot.value
        oldSession.metadataChanged(FakeMetadata(rawTitle = "Stale"))
        oldSession.metadataChanged(FakeMetadata(rawTitle = "Stale"))
        oldSession.destroyed()
        oldListener.changed(emptyList())
        assertSame(restarted, r.provider.snapshot.value)
        session.callback.metadataChanged(FakeMetadata(rawTitle = "First after restart"))
        assertSame(restarted, r.provider.snapshot.value)
        session.callback.metadataChanged(FakeMetadata(rawTitle = "Second after restart"))
        assertEquals("Second after restart", r.provider.snapshot.value.metadata?.title)
        assertEquals(2, r.events.filterIsInstance<MediaEvent.Metadata>().count { it.metadata.title == "Song" })
    }

    @Test
    fun `initial null and each second callback null clear state and reset dedup`() = runTest {
        val session = FakeMediaSession(currentMetadata = null)
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        assertEquals(listOf("Active", "Inactive"), r.events.map { it.javaClass.simpleName })
        assertFalse(r.provider.snapshot.value.active)
        session.metadataTwice(FakeMetadata())
        assertEquals("Song", r.provider.snapshot.value.metadata?.title)
        // Metadata has no extra Active event; only bind/playback activate the stream.
        assertFalse(r.provider.snapshot.value.active)
        session.callback.metadataChanged(null)
        assertEquals("Song", r.provider.snapshot.value.metadata?.title)
        session.callback.metadataChanged(null)
        assertNull(r.provider.snapshot.value.metadata)
        session.metadataTwice(FakeMetadata())
        assertEquals("Song", r.provider.snapshot.value.metadata?.title)
    }

    @Test
    fun `null counts toward metadata parity just like nonnull callback`() = runTest {
        val session = FakeMediaSession()
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        session.callback.metadataChanged(null)
        session.callback.metadataChanged(FakeMetadata(rawTitle = "Second"))
        assertEquals("Second", r.provider.snapshot.value.metadata?.title)
        session.callback.metadataChanged(FakeMetadata(rawTitle = "Skipped"))
        session.callback.metadataChanged(null)
        assertNull(r.provider.snapshot.value.metadata)
    }

    @Test
    fun `all null wins over advertisement before art and advertisement getters`() = runTest {
        val raw = FakeMetadata(null, null, null, ad = 1, failureAt = "ad")
        val r = rig(FakeMediaPlatform(listOf(FakeMediaSession(currentMetadata = raw))))
        r.provider.start()
        assertFalse(r.provider.snapshot.value.active)
        assertNull(r.provider.snapshot.value.metadata)
        assertEquals(0, raw.advertisementReads)
        assertEquals(0, raw.artReads)
        assertIs<MediaEvent.Inactive>(r.events.last())
    }

    @Test
    fun `empty missing whitespace and advertisement titles keep exact tuples`() = runTest {
        data class Case(val raw: FakeMetadata, val title: String, val album: String, val artist: String)
        val cases = listOf(
            Case(FakeMetadata("", "", ""), "Now Playing", "", ""),
            Case(FakeMetadata(null, "Album", "Artist"), "Now Playing", "", ""),
            Case(FakeMetadata(" \t", " A ", " R "), " \t", " A ", " R "),
            Case(FakeMetadata("Song", null, null), "Song", "", ""),
            Case(FakeMetadata(ad = 0), "Song", "Album", "Artist"),
            Case(FakeMetadata(ad = 1), "", "Advertisement", ""),
            Case(FakeMetadata(ad = -9), "", "Advertisement", ""),
        )
        for (case in cases) {
            val r = rig(FakeMediaPlatform(listOf(FakeMediaSession(currentMetadata = case.raw))))
            r.provider.start()
            val actual = r.provider.snapshot.value.metadata!!
            assertEquals(listOf(case.title, case.album, case.artist), listOf(actual.title, actual.album, actual.artist))
            assertTrue(r.provider.snapshot.value.active)
        }
    }

    @Test
    fun `raw id insensitive and title sensitive dedup occurs before art and advertisement`() = runTest {
        val session = FakeMediaSession()
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        val before = r.provider.snapshot.value
        val late = FakeMetadata("Song", "Changed album", "Changed artist", "ID", 1, byteArrayOf(1))
        session.metadataTwice(late)
        assertSame(before, r.provider.snapshot.value)
        assertEquals(0, late.artReads)
        assertEquals(0, late.advertisementReads)
        val titleCase = FakeMetadata(rawTitle = "song", rawId = "ID", art = byteArrayOf(2))
        session.metadataTwice(titleCase)
        assertEquals("song", r.provider.snapshot.value.metadata?.title)
        assertContentEquals(byteArrayOf(2), r.provider.snapshot.value.metadata?.artJpeg)
        assertEquals(1, titleCase.artReads)
        assertEquals(2, r.events.filterIsInstance<MediaEvent.Metadata>().size)
    }

    @Test
    fun `null id and null title do not dedup but empty raw id and title do`() = runTest {
        for ((id, title, dedup) in listOf(
            Triple(null, "Song", false),
            Triple("", "Song", true),
            Triple("id", null, false),
            Triple("id", "", true),
        )) {
            val raw = FakeMetadata(rawTitle = title, rawId = id)
            val session = FakeMediaSession(currentMetadata = raw)
            val r = rig(FakeMediaPlatform(listOf(session)))
            r.provider.start()
            session.metadataTwice(raw)
            assertEquals(if (dedup) 1 else 2, raw.artReads, "id=$id title=$title")
        }
    }

    @Test
    fun `known and unknown playback callbacks are active and clear old playing`() = runTest {
        val session = FakeMediaSession(currentPlayback = MediaPlayback.PLAYING)
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        for (playback in listOf(MediaPlayback.OTHER, MediaPlayback.PAUSED, null, MediaPlayback.PLAYING)) {
            val count = r.events.size
            session.playbackChanged(playback)
            assertEquals(playback?.playing, r.provider.snapshot.value.playing)
            assertTrue(r.provider.snapshot.value.active)
            assertFalse(assertIs<MediaEvent.Active>(r.events[count]).clearMetadata)
            assertEquals(playback, assertIs<MediaEvent.Playback>(r.events[count + 1]).playback)
            assertEquals(count + 2, r.events.size)
        }
        r.provider.stop()
        assertFalse(r.provider.snapshot.value.active)
        assertNull(r.provider.snapshot.value.playing)
    }

    @Test
    fun `metadata getter failures preserve prior state and never synthesize send fallback`() = runTest {
        val session = FakeMediaSession()
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        for (field in listOf("title", "album", "artist", "id", "ad", "art")) {
            val before = r.provider.snapshot.value
            val raw = FakeMetadata(rawTitle = "New $field", failureAt = field)
            session.metadataTwice(raw)
            assertSame(before, r.provider.snapshot.value, field)
            raw.failureAt = null
            session.metadataTwice(raw)
            assertEquals("New $field", r.provider.snapshot.value.metadata?.title)
        }
        assertTrue(r.events.filterIsInstance<MediaEvent.Metadata>().none { it.metadata.album == "NowPlaying" })
    }

    @Test
    fun `safe no art result preserves real metadata and initial getter failure is not null metadata`() = runTest {
        val session = FakeMediaSession(currentPlayback = MediaPlayback.PAUSED)
        session.failureAt = "metadata"
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        assertTrue(r.provider.snapshot.value.active)
        assertEquals(false, r.provider.snapshot.value.playing)
        assertNull(r.provider.snapshot.value.metadata)
        assertTrue(r.events.none { it is MediaEvent.Inactive })
        session.failureAt = null
        session.metadataTwice(FakeMetadata(art = null))
        assertEquals("Song", r.provider.snapshot.value.metadata?.title)
        assertNull(r.provider.snapshot.value.metadata?.artJpeg)
    }

    @Test
    fun `registration and cleanup failures cannot retain callbacks or controller`() = runTest {
        for (failure in listOf("add", "sessions")) {
            val platform = FakeMediaPlatform(listOf(FakeMediaSession()))
            platform.failureAt = failure
            val r = rig(platform)
            r.provider.start()
            assertFalse(r.provider.snapshot.value.active)
            assertEquals(1, platform.removals)
            platform.failureAt = null
            r.provider.start()
            assertTrue(r.provider.snapshot.value.active)
        }
        val session = FakeMediaSession()
        session.failureAt = "register"
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        assertFalse(r.provider.skipNext())
        assertEquals(1, session.unregisterCount)
        session.failureAt = null
        r.platform.changeSessions(listOf(session))
        session.failureAt = "unregister"
        r.platform.failureAt = "remove"
        val stale = session.callback
        r.provider.stop()
        stale.playbackChanged(MediaPlayback.PLAYING)
        assertFalse(r.provider.snapshot.value.active)
        assertFalse(r.provider.skipPrevious())
    }

    @Test
    fun `controls use controller playback including OTHER and mute has no controller gate`() = runTest {
        val r = rig(FakeMediaPlatform())
        r.provider.playPause()
        r.provider.volumeUp()
        r.provider.volumeDown()
        assertFalse(r.provider.skipNext())
        assertFalse(r.provider.skipPrevious())
        r.provider.mute()
        r.provider.mute()
        assertEquals(listOf(-100 to 0, 100 to 0), r.platform.volumeCalls)
        val session = FakeMediaSession()
        r.platform.sessions = listOf(session)
        r.provider.start()
        r.provider.playPause()
        for (playback in listOf(MediaPlayback.PLAYING, MediaPlayback.PAUSED, MediaPlayback.OTHER)) {
            session.currentPlayback = playback
            r.provider.playPause()
            assertTrue(r.provider.skipNext())
            assertTrue(r.provider.skipPrevious())
        }
        r.provider.volumeUp()
        r.provider.volumeDown()
        assertEquals(
            listOf("pause", "next", "previous", "play", "next", "previous", "play", "next", "previous", "volume:1:1", "volume:-1:1"),
            session.calls,
        )
    }

    @Test
    fun `every control adapter exception is a quiet no op`() = runTest {
        val session = FakeMediaSession(currentPlayback = MediaPlayback.PLAYING)
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        for (failure in listOf("playback", "pause")) {
            session.failureAt = failure
            r.provider.playPause()
        }
        session.currentPlayback = MediaPlayback.OTHER
        session.failureAt = "play"
        r.provider.playPause()
        session.failureAt = "next"
        assertFalse(r.provider.skipNext())
        session.failureAt = "previous"
        assertFalse(r.provider.skipPrevious())
        session.failureAt = "volume:1:1"
        r.provider.volumeUp()
        session.failureAt = "volume:-1:1"
        r.provider.volumeDown()
        for (failure in listOf("muteGetter", "muteSetter")) {
            r.platform.failureAt = failure
            r.provider.mute()
        }
        assertTrue(session.calls.isEmpty())
        assertTrue(r.platform.volumeCalls.isEmpty())
    }

    @Test
    fun `stale callbacks invoked during unregister and next register cannot change new binding`() = runTest {
        val a = FakeMediaSession(token = "a")
        val b = FakeMediaSession(token = "b", currentMetadata = FakeMetadata(rawTitle = "B"))
        val r = rig(FakeMediaPlatform(listOf(a)))
        r.provider.start()
        val old = a.callback
        a.onUnregister = {
            it.playbackChanged(MediaPlayback.PLAYING)
            it.metadataChanged(FakeMetadata(rawTitle = "Unregister stale"))
            it.metadataChanged(FakeMetadata(rawTitle = "Unregister stale"))
            it.destroyed()
        }
        b.onRegister = {
            old.playbackChanged(MediaPlayback.PAUSED)
            old.destroyed()
        }
        r.platform.changeSessions(listOf(b))
        assertEquals("B", r.provider.snapshot.value.metadata?.title)
        assertNull(r.provider.snapshot.value.playing)
        assertEquals(0, b.unregisterCount)
        assertEquals(listOf("Song", "B"), r.events.filterIsInstance<MediaEvent.Metadata>().map { it.metadata.title })
    }

    @Test
    fun `reentrant stop during framework getter cannot restore metadata or playback`() = runTest {
        val session = FakeMediaSession()
        val r = rig(FakeMediaPlatform(listOf(session)))
        r.provider.start()
        val raw = FakeMetadata(rawTitle = "Stale getter")
        val stopping = object : MediaMetadataSource by raw {
            override val title: String?
                get() {
                    r.provider.stop()
                    return raw.title
                }
        }
        session.metadataTwice(stopping)
        assertFalse(r.provider.snapshot.value.active)
        assertNull(r.provider.snapshot.value.metadata)
        assertNull(r.provider.snapshot.value.playing)
        assertIs<MediaEvent.Inactive>(r.events.last())
    }
}
