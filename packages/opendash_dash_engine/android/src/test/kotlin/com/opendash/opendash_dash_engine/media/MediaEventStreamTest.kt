package com.opendash.opendash_dash_engine.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaEventStreamTest {
    @Test
    fun `subscription before start sees one initial snapshot and every later event`() = runTest {
        val session = FakeMediaSession()
        val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
        val snapshots = mutableListOf<MediaSnapshot>()
        val events = mutableListOf<MediaEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            provider.collectWithSnapshot({ snapshots.add(it) }, { events.add(it) })
        }
        provider.start()
        session.playbackChanged(MediaPlayback.PLAYING)
        runCurrent()
        assertEquals(listOf(MediaSnapshot()), snapshots)
        assertEquals((1L..provider.snapshot.value.revision).toList(), events.map { it.revision })
        assertEquals(listOf("Active", "Metadata", "Active", "Playback"), events.map { it.javaClass.simpleName })
    }

    @Test
    fun `queued events before subscription are replayed only in snapshot and later events survive`() = runTest {
        val session = FakeMediaSession()
        val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
        provider.start() // publisher has not drained its queue yet
        val boundary = provider.snapshot.value
        val snapshots = mutableListOf<MediaSnapshot>()
        val events = mutableListOf<MediaEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            provider.collectWithSnapshot({ snapshots.add(it) }, { events.add(it) })
        }
        session.metadataTwice(FakeMetadata(rawTitle = "Later"))
        session.playbackChanged(MediaPlayback.PAUSED)
        runCurrent()
        assertEquals(listOf(boundary), snapshots)
        assertEquals(((boundary.revision + 1)..provider.snapshot.value.revision).toList(), events.map { it.revision })
        assertEquals("Later", (events.first() as MediaEvent.Metadata).metadata.title)
    }

    @Test
    fun `late subscriber still receives current snapshot after events without subscribers are drained`() = runTest {
        val session = FakeMediaSession(currentPlayback = MediaPlayback.PLAYING)
        val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
        provider.start()
        runCurrent()
        val snapshots = mutableListOf<MediaSnapshot>()
        val events = mutableListOf<MediaEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            provider.collectWithSnapshot({ snapshots.add(it) }, { events.add(it) })
        }
        runCurrent()
        assertEquals(listOf(provider.snapshot.value), snapshots)
        assertTrue(events.isEmpty())
        session.playbackChanged(MediaPlayback.OTHER)
        runCurrent()
        assertEquals(2, events.size)
        assertEquals(null, (events.last() as MediaEvent.Playback).playing)
    }

    @Test
    fun `concurrent updates during suspended replay arrive once after replay completes`() = runTest {
        val session = FakeMediaSession()
        val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
        provider.start()
        val replayGate = CompletableDeferred<Unit>()
        var initial: MediaSnapshot? = null
        var replayDone = false
        val events = mutableListOf<MediaEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            provider.collectWithSnapshot(
                onSnapshot = {
                    initial = it
                    replayGate.await()
                    replayDone = true
                },
                onEvent = {
                    assertTrue(replayDone)
                    events.add(it)
                },
            )
        }
        val update = FutureTask {
            repeat(200) { session.playbackChanged(if (it % 2 == 0) MediaPlayback.PLAYING else null) }
        }
        Thread(update, "media-replay-updates").start()
        update.get(5, TimeUnit.SECONDS)
        runCurrent()
        assertTrue(events.isEmpty())
        replayGate.complete(Unit)
        runCurrent()
        assertEquals(((initial!!.revision + 1)..provider.snapshot.value.revision).toList(), events.map { it.revision })
        assertEquals(400, events.size)
    }

    @Test
    fun `thread racing subscription is partitioned by the captured revision without a gap`() = runTest {
        repeat(20) {
            val session = FakeMediaSession()
            val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
            provider.start()
            val go = CountDownLatch(1)
            val update = FutureTask {
                check(go.await(5, TimeUnit.SECONDS))
                repeat(100) { session.playbackChanged(MediaPlayback.PLAYING) }
            }
            Thread(update, "media-subscribe-race").start()
            var initial: MediaSnapshot? = null
            val events = mutableListOf<MediaEvent>()
            go.countDown()
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                provider.collectWithSnapshot({ initial = it }, { events.add(it) })
            }
            update.get(5, TimeUnit.SECONDS)
            runCurrent()
            val boundary = initial!!.revision
            assertEquals(((boundary + 1)..provider.snapshot.value.revision).toList(), events.map { it.revision })
            assertEquals(events.size, events.map { it.revision }.distinct().size)
            collector.cancelAndJoin()
            provider.stop()
        }
    }

    @Test
    fun `slow subscriber has no bounded buffer drop and does not block callback producer`() = runTest {
        val session = FakeMediaSession()
        val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
        provider.start()
        val gate = CompletableDeferred<Unit>()
        var boundary = -1L
        val events = mutableListOf<MediaEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            provider.collectWithSnapshot(
                { boundary = it.revision },
                {
                    gate.await()
                    events.add(it)
                },
            )
        }
        session.playbackChanged(MediaPlayback.PAUSED)
        runCurrent() // first event is now blocked in the consumer
        repeat(4096) { session.playbackChanged(MediaPlayback.OTHER) }
        assertTrue(events.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(((boundary + 1)..provider.snapshot.value.revision).toList(), events.map { it.revision })
        assertEquals(8194, events.size)
    }

    @Test
    fun `ordinary SharedFlow onSubscription and cancellation follow standard semantics`() = runTest {
        val session = FakeMediaSession()
        val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
        var registered = false
        var finalized = false
        val firstEvents = mutableListOf<MediaEvent>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                provider.events.onSubscription { registered = true }.collect { firstEvents.add(it) }
            } finally {
                finalized = true
            }
        }
        assertTrue(registered)
        provider.start()
        runCurrent()
        assertEquals(2, firstEvents.size)
        collector.cancelAndJoin()
        assertTrue(finalized)
        session.playbackChanged(MediaPlayback.PLAYING)
        runCurrent()
        assertEquals(2, firstEvents.size)
        val laterEvents = mutableListOf<MediaEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            provider.collectWithSnapshot({}, { laterEvents.add(it) })
        }
        session.playbackChanged(null)
        runCurrent()
        assertEquals(2, laterEvents.size)
        assertFalse(provider.events.replayCache.isNotEmpty())
    }

    @Test
    fun `cancelling during initial replay removes subscriber and does not stall publisher`() = runTest {
        val session = FakeMediaSession()
        val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var laterSnapshot: MediaSnapshot? = null
        val stopped = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            provider.collectWithSnapshot({ gate.await() }, { error("cancelled collector") })
        }
        provider.start()
        runCurrent()
        stopped.cancelAndJoin()
        runCurrent()
        val events = mutableListOf<MediaEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            provider.collectWithSnapshot({ laterSnapshot = it }, { events.add(it) })
        }
        assertEquals(provider.snapshot.value, laterSnapshot)
        session.playbackChanged(MediaPlayback.PLAYING)
        runCurrent()
        assertEquals(2, events.size)
    }

    @Test
    fun `resubscription after unknown playback replays null and not the older known indicator`() = runTest {
        for ((known, unknown) in listOf(
            MediaPlayback.PLAYING to MediaPlayback.OTHER,
            MediaPlayback.PAUSED to null,
        )) {
            val session = FakeMediaSession(currentPlayback = known)
            val provider = MediaInfoProvider(FakeMediaPlatform(listOf(session)), backgroundScope)
            provider.start()
            session.playbackChanged(unknown)
            runCurrent()
            val replays = mutableListOf<MediaSnapshot>()
            val events = mutableListOf<MediaEvent>()
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                provider.collectWithSnapshot({ replays.add(it) }, { events.add(it) })
            }
            runCurrent()
            assertEquals(1, replays.size)
            assertEquals(null, replays.single().playing)
            assertTrue(replays.single().active)
            assertEquals("Song", replays.single().metadata?.title)
            assertTrue(events.isEmpty())
            collector.cancelAndJoin()
            provider.stop()
        }
    }
}
