package com.opendash.opendash_dash_engine.voice

import com.opendash.opendash_dash_engine.DashPigeonError
import com.opendash.opendash_dash_engine.VoiceMusicPolicy
import com.opendash.opendash_dash_engine.VoiceSpeechResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Main-thread owner; adapter callbacks may also arrive synchronously. */
internal class VoiceOutput(
    private val tts: VoiceTtsAdapter,
    private val focus: VoiceFocusAdapter,
) {
    private enum class Initialization { NEW, PENDING, READY, FAILED }

    private class Operation(
        val id: String,
        val text: String,
        val languageTag: String,
        val volume: Float,
        val policy: VoiceMusicPolicy,
    ) {
        val result = CompletableDeferred<VoiceSpeechResult>()
        var started = false
        var speaking = false
        var lease: VoiceFocusLease? = null
        var terminal = false
    }

    private var initialization = Initialization.NEW
    private var initError: DashPigeonError? = null
    private var active: Operation? = null
    private var nextId = 0L
    private var disposed = false
    private var transitionDepth = 0
    private var advancing = false

    init {
        tts.listener = object : VoiceTtsListener {
            override fun done(id: String): Unit = transition {
                active?.takeIf { it.id == id }?.let {
                    finish(it, VoiceSpeechResult.COMPLETED, stopSpeech = false)
                }
            }

            override fun stopped(id: String): Unit = transition {
                active?.takeIf { it.id == id }?.let {
                    finish(it, VoiceSpeechResult.CANCELLED, stopSpeech = false)
                }
            }

            override fun error(id: String, errorCode: Int?): Unit = transition {
                active?.takeIf { it.id == id }?.let {
                    fail(it, DashPigeonError("VOICE_TTS_PLAYBACK", "Speech failed", errorCode))
                }
            }
        }
    }

    fun speak(
        text: String,
        languageTag: String,
        volume: Double,
        policy: VoiceMusicPolicy,
    ): Deferred<VoiceSpeechResult> = transition {
        if (disposed) return@transition failed("ENGINE_GONE", "Voice owner is disposed")
        if (!volume.isFinite() || volume !in 0.0..1.0) {
            return@transition failed("VOICE_ARGUMENT", "Voice volume must be finite and in [0, 1]")
        }
        val operation = Operation((++nextId).toString(), text, languageTag, volume.toFloat(), policy)
        val previous = active
        // Register before cleanup: reentrant speak from cleanup must win over this call too.
        active = operation
        previous?.let { finish(it, VoiceSpeechResult.CANCELLED) }
        if (active === operation && text.isBlank()) {
            finish(operation, VoiceSpeechResult.COMPLETED, stopSpeech = false)
        }
        operation.result
    }

    fun stop() = transition {
        active?.let { finish(it, VoiceSpeechResult.CANCELLED) }
        Unit
    }

    fun dispose() = transition {
        if (disposed) return@transition
        disposed = true
        active?.let { finish(it, VoiceSpeechResult.CANCELLED) }
        // Shutdown is still attempted when stop/release failed; no callback may restart us.
        try {
            tts.shutdown()
        } catch (_: Exception) {
            // There is no remaining operation to fail after disposal cleanup.
        } finally {
            tts.listener = null
        }
    }

    private fun <T> transition(action: () -> T): T {
        transitionDepth++
        try {
            return action()
        } finally {
            transitionDepth--
            if (transitionDepth == 0) advance()
        }
    }

    private fun advance() {
        if (advancing || disposed) return
        advancing = true
        try {
            while (!disposed) {
                val operation = active ?: break
                when (initialization) {
                    Initialization.NEW -> initialize()
                    Initialization.PENDING -> break
                    Initialization.FAILED -> fail(operation, checkNotNull(initError))
                    Initialization.READY -> {
                        if (operation.started) break
                        operation.started = true
                        start(operation)
                    }
                }
            }
        } finally {
            advancing = false
        }
    }

    private fun initialize() {
        initialization = Initialization.PENDING
        try {
            tts.initialize { success ->
                transition {
                    if (!disposed && initialization == Initialization.PENDING) {
                        if (success) {
                            initialization = Initialization.READY
                        } else {
                            initialization = Initialization.FAILED
                            initError = DashPigeonError("VOICE_TTS_INIT", "TTS initialization failed")
                        }
                    }
                }
            }
        } catch (error: Exception) {
            initialization = Initialization.FAILED
            initError = platformError("VOICE_TTS_INIT", error)
        }
    }

    private fun start(operation: Operation) {
        try {
            if (operation.languageTag.isBlank() || !tts.setLanguage(operation.languageTag)) {
                fail(operation, DashPigeonError("VOICE_TTS_LANGUAGE", "Requested voice language is unavailable"))
                return
            }
        } catch (error: Exception) {
            fail(operation, platformError("VOICE_TTS_LANGUAGE", error))
            return
        }
        if (active !== operation) return
        if (operation.policy != VoiceMusicPolicy.UNCHANGED) {
            var requestedLease: VoiceFocusLease? = null
            var lostDuringRequest = false
            val lease = try {
                focus.request(operation.policy) {
                    transition {
                        val owned = requestedLease
                        if (owned == null) {
                            lostDuringRequest = true
                        } else if (active === operation && operation.lease === owned) {
                            finish(operation, VoiceSpeechResult.CANCELLED)
                        }
                    }
                }
            } catch (error: Exception) {
                fail(operation, platformError("VOICE_AUDIO_FOCUS", error))
                return
            }
            requestedLease = lease
            if (active !== operation) {
                // Reentrant stop/replacement happened before request returned its resource.
                try {
                    lease?.release()
                } catch (_: Exception) {
                    // Its operation is already terminal; never touch the replacement.
                }
                return
            }
            operation.lease = lease
            if (lostDuringRequest) {
                finish(operation, VoiceSpeechResult.CANCELLED)
                return
            }
            if (lease == null) {
                finish(operation, VoiceSpeechResult.FOCUS_DENIED, stopSpeech = false)
                return
            }
        }
        operation.speaking = true
        try {
            if (!tts.speak(operation.text, operation.volume, operation.id)) {
                fail(operation, DashPigeonError("VOICE_TTS_PLAYBACK", "TTS rejected speech"))
            }
        } catch (error: Exception) {
            fail(operation, platformError("VOICE_TTS_PLAYBACK", error))
        }
    }

    private fun fail(operation: Operation, error: DashPigeonError) =
        finish(operation, VoiceSpeechResult.CANCELLED, error = error)

    private fun finish(
        operation: Operation,
        outcome: VoiceSpeechResult,
        stopSpeech: Boolean = true,
        error: DashPigeonError? = null,
    ) {
        if (operation.terminal) return
        operation.terminal = true
        if (active === operation) active = null
        val lease = operation.lease
        operation.lease = null
        var failure = error
        try {
            if (stopSpeech && operation.speaking) tts.stop()
        } catch (cleanup: Exception) {
            if (failure == null) failure = platformError("VOICE_TTS_PLAYBACK", cleanup)
        }
        try {
            lease?.release()
        } catch (cleanup: Exception) {
            if (failure == null) failure = platformError("VOICE_AUDIO_FOCUS", cleanup)
        }
        if (failure == null) operation.result.complete(outcome)
        else operation.result.completeExceptionally(failure)
    }

    private fun failed(code: String, message: String): Deferred<VoiceSpeechResult> =
        CompletableDeferred<VoiceSpeechResult>().also {
            it.completeExceptionally(DashPigeonError(code, message))
        }

    private fun platformError(code: String, error: Exception) =
        DashPigeonError(code, error.message ?: error.javaClass.simpleName)
}
