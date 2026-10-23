package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.DashCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual datagrams from the session writer; no assertions on forwarded command arguments. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashSessionMediaTest {
    private class Wire(clock: () -> Long) : DashTransport {
        val delegate = FakeDashTransport(clock)
        var beforeSend: ((ByteArray) -> Unit)? = null

        override fun send(data: ByteArray) {
            beforeSend?.invoke(data)
            delegate.send(data)
        }
        override fun sendRtp(data: ByteArray) = delegate.sendRtp(data)
        override suspend fun receive(): ByteArray = delegate.receive()
        override fun adoptSenderAsDash() = delegate.adoptSenderAsDash()
        override fun close() = delegate.close()
    }

    private class Rig(
        val session: DashSession,
        val wire: Wire,
        val chrome: MutableStateFlow<DashChrome>,
        val parent: Job,
    ) {
        fun metadata(value: NowPlaying) {
            chrome.value = chrome.value.copy(nowPlaying = value)
            session.sendMedia(mediaMetadataCommands(value))
        }

        fun inactive() {
            chrome.value = chrome.value.copy(nowPlaying = null, mediaActive = false, mediaPlaying = null)
            session.sendMedia(listOf(DashCommand.MediaActive(false)))
        }

        fun unknownPlayback() {
            chrome.value = chrome.value.copy(mediaActive = true, mediaPlaying = null)
            session.sendMedia(emptyList())
        }

        val packets: List<Packet>
            get() = wire.delegate.sent.map { packet(it.bytes, it.atMs) }
        val media: List<Packet>
            get() = packets.filter { p -> p.fields.any { it.type == 5 && it.sub in mediaTags } }

        suspend fun close(farewell: Boolean = false) {
            session.close(farewell)
            parent.cancel()
        }
    }

    private data class Field(val type: Int, val sub: Int, val value: ByteArray)
    private data class Packet(val atMs: Long, val fields: List<Field>) {
        fun value(type: Int, sub: Int): ByteArray? = fields.firstOrNull {
            it.type == type && it.sub == sub
        }?.value
        fun has(type: Int, sub: Int, vararg bytes: Int): Boolean = value(type, sub)?.contentEquals(
            ByteArray(bytes.size) { bytes[it].toByte() },
        ) == true
        val mediaTag: Int get() = fields.single().sub
        val mediaValue: ByteArray get() = fields.single().value
    }

    private fun TestScope.rig(
        chrome: MutableStateFlow<DashChrome> = MutableStateFlow(DashChrome()),
        clock: () -> Long = { testScheduler.currentTime },
        heartbeat: () -> DashCommand.Heartbeat = { DashCommand.Heartbeat(25, 0x13, 0x19) },
    ): Rig {
        val parent = Job()
        val wire = Wire(clock)
        val session = DashSession.open(
            ssid = "K1G-TEST", chrome = chrome, clock = clock, heartbeat = heartbeat,
            parent = parent, context = StandardTestDispatcher(testScheduler),
        ) { wire }
        return Rig(session, wire, chrome, parent)
    }

    private fun TestScope.ready(r: Rig) {
        advanceTimeBy(200)
        runCurrent()
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().public as RSAPublicKey
        r.wire.delegate.deliver(incoming(7, 0, key.modulus.toByteArray()))
        r.wire.delegate.deliver(incoming(7, 3, key.publicExponent.toByteArray()))
        runCurrent()
        r.wire.delegate.deliver(incoming(7, 1, byteArrayOf(1, 2)))
        advanceTimeBy(1_800)
        runCurrent()
        assertEquals(DashState.READY, r.session.state.value)
        r.wire.delegate.clearSent()
    }

    private fun TestScope.streaming(r: Rig) {
        ready(r)
        r.session.startStreaming()
        runCurrent()
        r.wire.delegate.clearSent()
    }

    @Test
    fun `maximum artwork yields to projection ACKs and live heartbeat values`() = runTest {
        var musicVolume = 0x10
        var callVolume = 0x1A
        val r = rig(heartbeat = { DashCommand.Heartbeat(25, musicVolume, callVolume) })
        try {
            streaming(r)
            advanceTimeBy(850)
            runCurrent()
            r.wire.delegate.clearSent()
            val image = image(65_535, 17)
            val startedAt = testScheduler.currentTime
            r.metadata(NowPlaying("Maximum", artJpeg = image))
            runCurrent()
            advanceTimeBy(115)
            runCurrent()
            val receivedAt = testScheduler.currentTime
            musicVolume = 0x1A
            callVolume = 0x10
            r.wire.delegate.deliver(incoming(9, 0, byteArrayOf(0, 1, 0x22)))
            r.wire.delegate.deliver(incoming(9, 6, byteArrayOf(0x55)))
            runCurrent()
            advanceTimeBy(650)
            runCurrent()

            val chunks = r.media.filter { it.mediaTag == 0x40 }
            assertEquals(66, chunks.size)
            assertEquals(startedAt, chunks.first().atMs)
            assertArt(image, chunks)
            assertTrue(chunks.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= 10 })
            val projections = r.packets.filter { it.has(5, 0x56, 0x55) }
            assertTrue(projections.size >= 3)
            assertTrue(projections.first().atMs - startedAt <= 250)
            assertTrue(projections.zipWithNext().all { (a, b) -> b.atMs - a.atMs <= 250 })
            assertTrue(projections.any { it.atMs in (chunks.first().atMs + 1) until chunks.last().atMs })
            for ((sub, value) in listOf(0x80 to 0x22, 0x11 to 0x55)) {
                val ack = r.packets.single { it.has(6, sub, value) }
                assertTrue(ack.atMs - receivedAt in 0L..250L)
                assertTrue(ack.atMs < chunks.last().atMs)
            }
            val heartbeat = r.packets.single { it.value(6, 8) != null }
            assertTrue(heartbeat.atMs in (chunks.first().atMs + 1) until chunks.last().atMs)
            assertTrue(heartbeat.has(6, 8, 5))
            assertTrue(heartbeat.has(6, 0x10, 65))
            assertTrue(heartbeat.has(5, 0x4C, 0x1A))
            assertTrue(heartbeat.has(5, 0x1B, 0x10))
        } finally {
            r.close()
        }
    }

    @Test
    fun `new JPEG cancels delayed old chunks before the new card and restarts framing`() = runTest {
        val r = rig()
        try {
            streaming(r)
            val a = image(3_000, 11)
            val b = image(2_001, 73)
            r.metadata(NowPlaying("A", artJpeg = a))
            runCurrent()
            advanceTimeBy(5)
            r.metadata(NowPlaying("B", "album", "artist", artJpeg = b))
            runCurrent()
            // The old timer is pending and B must not bypass its remaining spacing.
            assertEquals(listOf(0x0D, 0x58, 0x40, 0x0D, 0x58), r.media.map { it.mediaTag })
            advanceTimeBy(40)
            runCurrent()
            val packets = r.media
            assertEquals(listOf(0x0D, 0x58, 0x40, 0x0D, 0x58, 0x40, 0x40, 0x40), packets.map { it.mediaTag })
            assertCard(packets[0], "A")
            assertCard(packets[3], "B", "album", "artist")
            assertContentEquals(byteArrayOf(0x55), packets[4].mediaValue)
            assertArt(b, packets.drop(5))
            assertTrue(packets[5].atMs - packets[2].atMs >= 10, "spacing survives supersession")
            assertContentEquals(a.copyOfRange(0, 1_000), packets[2].mediaValue.copyOfRange(3, 1_003))
        } finally {
            r.close()
        }
    }

    @Test
    fun `metadata without JPEG sends absent marker with no delayed old tail`() = runTest {
        val r = rig()
        try {
            streaming(r)
            r.metadata(NowPlaying("A", artJpeg = image(65_535, 11)))
            runCurrent()
            advanceTimeBy(5)
            r.metadata(NowPlaying("B"))
            runCurrent()
            advanceTimeBy(700)
            runCurrent()
            val packets = r.media
            assertEquals(listOf(0x0D, 0x58, 0x40, 0x0D, 0x58), packets.map { it.mediaTag })
            assertCard(packets[3], "B")
            assertContentEquals(byteArrayOf(0xAA.toByte()), packets[4].mediaValue)
        } finally {
            r.close()
        }
    }

    @Test
    fun `repeated replacement keeps every accepted card but no abandoned A or B chunks`() = runTest {
        val r = rig()
        try {
            streaming(r)
            r.metadata(NowPlaying("A", artJpeg = image(4_000, 11)))
            runCurrent()
            advanceTimeBy(3)
            r.metadata(NowPlaying("B", artJpeg = image(4_000, 37)))
            runCurrent()
            advanceTimeBy(3)
            val c = image(1_001, 73)
            r.metadata(NowPlaying("C", artJpeg = c))
            runCurrent()
            advanceTimeBy(30)
            runCurrent()
            val packets = r.media
            assertEquals(listOf(0x0D, 0x58, 0x40, 0x0D, 0x58, 0x0D, 0x58, 0x40, 0x40), packets.map { it.mediaTag })
            assertCard(packets[0], "A")
            assertCard(packets[3], "B")
            assertCard(packets[5], "C")
            assertArt(c, packets.drop(7))
            assertTrue(packets[7].atMs - packets[2].atMs >= 10)
        } finally {
            r.close()
        }
    }

    @Test
    fun `metadata enqueued before writer runs preserves both cards and markers but replaces art`() = runTest {
        val r = rig()
        try {
            streaming(r)
            r.metadata(NowPlaying("A", artJpeg = image(3_000, 11)))
            val b = image(1_001, 73)
            r.metadata(NowPlaying("B", artJpeg = b))
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            val packets = r.media
            assertEquals(listOf(0x0D, 0x58, 0x0D, 0x58, 0x40, 0x40), packets.map { it.mediaTag })
            assertCard(packets[0], "A")
            assertCard(packets[2], "B")
            assertArt(b, packets.drop(4))
        } finally {
            r.close()
        }
    }

    @Test
    fun `dedup producing no event leaves the entire current art transfer intact`() = runTest {
        val r = rig()
        try {
            streaming(r)
            val a = image(4_001, 11)
            r.metadata(NowPlaying("A", mediaId = "same-id", artJpeg = a))
            runCurrent()
            advanceTimeBy(5)
            runCurrent()
            // A suppressed callback has no sendMedia call, not an empty accepted event.
            advanceTimeBy(60)
            runCurrent()
            val packets = r.media
            assertEquals(1, packets.count { it.mediaTag == 0x0D })
            assertEquals(1, packets.count { it.mediaTag == 0x58 })
            assertArt(a, packets.filter { it.mediaTag == 0x40 })
        } finally {
            r.close()
        }
    }

    @Test
    fun `card-only fallback does not cancel an already accepted art transfer`() = runTest {
        val r = rig()
        try {
            streaming(r)
            val a = image(3_001, 11)
            r.metadata(NowPlaying("A", artJpeg = a))
            runCurrent()
            advanceTimeBy(5)
            r.session.sendMedia(listOf(DashCommand.NowPlaying("", "NowPlaying", "")))
            runCurrent()
            advanceTimeBy(40)
            runCurrent()
            val packets = r.media
            assertEquals(listOf(0x0D, 0x58, 0x40, 0x0D, 0x40, 0x40, 0x40), packets.map { it.mediaTag })
            assertCard(packets[3], "", "NowPlaying")
            assertArt(a, packets.filter { it.mediaTag == 0x40 })
        } finally {
            r.close()
        }
    }

    @Test
    fun `inactive lets an already started art packet finish and cancels every remaining chunk`() = runTest {
        val r = rig()
        try {
            streaming(r)
            var interrupted = false
            r.wire.beforeSend = { bytes ->
                if (!interrupted && packet(bytes).value(5, 0x40) != null) {
                    interrupted = true
                    r.inactive()
                }
            }
            r.metadata(NowPlaying("A", artJpeg = image(65_535, 11)))
            runCurrent()
            advanceTimeBy(1_200)
            runCurrent()
            val packets = r.media
            assertTrue(interrupted)
            assertEquals(listOf(0x0D, 0x58, 0x40, 0x17), packets.map { it.mediaTag })
            assertContentEquals(byteArrayOf(0xAA.toByte()), packets.last().mediaValue)
            assertEquals(null, r.chrome.value.nowPlaying)
        } finally {
            r.close()
        }
    }

    @Test
    fun `inactive invalidates queued event and periodic cards behind an in-flight control packet`() = runTest {
        val r = rig()
        try {
            streaming(r)
            r.metadata(NowPlaying("A", artJpeg = image(65_535, 11)))
            runCurrent()
            r.wire.delegate.clearSent()
            var interrupted = false
            r.wire.beforeSend = { bytes ->
                if (!interrupted && testScheduler.currentTime % 1_000L == 0L &&
                    packet(bytes).has(5, 0x56, 0x55)
                ) {
                    // The tick already queued periodic A. B is an accepted event behind
                    // the packet in progress; inactive must invalidate BOTH queued cards.
                    interrupted = true
                    r.metadata(NowPlaying("B", artJpeg = image(2_001, 73)))
                    r.inactive()
                }
            }
            advanceTimeBy(1_300)
            runCurrent()
            assertTrue(interrupted)
            val inactive = r.packets.indexOfFirst { it.has(5, 0x17, 0xAA) }
            assertTrue(inactive >= 0)
            assertFalse(r.media.any { it.mediaTag == 0x0D }, "neither queued A nor queued B may leave")
            assertFalse(r.packets.drop(inactive + 1).any {
                it.value(5, 0x40) != null || it.value(5, 0x0D) != null
            })
            assertEquals(1, r.media.count { it.mediaTag == 0x17 })
        } finally {
            r.close()
        }
    }

    @Test
    fun `farewell abandons pending art and queued media instead of draining them into teardown`() = runTest {
        val r = rig()
        streaming(r)
        r.metadata(NowPlaying("A", artJpeg = image(65_535, 11)))
        runCurrent()
        advanceTimeBy(5)
        r.metadata(NowPlaying("B", artJpeg = image(2_001, 73)))
        r.wire.delegate.clearSent()
        r.close(farewell = true)
        runCurrent()
        assertEquals(2, r.packets.size)
        assertTrue(r.packets[0].has(5, 0x56, 0xAA))
        assertTrue(r.packets[1].has(6, 5, 0xAA))
        val ended = r.wire.delegate.sent.size
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ended, r.wire.delegate.sent.size)

        val next = rig()
        try {
            streaming(next)
            r.session.sendMedia(listOf(DashCommand.AlbumArt(image(2_001, 99))))
            next.metadata(NowPlaying("Fresh"))
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf(0x0D, 0x58), next.media.map { it.mediaTag })
            assertCard(next.media[0], "Fresh")
            assertEquals(ended, r.wire.delegate.sent.size)
        } finally {
            next.close()
        }
    }

    @Test
    fun `unknown playback clears known replay for either prior playing state on reconnect`() = runTest {
        for (known in listOf(true, false)) {
            val chrome = MutableStateFlow(
                DashChrome(mediaActive = true, mediaPlaying = known, nowPlaying = NowPlaying("A")),
            )
            val first = rig(chrome)
            try {
                ready(first)
                first.session.startStreaming()
                runCurrent()
                assertEquals(listOf(0x17, 0x0D, 0x58, 0x19), first.media.map { it.mediaTag })
                assertContentEquals(byteArrayOf(if (known) 0x55 else 0xAA.toByte()), first.media.last().mediaValue)
                first.unknownPlayback()
                runCurrent()
            } finally {
                first.close()
            }
            val second = rig(chrome)
            try {
                ready(second)
                second.session.startStreaming()
                runCurrent()
                assertEquals(listOf(0x17, 0x0D, 0x58), second.media.map { it.mediaTag })
                assertTrue(second.media.first().has(5, 0x17, 0x55))
                assertEquals(null, chrome.value.mediaPlaying)
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun `replacement metadata retains one deferred current playback after latest art completes`() = runTest {
        val r = rig(MutableStateFlow(DashChrome(
            mediaActive = true, mediaPlaying = true,
            nowPlaying = NowPlaying("A", artJpeg = image(3_000, 11)),
        )))
        try {
            ready(r)
            r.session.startStreaming()
            runCurrent()
            advanceTimeBy(5)
            val b = image(2_001, 73)
            r.metadata(NowPlaying("B", artJpeg = b))
            runCurrent()
            advanceTimeBy(40)
            runCurrent()
            val packets = r.media
            assertEquals(listOf(0x17, 0x0D, 0x58, 0x40, 0x0D, 0x58, 0x40, 0x40, 0x40, 0x19), packets.map { it.mediaTag })
            assertArt(b, packets.subList(6, 9))
            assertTrue(packets.last().has(5, 0x19, 0x55))
            assertTrue(packets.last().atMs >= packets[8].atMs)
        } finally {
            r.close()
        }
    }

    @Test
    fun `unknown during replay art cancels the deferred old playback indicator`() = runTest {
        val image = image(3_000, 11)
        val r = rig(MutableStateFlow(DashChrome(
            mediaActive = true, mediaPlaying = true, nowPlaying = NowPlaying("A", artJpeg = image),
        )))
        try {
            ready(r)
            r.session.startStreaming()
            runCurrent()
            advanceTimeBy(5)
            r.unknownPlayback()
            runCurrent()
            advanceTimeBy(40)
            runCurrent()
            assertFalse(r.media.any { it.mediaTag == 0x19 })
            assertArt(image, r.media.filter { it.mediaTag == 0x40 })
        } finally {
            r.close()
        }
    }

    @Test
    fun `rebind clearing metadata cancels art without inventing inactive`() = runTest {
        val r = rig()
        try {
            streaming(r)
            r.metadata(NowPlaying("A", artJpeg = image(3_000, 11)))
            runCurrent()
            advanceTimeBy(5)
            r.chrome.value = r.chrome.value.copy(nowPlaying = null, mediaActive = true, mediaPlaying = null)
            r.session.sendMedia(listOf(DashCommand.MediaActive(true)))
            runCurrent()
            advanceTimeBy(40)
            runCurrent()
            assertEquals(listOf(0x0D, 0x58, 0x40, 0x17), r.media.map { it.mediaTag })
            assertTrue(r.media.last().has(5, 0x17, 0x55))
        } finally {
            r.close()
        }
    }

    @Test
    fun `start replay is streaming-only and repeated start cannot duplicate the captured snapshot`() = runTest {
        val image = image(1_001, 11)
        val chrome = MutableStateFlow(DashChrome(
            mediaActive = true, mediaPlaying = false, nowPlaying = NowPlaying("A", artJpeg = image),
        ))
        val r = rig(chrome)
        try {
            r.session.startStreaming()
            r.session.sendMedia(mediaReplay(chrome.value))
            ready(r)
            r.session.sendMedia(mediaReplay(chrome.value))
            runCurrent()
            assertTrue(r.media.isEmpty(), "READY is not STREAMING")
            r.session.startStreaming()
            r.session.startStreaming()
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            assertEquals(listOf(0x17, 0x0D, 0x58, 0x40, 0x40, 0x19), r.media.map { it.mediaTag })
            assertArt(image, r.media.filter { it.mediaTag == 0x40 })
            assertTrue(r.media.last().has(5, 0x19, 0xAA))
        } finally {
            r.close()
        }
    }

    @Test
    fun `art deadline follows actual packet completion rather than its scheduled timestamp`() = runTest {
        var elapsedInSend = 0L
        val r = rig(clock = { testScheduler.currentTime + elapsedInSend })
        try {
            streaming(r)
            var first = true
            r.wire.beforeSend = { bytes ->
                if (first && packet(bytes).value(5, 0x40) != null) {
                    first = false
                    elapsedInSend += 137
                }
            }
            r.metadata(NowPlaying("A", artJpeg = image(1_001, 11)))
            runCurrent()
            advanceTimeBy(9)
            runCurrent()
            assertEquals(1, r.media.count { it.mediaTag == 0x40 })
            advanceTimeBy(1)
            runCurrent()
            val chunks = r.media.filter { it.mediaTag == 0x40 }
            assertEquals(2, chunks.size)
            assertEquals(10L, chunks[1].atMs - chunks[0].atMs)
        } finally {
            r.close()
        }
    }

    @Test
    fun `inactive followed by new media does not reset the session art spacing clock`() = runTest {
        val r = rig()
        try {
            streaming(r)
            r.metadata(NowPlaying("A", artJpeg = image(3_000, 11)))
            runCurrent()
            advanceTimeBy(3)
            r.inactive()
            runCurrent()
            advanceTimeBy(2)
            val b = image(1_001, 73)
            r.metadata(NowPlaying("B", artJpeg = b))
            runCurrent()
            assertEquals(1, r.media.count { it.mediaTag == 0x40 })
            advanceTimeBy(25)
            runCurrent()
            val packets = r.media
            assertEquals(listOf(0x0D, 0x58, 0x40, 0x17, 0x0D, 0x58, 0x40, 0x40), packets.map { it.mediaTag })
            assertArt(b, packets.takeLast(2))
            assertTrue(packets[6].atMs - packets[2].atMs >= 10)
        } finally {
            r.close()
        }
    }

    @Test
    fun `transport teardown drops old tail while reconnect replays the preserved snapshot from first frame`() = runTest {
        val a = image(3_001, 11)
        val chrome = MutableStateFlow(DashChrome(
            mediaActive = true, mediaPlaying = true, nowPlaying = NowPlaying("A", artJpeg = a),
        ))
        val first = rig(chrome)
        ready(first)
        first.session.startStreaming()
        runCurrent()
        assertEquals(1, first.media.count { it.mediaTag == 0x40 })
        first.close()
        val firstEnded = first.wire.delegate.sent.size
        val next = rig(chrome)
        try {
            ready(next)
            next.session.startStreaming()
            runCurrent()
            // A callback retained by the closed session cannot acquire the new writer.
            first.session.sendMedia(mediaReplay(chrome.value))
            advanceTimeBy(50)
            runCurrent()
            assertEquals(listOf(0x17, 0x0D, 0x58, 0x40, 0x40, 0x40, 0x40, 0x19), next.media.map { it.mediaTag })
            assertArt(a, next.media.filter { it.mediaTag == 0x40 })
            assertEquals(firstEnded, first.wire.delegate.sent.size)
        } finally {
            next.close()
        }
    }

    @Test
    fun `media enqueue racing farewell cannot get past the terminal lifecycle claim`() = runTest {
        val r = rig()
        streaming(r)
        var attempted = false
        r.wire.beforeSend = { bytes ->
            if (!attempted && packet(bytes).has(5, 0x56, 0xAA)) {
                attempted = true
                r.metadata(NowPlaying("Too late", artJpeg = image(3_000, 11)))
                r.session.startStreaming()
            }
        }
        r.close(farewell = true)
        runCurrent()
        assertTrue(attempted)
        assertEquals(2, r.packets.size)
        assertTrue(r.packets[0].has(5, 0x56, 0xAA))
        assertTrue(r.packets[1].has(6, 5, 0xAA))
        assertEquals(DashState.IDLE, r.session.state.value)
    }

    @Test
    fun `unknown while replay card is in flight cannot restore its captured playback even without art`() = runTest {
        val r = rig(MutableStateFlow(DashChrome(
            mediaActive = true, mediaPlaying = true, nowPlaying = NowPlaying("A"),
        )))
        try {
            ready(r)
            var cleared = false
            r.wire.beforeSend = { bytes ->
                if (!cleared && packet(bytes).value(5, 0x0D) != null) {
                    cleared = true
                    r.unknownPlayback()
                }
            }
            r.session.startStreaming()
            runCurrent()
            assertTrue(cleared)
            assertEquals(listOf(0x17, 0x0D, 0x58), r.media.map { it.mediaTag })
        } finally {
            r.close()
        }
    }

    @Test
    fun `replacement during replay card defers current playback until replacement art finishes`() = runTest {
        val a = image(3_000, 11)
        val b = image(1_001, 73)
        val r = rig(MutableStateFlow(DashChrome(
            mediaActive = true, mediaPlaying = true, nowPlaying = NowPlaying("A", artJpeg = a),
        )))
        try {
            ready(r)
            var replaced = false
            r.wire.beforeSend = { bytes ->
                if (!replaced && packet(bytes).value(5, 0x0D) != null) {
                    replaced = true
                    r.metadata(NowPlaying("B", artJpeg = b))
                }
            }
            r.session.startStreaming()
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            assertTrue(replaced)
            val packets = r.media
            assertEquals(listOf(0x17, 0x0D, 0x58, 0x0D, 0x58, 0x40, 0x40, 0x19), packets.map { it.mediaTag })
            assertArt(b, packets.subList(5, 7))
            assertTrue(packets.last().has(5, 0x19, 0x55))
        } finally {
            r.close()
        }
    }

    @Test
    fun `rapid playing then paused events both leave once between art chunks and cancel replay tail`() = runTest {
        val art = image(3_001, 11)
        val r = rig(MutableStateFlow(DashChrome(
            mediaActive = true, mediaPlaying = true, nowPlaying = NowPlaying("A", artJpeg = art),
        )))
        try {
            ready(r)
            r.session.startStreaming()
            runCurrent()
            assertEquals(1, r.media.count { it.mediaTag == 0x40 })
            advanceTimeBy(5)
            r.chrome.value = r.chrome.value.copy(mediaPlaying = true)
            r.session.sendMedia(listOf(
                DashCommand.MediaActive(true), DashCommand.MediaPlayback(true),
            ))
            r.chrome.value = r.chrome.value.copy(mediaPlaying = false)
            r.session.sendMedia(listOf(
                DashCommand.MediaActive(true), DashCommand.MediaPlayback(false),
            ))
            runCurrent()
            val beforeNextChunk = r.media
            assertEquals(
                listOf(0x17, 0x0D, 0x58, 0x40, 0x17, 0x19, 0x17, 0x19),
                beforeNextChunk.map { it.mediaTag },
            )
            val indicators = beforeNextChunk.filter { it.mediaTag == 0x19 }
            assertTrue(indicators[0].has(5, 0x19, 0x55))
            assertTrue(indicators[1].has(5, 0x19, 0xAA))
            advanceTimeBy(40)
            runCurrent()
            assertEquals(2, r.media.count { it.mediaTag == 0x19 }, "no deferred replay duplicate")
            assertArt(art, r.media.filter { it.mediaTag == 0x40 })
        } finally {
            r.close()
        }
    }

    @Test
    fun `tick cannot publish uncommitted chrome metadata before art invalidation`() = runTest {
        val r = rig()
        try {
            streaming(r)
            advanceTimeBy(850)
            runCurrent()
            r.metadata(NowPlaying("A", artJpeg = image(65_535, 11)))
            runCurrent()
            r.wire.delegate.clearSent()
            advanceTimeBy(100)
            runCurrent()

            // The owner's chrome write has happened, but its enqueue has not. A tick
            // deliberately lands in that gap while A is still sending chunks.
            val b = NowPlaying("B", artJpeg = image(1_001, 73))
            r.chrome.value = r.chrome.value.copy(nowPlaying = b)
            advanceTimeBy(50)
            runCurrent()
            val beforeAcceptance = r.media
            val periodic = beforeAcceptance.filter { it.mediaTag == 0x0D }
            assertEquals(1, periodic.size)
            assertCard(periodic.single(), "A")
            assertTrue(beforeAcceptance.any { it.mediaTag == 0x40 && it.atMs >= 2_950 })

            r.session.sendMedia(mediaMetadataCommands(b))
            runCurrent()
            advanceTimeBy(30)
            runCurrent()
            val packets = r.media
            val cards = packets.filter { it.mediaTag == 0x0D }
            assertEquals(2, cards.size)
            assertCard(cards[0], "A")
            assertCard(cards[1], "B")
            val acceptedB = packets.indexOf(cards[1])
            assertEquals(listOf(0x0D, 0x58, 0x40, 0x40), packets.drop(acceptedB).map { it.mediaTag })
            assertArt(requireNotNull(b.artJpeg), packets.drop(acceptedB + 2))
        } finally {
            r.close()
        }
    }

    companion object {
        private val mediaTags = setOf(0x0D, 0x17, 0x19, 0x58, 0x40)

        private fun packet(bytes: ByteArray, atMs: Long = 0L): Packet {
            assertEquals(bytes.size, ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255))
            val fields = mutableListOf<Field>()
            var offset = 17
            while (offset < bytes.size) {
                val type = bytes[offset].toInt() and 255
                val sub = bytes[offset + 1].toInt() and 255
                val size = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
                fields += Field(type, sub, bytes.copyOfRange(offset + 4, offset + 4 + size))
                offset += 4 + size
            }
            assertEquals(bytes.size, offset)
            return Packet(atMs, fields)
        }

        private fun assertCard(packet: Packet, title: String, album: String = "", artist: String = "") {
            assertEquals(0x0D, packet.mediaTag)
            assertContentEquals("$title\u0000$album\u0000$artist".toByteArray(Charsets.US_ASCII), packet.mediaValue)
        }

        private fun assertArt(image: ByteArray, packets: List<Packet>) {
            val expectedCount = if (image.size <= 1_000) 2 else (image.size + 999) / 1_000
            assertEquals(expectedCount, packets.size)
            var offset = 0
            for ((index, packet) in packets.withIndex()) {
                assertEquals(0x40, packet.mediaTag)
                val value = packet.mediaValue
                val prefix = if (index == 0) 3 else 1
                if (index == 0) {
                    assertContentEquals(byteArrayOf(0, (image.size shr 8).toByte(), image.size.toByte()), value.copyOfRange(0, 3))
                } else {
                    assertEquals(if (index == packets.lastIndex) 255 else index, value[0].toInt() and 255)
                }
                val size = if (index == 0) minOf(1_000, image.size) else minOf(1_000, image.size - offset)
                assertEquals(prefix + size, value.size)
                assertContentEquals(image.copyOfRange(offset, offset + size), value.copyOfRange(prefix, value.size))
                offset += size
            }
            assertEquals(image.size, offset)
        }

        private fun image(size: Int, seed: Int): ByteArray = ByteArray(size) {
            ((it * 37 + it / 251 + seed) and 255).toByte()
        }

        private fun incoming(type: Int, sub: Int, value: ByteArray): ByteArray {
            val size = 12 + value.size
            return byteArrayOf(
                (size shr 8).toByte(), size.toByte(), 0, 1, 0, 0, 0, 0,
                type.toByte(), sub.toByte(), (value.size shr 8).toByte(), value.size.toByte(),
            ) + value
        }
    }
}
