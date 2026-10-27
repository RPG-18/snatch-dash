package com.opendash.opendash_dash_engine.voice

import com.opendash.opendash_dash_engine.DashPigeonError
import com.opendash.opendash_dash_engine.VoiceMusicPolicy
import com.opendash.opendash_dash_engine.VoiceSpeechResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class VoiceOutputTest {
    private class Rig {
        val tts = FakeTts()
        val focus = FakeFocus()
        val output = VoiceOutput(tts, focus)

        fun speak(
            text: String = "Turn right",
            policy: VoiceMusicPolicy = VoiceMusicPolicy.DUCK,
            language: String = "en-US",
            volume: Double = 0.5,
        ) = output.speak(text, language, volume, policy)
    }

    @Test
    fun `granted policies own a lease until completion and preserve speech parameters`() = runTest {
        for (policy in listOf(VoiceMusicPolicy.DUCK, VoiceMusicPolicy.PAUSE_AND_RESUME)) {
            val r = Rig()
            val result = r.speak("Поверните направо", policy, "ru-RU", 0.25)
            val speech = r.tts.speeches.single()
            assertEquals("Поверните направо", speech.text)
            assertEquals("ru-RU", speech.language)
            assertEquals(0.25f, speech.volume)
            assertEquals(policy, r.focus.requests.single().policy)
            assertTrue(r.focus.requests.single().held)
            assertFalse(result.isCompleted)
            r.tts.done(speech.id)
            assertEquals(VoiceSpeechResult.COMPLETED, result.await())
            assertNull(r.tts.playingId)
            assertFalse(r.focus.requests.single().held)
            assertEquals(1, r.focus.requests.single().releases)
            r.tts.done(speech.id)
            r.tts.stopped(speech.id)
            r.tts.error(speech.id, 4)
            assertEquals(VoiceSpeechResult.COMPLETED, result.await())
            assertEquals(1, r.focus.requests.single().releases)
        }
    }

    @Test
    fun `unchanged never requests focus and denied never starts speech`() = runTest {
        val unchanged = Rig()
        val result = unchanged.speak(policy = VoiceMusicPolicy.UNCHANGED)
        assertTrue(unchanged.focus.requests.isEmpty())
        unchanged.tts.done(checkNotNull(unchanged.tts.playingId))
        assertEquals(VoiceSpeechResult.COMPLETED, result.await())

        val denied = Rig()
        denied.focus.granted = false
        assertEquals(VoiceSpeechResult.FOCUS_DENIED, denied.speak().await())
        assertTrue(denied.tts.speeches.isEmpty())
        assertFalse(denied.focus.requests.single().held)
        denied.focus.requests.single().lose()
        assertTrue(denied.tts.speeches.isEmpty())
    }

    @Test
    fun `replacement releases old request before starting new policy and ignores all stale callbacks`() = runTest {
        val r = Rig()
        val a = r.speak("A")
        val idA = checkNotNull(r.tts.playingId)
        val leaseA = r.focus.requests.single()
        var terminalA = 0
        a.invokeOnCompletion { terminalA++ }
        r.focus.beforeRequest = { assertFalse(leaseA.held) }
        val b = r.speak("B", VoiceMusicPolicy.PAUSE_AND_RESUME)
        val idB = checkNotNull(r.tts.playingId)
        val leaseB = r.focus.requests.last()
        assertTrue(idA.toLong() < idB.toLong())
        assertEquals(VoiceSpeechResult.CANCELLED, a.await())
        r.tts.done(idA)
        r.tts.stopped(idA)
        r.tts.error(idA, null)
        r.tts.error(idA, 7)
        leaseA.lose()
        assertFalse(b.isCompleted)
        assertEquals(idB, r.tts.playingId)
        assertTrue(leaseB.held)
        assertEquals(0, leaseB.releases)
        assertEquals(1, terminalA)
        r.tts.done(idB)
        assertEquals(VoiceSpeechResult.COMPLETED, b.await())
        assertFalse(leaseB.held)
        assertEquals(1, leaseA.releases)
    }

    @Test
    fun `latest operation wins during asynchronous initialization without early focus`() = runTest {
        val r = Rig()
        r.tts.immediateInitialization = null
        val a = r.speak("A")
        val b = r.speak("B")
        assertEquals(VoiceSpeechResult.CANCELLED, a.await())
        assertFalse(b.isCompleted)
        assertEquals(1, r.tts.initializations)
        assertTrue(r.focus.requests.isEmpty())
        assertTrue(r.tts.speeches.isEmpty())
        r.tts.initializeResult(true)
        assertEquals("B", r.tts.speeches.single().text)
        r.tts.done(checkNotNull(r.tts.playingId))
        assertEquals(VoiceSpeechResult.COMPLETED, b.await())
    }

    @Test
    fun `stop during initialization prevents late speech and initialized owner serves next operation`() = runTest {
        val r = Rig()
        r.tts.immediateInitialization = null
        val a = r.speak("A")
        r.output.stop()
        assertEquals(VoiceSpeechResult.CANCELLED, a.await())
        r.tts.initializeResult(true)
        assertTrue(r.tts.speeches.isEmpty())
        assertTrue(r.focus.requests.isEmpty())
        val b = r.speak("B")
        assertEquals(1, r.tts.initializations)
        assertEquals("B", r.tts.speeches.single().text)
        r.output.stop()
        assertEquals(VoiceSpeechResult.CANCELLED, b.await())
        assertNull(r.tts.playingId)
        assertFalse(r.focus.requests.single().held)
    }

    @Test
    fun `dispose during initialization ignores late success and rejects subsequent calls`() = runTest {
        val r = Rig()
        r.tts.immediateInitialization = null
        val a = r.speak()
        r.output.dispose()
        assertEquals(VoiceSpeechResult.CANCELLED, a.await())
        r.tts.initializeResult(true)
        assertEquals(1, r.tts.shutdowns)
        assertTrue(r.tts.speeches.isEmpty())
        assertTrue(r.focus.requests.isEmpty())
        assertNull(r.tts.listener)
        assertError("ENGINE_GONE", r.speak())
        r.output.dispose()
        assertEquals(1, r.tts.shutdowns)
    }

    @Test
    fun `dispose during speech releases resources and stale callbacks cannot restart owner`() = runTest {
        val r = Rig()
        val result = r.speak()
        val id = checkNotNull(r.tts.playingId)
        val listener = checkNotNull(r.tts.listener)
        val lease = r.focus.requests.single()
        r.output.dispose()
        assertEquals(VoiceSpeechResult.CANCELLED, result.await())
        assertNull(r.tts.playingId)
        assertFalse(lease.held)
        listener.done(id)
        listener.stopped(id)
        listener.error(id, null)
        lease.lose()
        assertEquals(1, r.tts.speeches.size)
        assertEquals(1, lease.releases)
        assertEquals(1, r.tts.shutdowns)
    }

    @Test
    fun `both error callbacks terminate speech and free focus`() = runTest {
        for (code in listOf(null, 3)) {
            val r = Rig()
            val result = r.speak()
            r.tts.error(checkNotNull(r.tts.playingId), code)
            assertError("VOICE_TTS_PLAYBACK", result)
            assertNull(r.tts.playingId)
            assertFalse(r.focus.requests.single().held)
            val next = r.speak("Next")
            r.tts.done(checkNotNull(r.tts.playingId))
            assertEquals(VoiceSpeechResult.COMPLETED, next.await())
        }
    }

    @Test
    fun `immediate playback rejection and exceptions terminate and release focus`() = runTest {
        for (throws in listOf(false, true)) {
            val r = Rig()
            r.tts.acceptSpeech = false
            if (throws) r.tts.failureAt = "speak"
            assertError("VOICE_TTS_PLAYBACK", r.speak())
            assertNull(r.tts.playingId)
            assertFalse(r.focus.requests.single().held)
            assertEquals(1, r.focus.requests.single().releases)
        }
    }

    @Test
    fun `missing language or language exception does not request focus or speak`() = runTest {
        for (scenario in listOf("missing", "exception", "blank")) {
            val r = Rig()
            r.tts.languageAvailable = scenario != "missing"
            if (scenario == "exception") r.tts.failureAt = "language"
            assertError("VOICE_TTS_LANGUAGE", r.speak(language = if (scenario == "blank") " " else "ru-RU"))
            assertTrue(r.tts.speeches.isEmpty())
            assertTrue(r.focus.requests.isEmpty())
        }
    }

    @Test
    fun `failed initialization is cached and never acquires focus`() = runTest {
        for (throws in listOf(false, true)) {
            val r = Rig()
            r.tts.immediateInitialization = false
            if (throws) r.tts.failureAt = "initialize"
            assertError("VOICE_TTS_INIT", r.speak())
            r.tts.failureAt = null
            r.tts.immediateInitialization = true
            r.tts.initializeResult(true)
            assertError("VOICE_TTS_INIT", r.speak("Next"))
            assertEquals(1, r.tts.initializations)
            assertTrue(r.focus.requests.isEmpty())
            assertTrue(r.tts.speeches.isEmpty())
            r.output.dispose()
            assertEquals(1, r.tts.shutdowns)
        }
    }

    @Test
    fun `focus request exception fails without playback and next operation can run`() = runTest {
        val r = Rig()
        r.focus.throwOnRequest = true
        assertError("VOICE_AUDIO_FOCUS", r.speak())
        assertNull(r.tts.playingId)
        assertTrue(r.focus.requests.isEmpty())
        r.focus.throwOnRequest = false
        val next = r.speak()
        r.output.stop()
        assertEquals(VoiceSpeechResult.CANCELLED, next.await())
        assertFalse(r.focus.requests.single().held)
    }

    @Test
    fun `focus loss stops speech permanently and releases exactly its lease`() = runTest {
        val r = Rig()
        val result = r.speak()
        val lease = r.focus.requests.single()
        lease.lose()
        assertEquals(VoiceSpeechResult.CANCELLED, result.await())
        assertNull(r.tts.playingId)
        assertFalse(lease.held)
        // Gains are filtered by the adapter; repeated loss cannot resume the old phrase either.
        lease.lose()
        assertEquals(1, r.tts.speeches.size)
        assertEquals(1, lease.releases)
        val next = r.speak(policy = VoiceMusicPolicy.UNCHANGED)
        assertEquals(1, r.focus.requests.size)
        r.output.stop()
        assertEquals(VoiceSpeechResult.CANCELLED, next.await())
    }

    @Test
    fun `focus lost synchronously during request cancels and releases returned lease without speech`() = runTest {
        val r = Rig()
        r.focus.onRequest = { it.lose() }
        assertEquals(VoiceSpeechResult.CANCELLED, r.speak().await())
        assertTrue(r.tts.speeches.isEmpty())
        assertFalse(r.focus.requests.single().held)
        assertEquals(1, r.focus.requests.single().releases)
    }

    @Test
    fun `synchronous completion and stop callbacks cannot strand or double complete result`() = runTest {
        val r = Rig()
        r.tts.onSpeak = { r.tts.done(it) }
        assertEquals(VoiceSpeechResult.COMPLETED, r.speak().await())
        assertFalse(r.focus.requests.single().held)
        r.tts.onSpeak = null
        val next = r.speak()
        val id = checkNotNull(r.tts.playingId)
        r.tts.onStop = {
            r.tts.stopped(id)
            r.tts.error(id, 9)
            r.tts.done(id)
        }
        r.output.stop()
        assertEquals(VoiceSpeechResult.CANCELLED, next.await())
        assertTrue(r.focus.requests.none { it.held })
        assertTrue(r.focus.requests.all { it.releases == 1 })
    }

    @Test
    fun `platform stopped callback cancels without requiring another stop`() = runTest {
        val r = Rig()
        val result = r.speak()
        r.tts.stopped(checkNotNull(r.tts.playingId))
        assertEquals(VoiceSpeechResult.CANCELLED, result.await())
        assertNull(r.tts.playingId)
        assertEquals(0, r.tts.stops)
        assertFalse(r.focus.requests.single().held)
    }

    @Test
    fun `stop and release exceptions still attempt every cleanup and complete pending result`() = runTest {
        for (failure in listOf("stop", "release", "both")) {
            val r = Rig()
            val result = r.speak()
            val lease = r.focus.requests.single()
            if (failure != "release") r.tts.failureAt = "stop"
            lease.throwOnRelease = failure != "stop"
            r.output.stop()
            assertError(if (failure == "release") "VOICE_AUDIO_FOCUS" else "VOICE_TTS_PLAYBACK", result)
            assertEquals(1, r.tts.stops)
            assertNull(r.tts.playingId)
            assertEquals(1, lease.releases)
            assertFalse(lease.held)
            r.output.stop()
            assertEquals(1, lease.releases)
        }
    }

    @Test
    fun `completion release exception is reported and original playback error survives cleanup failure`() = runTest {
        val r = Rig()
        val done = r.speak()
        r.focus.requests.single().throwOnRelease = true
        r.tts.done(checkNotNull(r.tts.playingId))
        assertError("VOICE_AUDIO_FOCUS", done)
        val error = r.speak()
        r.focus.requests.last().throwOnRelease = true
        r.tts.failureAt = "stop"
        r.tts.error(checkNotNull(r.tts.playingId), 5)
        val reported = assertError("VOICE_TTS_PLAYBACK", error)
        assertEquals(5, reported.details)
        assertTrue(r.focus.requests.none { it.held })
    }

    @Test
    fun `dispose calls shutdown even when stop and release throw`() = runTest {
        val r = Rig()
        val result = r.speak()
        r.tts.failureAt = "stop"
        r.focus.requests.single().throwOnRelease = true
        r.tts.throwOnShutdown = true
        r.output.dispose()
        assertError("VOICE_TTS_PLAYBACK", result)
        assertEquals(1, r.tts.shutdowns)
        assertFalse(r.focus.requests.single().held)
        assertNull(r.tts.listener)
        assertError("ENGINE_GONE", r.speak())
    }

    @Test
    fun `reentrant replacement from old stop is newest and waits for old lease release`() = runTest {
        val r = Rig()
        val a = r.speak("A")
        val leaseA = r.focus.requests.single()
        var c: Deferred<VoiceSpeechResult>? = null
        r.tts.onStop = {
            r.tts.onStop = null
            c = r.speak("C", VoiceMusicPolicy.PAUSE_AND_RESUME)
            assertEquals(1, r.tts.speeches.size)
        }
        r.focus.beforeRequest = { assertFalse(leaseA.held) }
        val b = r.speak("B")
        assertEquals(VoiceSpeechResult.CANCELLED, a.await())
        assertEquals(VoiceSpeechResult.CANCELLED, b.await())
        assertEquals(listOf("A", "C"), r.tts.speeches.map { it.text })
        r.output.stop()
        assertEquals(VoiceSpeechResult.CANCELLED, assertNotNull(c).await())
        assertTrue(r.focus.requests.none { it.held })
    }

    @Test
    fun `reentrant stop during focus request releases returned resource and does not speak`() = runTest {
        val r = Rig()
        r.focus.onRequest = { r.output.stop() }
        assertEquals(VoiceSpeechResult.CANCELLED, r.speak().await())
        assertTrue(r.tts.speeches.isEmpty())
        assertFalse(r.focus.requests.single().held)
        assertEquals(1, r.focus.requests.single().releases)
    }

    @Test
    fun `blank speech completes without initializing and invalid volume never touches Android`() = runTest {
        val r = Rig()
        assertEquals(VoiceSpeechResult.COMPLETED, r.speak(" \n\t").await())
        for (volume in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1.01)) {
            assertError("VOICE_ARGUMENT", r.speak(volume = volume))
        }
        assertEquals(0, r.tts.initializations)
        assertTrue(r.tts.speeches.isEmpty())
        assertTrue(r.focus.requests.isEmpty())
        for (volume in listOf(0.0, 1.0)) {
            val result = r.speak(volume = volume)
            assertEquals(volume.toFloat(), r.tts.speeches.last().volume)
            r.output.stop()
            assertEquals(VoiceSpeechResult.CANCELLED, result.await())
        }
    }

    @Test
    fun `invalid request preserves active speech while blank replacement cancels it`() = runTest {
        val r = Rig()
        val active = r.speak()
        val id = r.tts.playingId
        assertError("VOICE_ARGUMENT", r.speak(volume = -1.0))
        assertEquals(id, r.tts.playingId)
        assertFalse(active.isCompleted)
        assertEquals(VoiceSpeechResult.COMPLETED, r.speak(" ").await())
        assertEquals(VoiceSpeechResult.CANCELLED, active.await())
        assertNull(r.tts.playingId)
        assertFalse(r.focus.requests.single().held)
    }

    private suspend fun assertError(code: String, result: Deferred<VoiceSpeechResult>): DashPigeonError {
        assertTrue(result.isCompleted, "Failure must not leave a pending result")
        val error = try {
            result.await()
            fail("Expected $code")
        } catch (error: DashPigeonError) {
            error
        }
        assertEquals(code, error.code)
        return error
    }

    private data class Speech(val text: String, val language: String, val volume: Float, val id: String)

    private class FakeTts : VoiceTtsAdapter {
        override var listener: VoiceTtsListener? = null
        var immediateInitialization: Boolean? = true
        var initializeResult: (Boolean) -> Unit = {}
        var initializations = 0
        var languageAvailable = true
        var language = ""
        var acceptSpeech = true
        var failureAt: String? = null
        var throwOnShutdown = false
        var playingId: String? = null
        val speeches = mutableListOf<Speech>()
        var stops = 0
        var shutdowns = 0
        var onSpeak: ((String) -> Unit)? = null
        var onStop: (() -> Unit)? = null

        override fun initialize(onResult: (Boolean) -> Unit) {
            initializations++
            initializeResult = onResult
            if (failureAt == "initialize") error("initialize failed")
            immediateInitialization?.let(onResult)
        }

        override fun setLanguage(languageTag: String): Boolean {
            if (failureAt == "language") error("language failed")
            language = languageTag
            return languageAvailable
        }

        override fun speak(text: String, volume: Float, utteranceId: String): Boolean {
            playingId = utteranceId
            speeches.add(Speech(text, language, volume, utteranceId))
            if (failureAt == "speak") error("speak failed")
            onSpeak?.invoke(utteranceId)
            return acceptSpeech
        }

        override fun stop() {
            stops++
            playingId = null
            onStop?.invoke()
            if (failureAt == "stop") error("stop failed")
        }

        override fun shutdown() {
            shutdowns++
            playingId = null
            if (throwOnShutdown) error("shutdown failed")
        }

        fun done(id: String) {
            if (playingId == id) playingId = null
            listener?.done(id)
        }

        fun stopped(id: String) {
            if (playingId == id) playingId = null
            listener?.stopped(id)
        }

        fun error(id: String, code: Int?) {
            listener?.error(id, code)
        }
    }

    private class FakeFocus : VoiceFocusAdapter {
        var granted = true
        var throwOnRequest = false
        val requests = mutableListOf<Lease>()
        var beforeRequest: (() -> Unit)? = null
        var onRequest: ((Lease) -> Unit)? = null

        override fun request(policy: VoiceMusicPolicy, onLost: () -> Unit): VoiceFocusLease? {
            beforeRequest?.invoke()
            if (throwOnRequest) error("request failed")
            val lease = Lease(policy, onLost, granted)
            requests.add(lease)
            onRequest?.invoke(lease)
            return if (granted) lease else null
        }
    }

    private class Lease(
        val policy: VoiceMusicPolicy,
        val lose: () -> Unit,
        var held: Boolean,
    ) : VoiceFocusLease {
        var releases = 0
        var throwOnRelease = false

        override fun release() {
            releases++
            held = false
            if (throwOnRelease) error("release failed")
        }
    }
}
