package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.DashCommand
import com.opendash.opendash_dash_engine.dash.protocol.toHex
import com.opendash.opendash_dash_engine.media.FakeMediaPlatform
import com.opendash.opendash_dash_engine.media.FakeMediaSession
import com.opendash.opendash_dash_engine.media.FakeMetadata
import com.opendash.opendash_dash_engine.media.MediaEvent
import com.opendash.opendash_dash_engine.media.MediaInfoProvider
import com.opendash.opendash_dash_engine.media.MediaMetadataSnapshot
import com.opendash.opendash_dash_engine.media.MediaPlayback
import com.opendash.opendash_dash_engine.media.MediaSnapshot
import com.opendash.opendash_dash_engine.util.DebugLog
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaForwardingTest {
    @Test
    fun `stop invalidates queued legacy metadata even after title was cleared`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, MediaSnapshot())
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            r.wire.clearSent()

            r.owner.updateNowPlaying("Queued", "", "")
            r.owner.updateNowPlaying(null, "", "")
            r.owner.stop()
            r.owner.stop()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(0x17), media(r.wire).map { it.sub })
            assertEquals(listOf("aa"), values(r.wire, 0x17))
            assertNull(r.chrome.value.nowPlaying)
            assertNull(r.publishedTitles.last())
        }
    }

    @Test
    fun `pre-stream updates become one current replay and later metadata has no duplicate playback`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, snapshot("Before", playing = true))
            val art = byteArrayOf(0x31)
            r.owner.acceptEvent(r.generation, MediaEvent.Metadata(2, metadata("Current", art)))
            assertEquals("Current", r.chrome.value.nowPlaying?.title)
            assertEquals("id-Current", r.chrome.value.nowPlaying?.mediaId)
            assertTrue(r.chrome.value.nowPlaying?.artJpeg === art)
            reachReady(r)
            assertTrue(media(r.wire).isEmpty(), "READY does not send event media")

            r.owner.startStreaming()
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
            assertEquals(listOf(0x17, 0x0D, 0x58, 0x40, 0x40, 0x19), media(r.wire).map { it.sub })
            assertEquals(listOf("Current"), titles(r.wire))
            assertEquals(listOf("55"), values(r.wire, 0x19))
            assertEquals(listOf("00000131", "ff"), values(r.wire, 0x40))

            r.wire.clearSent()
            r.owner.acceptEvent(r.generation, MediaEvent.Metadata(3, metadata("After")))
            runCurrent()
            assertEquals(listOf(0x0D, 0x58), media(r.wire).map { it.sub })
            assertEquals(listOf("After"), titles(r.wire))
            assertEquals(listOf("aa"), values(r.wire, 0x58))
            assertTrue(values(r.wire, 0x19).isEmpty())
        }
    }

    @Test
    fun `update racing STREAMING capture and genuine later metadata are delivered once each`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, snapshot("Before"))
            reachReady(r)
            StreamingPause().use { pause ->
                val starter = FutureTask { r.owner.startStreaming() }
                val startThread = Thread(starter, "media-start-capture").apply { isDaemon = true; start() }
                val attempted = CountDownLatch(1)
                val update = FutureTask {
                    attempted.countDown()
                    r.owner.acceptEvent(r.generation, MediaEvent.Metadata(2, metadata("During")))
                }
                val updateThread = Thread(update, "media-during-capture").apply { isDaemon = true }
                try {
                    pause.awaitPaused()
                    updateThread.start()
                    assertTrue(attempted.await(5, TimeUnit.SECONDS))
                    assertFalse(update.isDone, "the owner monitor spans capture and event enqueue")
                    assertEquals("Before", r.chrome.value.nowPlaying?.title)
                    pause.resume()
                    starter.get(5, TimeUnit.SECONDS)
                    update.get(5, TimeUnit.SECONDS)
                    r.owner.acceptEvent(r.generation, MediaEvent.Metadata(3, metadata("After")))
                    runCurrent()
                    assertEquals(listOf("Before", "During", "After"), titles(r.wire))
                    assertEquals(listOf("55"), values(r.wire, 0x17))
                    assertEquals(listOf("aa", "aa", "aa"), values(r.wire, 0x58))
                } finally {
                    pause.resume()
                    startThread.join(5_000)
                    updateThread.join(5_000)
                }
            }
        }
    }

    @Test
    fun `reentrant chrome subscriber preserves accepted metadata A then B order`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, snapshot("Initial"))
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            r.wire.clearSent()
            val subscriber = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                r.chrome.filter { it.nowPlaying?.title == "A" }.take(1).collect {
                    r.owner.acceptEvent(r.generation, MediaEvent.Metadata(3, metadata("B", byteArrayOf(0x42))))
                }
            }
            r.owner.acceptEvent(r.generation, MediaEvent.Metadata(2, metadata("A", byteArrayOf(0x41))))
            assertTrue(subscriber.isCompleted)
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
            assertEquals(listOf("A", "B"), titles(r.wire))
            assertEquals(listOf("A", "B"), r.publishedTitles.takeLast(2))
            assertEquals(listOf("00000142", "ff"), values(r.wire, 0x40))
            assertEquals("B", r.chrome.value.nowPlaying?.title)
        }
    }

    @Test
    fun `reentrant stop during chrome or published state cannot enqueue stale card after inactive`() = runTest {
        for (seam in listOf("chrome", "published")) {
            withRig { r ->
                r.owner.acceptSnapshot(r.generation, snapshot("Initial"))
                reachReady(r)
                r.owner.startStreaming()
                runCurrent()
                r.wire.clearSent()
                val stopAndLateCallback = {
                    r.owner.stop()
                    r.owner.acceptEvent(r.generation, MediaEvent.Metadata(3, metadata("Late")))
                }
                if (seam == "chrome") {
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        r.chrome.filter { it.nowPlaying?.title == "Stop me" }.take(1).collect {
                            stopAndLateCallback()
                        }
                    }
                } else {
                    r.onPublished = { if (it.nowPlaying?.title == "Stop me") stopAndLateCallback() }
                }
                r.owner.acceptEvent(r.generation, MediaEvent.Metadata(2, metadata("Stop me", ByteArray(65_535) { 0x41 })))
                runCurrent()
                advanceTimeBy(1_500)
                runCurrent()
                assertEquals(listOf(0x17), media(r.wire).map { it.sub }, seam)
                assertEquals(listOf("aa"), values(r.wire, 0x17), seam)
                assertFalse(r.chrome.value.mediaActive)
                assertNull(r.chrome.value.nowPlaying)
                assertNull(r.chrome.value.mediaPlaying)
                assertNull(r.publishedTitles.last())
            }
        }
    }

    @Test
    fun `reentrant STREAMING subscriber enqueues B after captured A rather than before it`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, snapshot("A"))
            reachReady(r)
            val subscriber = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                checkNotNull(r.session).state.filter { it == DashState.STREAMING }.take(1).collect {
                    r.owner.acceptEvent(r.generation, MediaEvent.Metadata(2, metadata("B")))
                }
            }
            r.owner.startStreaming()
            assertTrue(subscriber.isCompleted, "the subscriber reentered synchronously")
            runCurrent()
            assertEquals(listOf("A", "B"), titles(r.wire))
            assertEquals(listOf("55"), values(r.wire, 0x17))
        }
    }

    @Test
    fun `tick cannot publish uncommitted B while A art is running between chrome change and enqueue`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, snapshot("Initial"))
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            advanceTimeBy(900)
            runCurrent()
            r.owner.acceptEvent(r.generation, MediaEvent.Metadata(2, metadata("A", ByteArray(65_535) { 0x41 })))
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            val changed = CountDownLatch(1)
            val release = CountDownLatch(1)
            r.onPublished = {
                if (it.nowPlaying?.title == "B") {
                    changed.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "test must release metadata enqueue" }
                }
            }
            val update = FutureTask {
                r.owner.acceptEvent(r.generation, MediaEvent.Metadata(3, metadata("B", byteArrayOf(0x42))))
            }
            val thread = Thread(update, "media-chrome-before-enqueue").apply { isDaemon = true; start() }
            try {
                assertTrue(changed.await(5, TimeUnit.SECONDS))
                assertEquals("B", r.chrome.value.nowPlaying?.title)
                advanceTimeBy(100)
                runCurrent()
                assertFalse("B" in titles(r.wire), "tick must use committed A until B invalidates its art")
                assertTrue(values(r.wire, 0x40).size in 2..65, "old multichunk art is still in flight")
                release.countDown()
                update.get(5, TimeUnit.SECONDS)
                runCurrent()
                advanceTimeBy(20)
                runCurrent()
                val packets = media(r.wire)
                val bIndex = packets.indexOfFirst { it.sub == 0x0D && title(it) == "B" }
                assertTrue(bIndex >= 0)
                assertEquals(listOf("B"), titles(r.wire).filter { it == "B" })
                assertEquals(listOf("00000142", "ff"), packets.drop(bIndex).filter { it.sub == 0x40 }.map { it.value.toHex() })
            } finally {
                release.countDown()
                thread.join(5_000)
                r.onPublished = null
            }
        }
    }

    @Test
    fun `ordered provider events reduce their own revision rather than a future snapshot`() = runTest {
        withRig { r ->
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            val android = FakeMediaSession(currentMetadata = FakeMetadata(rawTitle = "First"))
            val provider = MediaInfoProvider(FakeMediaPlatform(listOf(android)), backgroundScope)
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                provider.collectWithSnapshot(
                    { r.owner.acceptSnapshot(r.generation, it) },
                    { r.owner.acceptEvent(r.generation, it) },
                )
            }
            runCurrent()
            r.wire.clearSent()
            provider.start()
            android.metadataTwice(FakeMetadata(rawTitle = "Second", rawId = "second"))
            android.metadataTwice(FakeMetadata(rawTitle = "Third", rawId = "third"))
            assertEquals("Third", provider.snapshot.value.metadata?.title)
            runCurrent()
            assertEquals(listOf("First", "Second", "Third"), titles(r.wire))
            assertEquals("Third", r.chrome.value.nowPlaying?.title)
            assertEquals(listOf("First", "Second", "Third"), r.publishedTitles.filterNotNull())
            collector.cancel()
            provider.stop()
        }
    }

    @Test
    fun `late provider snapshot covers queued older revisions but genuine later metadata still sends once`() = runTest {
        withRig { r ->
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            r.wire.clearSent()
            val android = FakeMediaSession(currentMetadata = FakeMetadata(rawTitle = "Older"))
            val provider = MediaInfoProvider(FakeMediaPlatform(listOf(android)), backgroundScope)
            provider.start()
            android.metadataTwice(FakeMetadata(rawTitle = "Current", rawId = "current"))
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                provider.collectWithSnapshot(
                    { r.owner.acceptSnapshot(r.generation, it) },
                    { r.owner.acceptEvent(r.generation, it) },
                )
            }
            android.metadataTwice(FakeMetadata(rawTitle = "Later", rawId = "later"))
            runCurrent()
            assertEquals(listOf("Current", "Later"), titles(r.wire))
            assertEquals(listOf("Current", "Later"), r.publishedTitles.filterNotNull())
            collector.cancel()
            provider.stop()
        }
    }

    @Test
    fun `dedup suppressed callback leaves current multichunk transfer running`() = runTest {
        withRig { r ->
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            val android = FakeMediaSession(currentMetadata = FakeMetadata(art = ByteArray(2_001) { 0x41 }))
            val provider = MediaInfoProvider(FakeMediaPlatform(listOf(android)), backgroundScope)
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                provider.collectWithSnapshot(
                    { r.owner.acceptSnapshot(r.generation, it) },
                    { r.owner.acceptEvent(r.generation, it) },
                )
            }
            runCurrent()
            r.wire.clearSent()
            provider.start()
            runCurrent()
            assertEquals(1, values(r.wire, 0x40).size)
            val suppressed = FakeMetadata(rawAlbum = "Changed album", rawId = "ID", art = byteArrayOf(0x42))
            android.metadataTwice(suppressed)
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            assertEquals(0, suppressed.artReads)
            assertEquals(listOf("Song"), titles(r.wire))
            val chunks = media(r.wire).filter { it.sub == 0x40 }.map { it.value }
            assertEquals(listOf(1_003, 1_001, 2), chunks.map { it.size })
            assertEquals(listOf("0007d1", "01", "ff"), chunks.mapIndexed { index, bytes ->
                bytes.copyOfRange(0, if (index == 0) 3 else 1).toHex()
            })
            assertTrue(chunks.all { it.last() == 0x41.toByte() })
            collector.cancel()
            provider.stop()
        }
    }

    @Test
    fun `rebind with failing metadata getter clears old title playback and in-flight art without inactive`() = runTest {
        for (failure in listOf("metadata", "title")) {
            withRig { r ->
                reachReady(r)
                r.owner.startStreaming()
                runCurrent()
                val android = FakeMediaSession(
                    token = "old",
                    currentMetadata = FakeMetadata(art = ByteArray(65_535) { 0x41 }),
                    currentPlayback = MediaPlayback.PLAYING,
                )
                val platform = FakeMediaPlatform(listOf(android))
                val provider = MediaInfoProvider(platform, backgroundScope)
                val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    provider.collectWithSnapshot(
                        { r.owner.acceptSnapshot(r.generation, it) },
                        { r.owner.acceptEvent(r.generation, it) },
                    )
                }
                runCurrent()
                provider.start()
                runCurrent()
                assertEquals(1, values(r.wire, 0x40).size, "old transfer has begun")
                r.wire.clearSent()
                val replacement = FakeMediaSession(
                    token = "new",
                    currentMetadata = FakeMetadata(failureAt = if (failure == "title") "title" else null),
                ).apply { if (failure == "metadata") failureAt = "metadata" }
                platform.changeSessions(listOf(replacement))
                runCurrent()
                assertTrue(r.chrome.value.mediaActive)
                assertNull(r.chrome.value.nowPlaying)
                assertNull(r.chrome.value.mediaPlaying)
                assertNull(r.publishedTitles.last())
                advanceTimeBy(1_500)
                runCurrent()
                assertEquals(listOf(0x17), media(r.wire).map { it.sub }, failure)
                assertEquals(listOf("55"), values(r.wire, 0x17), "reset is not an inactive transition")
                collector.cancel()
                provider.stop()
            }
        }
    }

    @Test
    fun `unknown playback clears old indicator including replay tail and reconnect never restores it`() = runTest {
        for ((known, unknown) in listOf(
            true to MediaPlayback.OTHER,
            false to null,
        )) {
            for (art in listOf<ByteArray?>(null, ByteArray(65_535) { 0x44 })) {
                withRig { r ->
                    r.owner.acceptSnapshot(r.generation, snapshot("Song", playing = known, art = art))
                    reachReady(r)
                    r.owner.startStreaming()
                    runCurrent()
                    if (art == null) assertEquals(listOf(if (known) "55" else "aa"), values(r.wire, 0x19))
                    else assertTrue(values(r.wire, 0x19).isEmpty(), "known replay awaits art")
                    r.wire.clearSent()
                    r.owner.acceptEvent(r.generation, MediaEvent.Active(2, clearMetadata = false))
                    r.owner.acceptEvent(r.generation, MediaEvent.Playback(3, unknown))
                    runCurrent()
                    advanceTimeBy(700)
                    runCurrent()
                    assertTrue(r.chrome.value.mediaActive)
                    assertNull(r.chrome.value.mediaPlaying)
                    assertTrue(values(r.wire, 0x19).isEmpty(), "unknown has no wire indicator")

                    r.closeTransport()
                    r.openTransport()
                    reachReady(r)
                    r.owner.startStreaming()
                    runCurrent()
                    advanceTimeBy(700)
                    runCurrent()
                    assertEquals(listOf("Song"), titles(r.wire))
                    assertTrue(values(r.wire, 0x19).isEmpty(), "no older known indicator on reconnect")
                }
            }
        }
    }

    @Test
    fun `metadata replacement preserves one deferred current playback indicator after replacement art`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, snapshot("A", playing = true, art = ByteArray(65_535) { 0x41 }))
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            r.owner.acceptEvent(r.generation, MediaEvent.Metadata(2, metadata("B", byteArrayOf(0x42))))
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            val packets = media(r.wire)
            assertEquals(listOf("A", "B"), titles(r.wire))
            assertEquals(listOf("55"), values(r.wire, 0x19))
            val bIndex = packets.indexOfFirst { it.sub == 0x0D && title(it) == "B" }
            assertEquals(listOf("00000142", "ff"), packets.drop(bIndex).filter { it.sub == 0x40 }.map { it.value.toHex() })
            assertEquals(0x19, packets.last().sub)
        }
    }

    @Test
    fun `live stop publishes clear state before explicit inactive and rejects late callbacks and repeat`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, snapshot("Old", playing = true, art = ByteArray(65_535) { 0x41 }))
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            assertEquals(1, values(r.wire, 0x40).size)
            r.wire.clearSent()
            r.owner.acceptEvent(r.generation, MediaEvent.Metadata(2, metadata("Queued")))
            r.owner.stop()
            assertNull(r.chrome.value.nowPlaying)
            assertFalse(r.chrome.value.mediaActive)
            assertNull(r.chrome.value.mediaPlaying)
            assertNull(r.publishedTitles.last())
            assertTrue(values(r.wire, 0x17).isEmpty(), "local clear was published before writer delivery")
            r.owner.acceptEvent(r.generation, MediaEvent.Metadata(99, metadata("Late")))
            r.owner.acceptEvent(r.generation, MediaEvent.Active(100, clearMetadata = false))
            r.owner.stop()
            runCurrent()
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(listOf(0x17), media(r.wire).map { it.sub })
            assertEquals(listOf("aa"), values(r.wire, 0x17))
            assertNull(r.chrome.value.nowPlaying)

            val nextGeneration = r.owner.begin()
            r.owner.acceptSnapshot(nextGeneration, snapshot("Restarted"))
            r.owner.acceptEvent(r.generation, MediaEvent.Metadata(101, metadata("Late after restart")))
            runCurrent()
            assertEquals(listOf("Restarted"), titles(r.wire))
            assertEquals("Restarted", r.chrome.value.nowPlaying?.title)
        }
    }

    @Test
    fun `stop after transport teardown clears state and has no closed-session tail on next connection`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, snapshot("Old", playing = false, art = ByteArray(65_535) { 0x41 }))
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            val closedWire = r.wire
            r.closeTransport()
            val countAtClose = closedWire.sent.size
            r.owner.stop()
            r.owner.stop()
            r.owner.acceptEvent(r.generation, MediaEvent.Metadata(10, metadata("Late")))
            assertEquals(countAtClose, closedWire.sent.size, "no required final send on a dead transport")
            assertNull(r.chrome.value.nowPlaying)
            assertFalse(r.chrome.value.mediaActive)
            assertNull(r.chrome.value.mediaPlaying)
            r.openTransport()
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            advanceTimeBy(700)
            runCurrent()
            assertEquals(listOf(0x17), media(r.wire).map { it.sub })
            assertEquals(listOf("aa"), values(r.wire, 0x17))
        }
    }

    @Test
    fun `metadata accepted after inactive replays on reconnect without inventing Active`() = runTest {
        withRig { r ->
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            val android = FakeMediaSession(currentMetadata = null)
            val provider = MediaInfoProvider(FakeMediaPlatform(listOf(android)), backgroundScope)
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                provider.collectWithSnapshot(
                    { r.owner.acceptSnapshot(r.generation, it) },
                    { r.owner.acceptEvent(r.generation, it) },
                )
            }
            provider.start()
            runCurrent()
            assertFalse(provider.snapshot.value.active)
            r.wire.clearSent()
            android.metadataTwice(FakeMetadata(rawTitle = "B", art = byteArrayOf(0x42)))
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
            assertFalse(provider.snapshot.value.active, "metadata alone does not activate media")
            assertFalse(r.chrome.value.mediaActive)
            assertEquals(listOf("B"), titles(r.wire))
            assertTrue(values(r.wire, 0x17).isEmpty())

            r.closeTransport()
            r.openTransport()
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
            assertEquals(listOf(0x17, 0x0D, 0x58, 0x40, 0x40), media(r.wire).map { it.sub })
            assertEquals(listOf("aa"), values(r.wire, 0x17))
            assertEquals(listOf("B"), titles(r.wire))
            assertEquals(listOf("00000142", "ff"), values(r.wire, 0x40))
            assertTrue(values(r.wire, 0x19).isEmpty())
            collector.cancel()
            provider.stop()
        }
    }

    @Test
    fun `ordinary reconnect preserves active snapshot and replays card art and known playback once`() = runTest {
        withRig { r ->
            r.owner.acceptSnapshot(r.generation, snapshot("Kept", playing = false, art = byteArrayOf(0x61)))
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
            r.closeTransport()
            assertEquals("Kept", r.chrome.value.nowPlaying?.title)
            assertTrue(r.chrome.value.mediaActive)
            assertEquals(false, r.chrome.value.mediaPlaying)
            r.openTransport()
            reachReady(r)
            r.owner.startStreaming()
            r.owner.startStreaming()
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
            assertEquals(listOf(0x17, 0x0D, 0x58, 0x40, 0x40, 0x19), media(r.wire).map { it.sub })
            assertEquals(listOf("Kept"), titles(r.wire))
            assertEquals(listOf("aa"), values(r.wire, 0x19))
        }
    }

    @Test
    fun `legacy title path shares replay boundary but blanks never become cards or inactive`() = runTest {
        withRig { r ->
            r.owner.updateNowPlaying("Legacy", "Album", "Artist")
            assertEquals("Legacy", r.chrome.value.nowPlaying?.title)
            assertFalse(r.chrome.value.mediaActive, "legacy metadata does not invent an Active event")
            r.owner.updateNowPlaying(" \t", "", "")
            assertNull(r.chrome.value.nowPlaying)
            r.owner.acceptSnapshot(r.generation, snapshot("Provider", playing = true))
            reachReady(r)
            r.owner.startStreaming()
            runCurrent()
            r.wire.clearSent()
            r.owner.updateNowPlaying("Legacy live", "Album", "Artist")
            runCurrent()
            assertEquals(listOf("Legacy live"), titles(r.wire))
            assertTrue(values(r.wire, 0x17).isEmpty())
            assertTrue(values(r.wire, 0x19).isEmpty())
            r.wire.clearSent()
            r.owner.updateNowPlaying(null, "", "")
            runCurrent()
            assertTrue(media(r.wire).isEmpty())
            assertTrue(r.chrome.value.mediaActive)
            assertEquals(true, r.chrome.value.mediaPlaying)
        }
    }

    @Test
    fun `provider blank metadata is retained and only valid JPEG lengths announce art`() = runTest {
        for (size in listOf(0, 1, 65_535, 65_536)) {
            withRig { r ->
                val art = ByteArray(size) { 0x51 }
                r.owner.acceptSnapshot(r.generation, snapshot("Initial"))
                reachReady(r)
                r.owner.startStreaming()
                runCurrent()
                r.wire.clearSent()
                r.owner.acceptEvent(r.generation, MediaEvent.Metadata(2, MediaMetadataSnapshot("", "Advertisement", "", artJpeg = art)))
                runCurrent()
                advanceTimeBy(700)
                runCurrent()
                assertEquals(listOf(""), titles(r.wire))
                assertEquals("", r.chrome.value.nowPlaying?.title)
                val valid = size in 1..65_535
                assertEquals(listOf(if (valid) "55" else "aa"), values(r.wire, 0x58))
                assertEquals(valid, values(r.wire, 0x40).isNotEmpty())
            }
        }
    }

    private class Rig(private val scope: TestScope) {
        val chrome = MutableStateFlow(DashChrome())
        var session: DashSession? = null
        private var transport: FakeDashTransport? = null
        private var parent: Job? = null
        val wire: FakeDashTransport get() = checkNotNull(transport)
        val publishedTitles = mutableListOf<String?>()
        var onPublished: ((DashChrome) -> Unit)? = null
        val owner = MediaForwarding(chrome, { session }) {
            publishedTitles.add(it.nowPlaying?.title)
            onPublished?.invoke(it)
        }
        val generation = owner.begin()

        fun openTransport() {
            check(session == null)
            val liveParent = Job()
            val liveWire = FakeDashTransport { scope.testScheduler.currentTime }
            parent = liveParent
            transport = liveWire
            session = DashSession.open(
                ssid = "K1G-MEDIA",
                chrome = chrome,
                heartbeat = { DashCommand.Heartbeat(25, 0x13, 0x19) },
                clock = { scope.testScheduler.currentTime },
                parent = liveParent,
                context = StandardTestDispatcher(scope.testScheduler),
            ) { liveWire }
        }

        suspend fun closeTransport() {
            session?.close(farewell = false)
            session = null
            parent?.cancel()
            parent = null
        }
    }

    private suspend fun TestScope.withRig(block: suspend (Rig) -> Unit) {
        val r = Rig(this)
        r.openTransport()
        try {
            block(r)
        } finally {
            r.closeTransport()
        }
    }

    private fun TestScope.reachReady(r: Rig) {
        advanceTimeBy(200)
        runCurrent()
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().public as RSAPublicKey
        r.wire.deliver(incoming(0x07, 0x00, key.modulus.toByteArray()))
        r.wire.deliver(incoming(0x07, 0x03, key.publicExponent.toByteArray()))
        runCurrent()
        r.wire.deliver(incoming(0x07, 0x01, byteArrayOf(0x01, 0x02)))
        advanceTimeBy(1_800)
        runCurrent()
        assertEquals(DashState.READY, r.session?.state?.value)
        r.wire.clearSent()
    }

    private class Packet(val sub: Int, val value: ByteArray)

    private fun media(wire: FakeDashTransport): List<Packet> = wire.sent.mapNotNull {
        val bytes = it.bytes
        if (bytes.size < 21 || bytes[17].toInt() != 0x05) return@mapNotNull null
        val sub = bytes[18].toInt() and 0xFF
        if (sub !in setOf(0x0D, 0x17, 0x19, 0x40, 0x58)) return@mapNotNull null
        val size = ((bytes[19].toInt() and 0xFF) shl 8) or (bytes[20].toInt() and 0xFF)
        Packet(sub, bytes.copyOfRange(21, 21 + size))
    }

    private fun title(packet: Packet): String {
        val end = packet.value.indexOf(0.toByte())
        assertTrue(end >= 0, "05 0D title must be NUL-terminated")
        return String(packet.value, 0, end, Charsets.US_ASCII)
    }

    private fun titles(wire: FakeDashTransport) = media(wire).filter { it.sub == 0x0D }.map(::title)
    private fun values(wire: FakeDashTransport, sub: Int) = media(wire).filter { it.sub == sub }.map { it.value.toHex() }
    private fun metadata(title: String, art: ByteArray? = null) =
        MediaMetadataSnapshot(title, "Album", "Artist", "id-$title", art)
    private fun snapshot(title: String, playing: Boolean? = null, art: ByteArray? = null) =
        MediaSnapshot(1, active = true, playing = playing, metadata = metadata(title, art))

    private fun incoming(type: Int, sub: Int, value: ByteArray): ByteArray {
        val size = 12 + value.size
        return byteArrayOf(
            (size shr 8).toByte(), size.toByte(), 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            type.toByte(), sub.toByte(), (value.size shr 8).toByte(), value.size.toByte(),
        ) + value
    }

    private class StreamingPause : AutoCloseable {
        private val previous = DebugLog.sink
        private val reached = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val timedOut = AtomicBoolean(false)

        init {
            DebugLog.sink = { _, _, _ -> }
            DebugLog.sink = { tag, _, message ->
                if (tag == "DashSession" && message == "state READY -> STREAMING") {
                    reached.countDown()
                    if (!release.await(10, TimeUnit.SECONDS)) timedOut.set(true)
                }
            }
        }

        fun awaitPaused() = assertTrue(reached.await(5, TimeUnit.SECONDS), "STREAMING transition reached")
        fun resume() = release.countDown()
        override fun close() {
            release.countDown()
            DebugLog.sink = previous
            assertFalse(timedOut.get(), "race must complete before the guard deadline")
        }
    }
}
