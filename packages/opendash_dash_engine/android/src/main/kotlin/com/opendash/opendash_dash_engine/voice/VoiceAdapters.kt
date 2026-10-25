package com.opendash.opendash_dash_engine.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.opendash.opendash_dash_engine.VoiceMusicPolicy
import java.util.Locale

internal interface VoiceTtsListener {
    fun done(id: String)
    fun stopped(id: String)
    fun error(id: String, errorCode: Int?)
}

internal interface VoiceTtsAdapter {
    var listener: VoiceTtsListener?
    fun initialize(onResult: (Boolean) -> Unit)
    fun setLanguage(languageTag: String): Boolean
    fun speak(text: String, volume: Float, utteranceId: String): Boolean
    fun stop()
    fun shutdown()
}

internal fun interface VoiceFocusLease {
    fun release()
}

internal interface VoiceFocusAdapter {
    fun request(policy: VoiceMusicPolicy, onLost: () -> Unit): VoiceFocusLease?
}

internal class AndroidVoiceTtsAdapter(context: Context) : VoiceTtsAdapter {
    private enum class State { NEW, INITIALIZING, READY, FAILED, CLOSED }

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var state = State.NEW
    private var tts: TextToSpeech? = null
    private var initializationResult: ((Boolean) -> Unit)? = null
    private var currentUtterance: String? = null
    override var listener: VoiceTtsListener? = null

    override fun initialize(onResult: (Boolean) -> Unit) {
        when (state) {
            State.READY -> {
                onResult(true)
                return
            }
            State.FAILED, State.CLOSED -> {
                onResult(false)
                return
            }
            State.INITIALIZING -> error("Voice TTS initialization is already pending")
            State.NEW -> Unit
        }
        state = State.INITIALIZING
        initializationResult = onResult
        try {
            tts = TextToSpeech(context) { status ->
                // Even a synchronous constructor callback must wait for the field assignment.
                handler.post { voiceCallback { finishInitialization(status) } }
            }
        } catch (error: Exception) {
            state = State.FAILED
            initializationResult = null
            throw error
        }
    }

    private fun finishInitialization(status: Int) {
        if (state != State.INITIALIZING) return
        val engine = tts
        val ready = if (status == TextToSpeech.SUCCESS && engine != null) {
            try {
                engine.setAudioAttributes(voiceAudioAttributes) == TextToSpeech.SUCCESS &&
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) = terminal(utteranceId) { target, id ->
                            target.done(id)
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) =
                            terminal(utteranceId) { target, id -> target.stopped(id) }

                        @Deprecated("Legacy Android TTS error callback")
                        override fun onError(utteranceId: String?) = terminal(utteranceId) { target, id ->
                            target.error(id, null)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) =
                            terminal(utteranceId) { target, id -> target.error(id, errorCode) }
                    }) == TextToSpeech.SUCCESS
            } catch (error: Exception) {
                Log.e("VoiceOutput", "TTS initialization failed", error)
                false
            }
        } else {
            false
        }
        state = if (ready) State.READY else State.FAILED
        val callback = initializationResult
        initializationResult = null
        if (!ready) {
            tts = null
            // A failed setup is terminal, but cleanup failure must not lose its result.
            voiceCallback { engine?.shutdown() }
        }
        callback?.invoke(ready)
    }

    private fun terminal(id: String?, notify: (VoiceTtsListener, String) -> Unit) {
        if (id == null) return
        handler.dispatchVoiceCallback {
            if (state != State.CLOSED) {
                if (currentUtterance == id) currentUtterance = null
                listener?.let { notify(it, id) }
            }
        }
    }

    override fun setLanguage(languageTag: String): Boolean {
        val engine = tts ?: return false
        if (state != State.READY) return false
        val locale = Locale.forLanguageTag(languageTag)
        if (locale.language.isEmpty()) return false
        return engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    override fun speak(text: String, volume: Float, utteranceId: String): Boolean {
        val engine = tts ?: return false
        if (state != State.READY) return false
        val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        currentUtterance = utteranceId
        // Keep the ID on failure: owner cleanup must still stop a partially submitted utterance.
        return engine.speak(text, TextToSpeech.QUEUE_FLUSH, parameters, utteranceId) ==
            TextToSpeech.SUCCESS
    }

    override fun stop() {
        if (currentUtterance == null) return
        currentUtterance = null
        check(tts?.stop() != TextToSpeech.ERROR) { "TTS stop failed" }
    }

    override fun shutdown() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        initializationResult = null
        currentUtterance = null
        listener = null
        val engine = tts
        tts = null
        engine?.shutdown()
    }
}

internal class AndroidVoiceFocusAdapter(context: Context) : VoiceFocusAdapter {
    private val context = context.applicationContext
    private val audio by lazy { this.context.getSystemService(AudioManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    override fun request(policy: VoiceMusicPolicy, onLost: () -> Unit): VoiceFocusLease? {
        val gain = when (policy) {
            VoiceMusicPolicy.DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            VoiceMusicPolicy.PAUSE_AND_RESUME -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            VoiceMusicPolicy.UNCHANGED -> return VoiceFocusLease { }
        }
        val manager = audio ?: return null
        var released = false
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(voiceAudioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> handler.dispatchVoiceCallback {
                        if (!released) onLost()
                    }
                }
            }, handler)
            .build()
        val result = try {
            manager.requestAudioFocus(request)
        } catch (error: Exception) {
            released = true
            // A framework exception may follow partial acquisition of this exact request.
            try {
                manager.abandonAudioFocusRequest(request)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            released = true
            // AudioManager registers the listener before asking the service, even on denial.
            manager.abandonAudioFocusRequest(request)
            return null
        }
        return VoiceFocusLease {
            if (!released) {
                released = true
                manager.abandonAudioFocusRequest(request)
            }
        }
    }
}

private val voiceAudioAttributes: AudioAttributes by lazy {
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

private fun Handler.dispatchVoiceCallback(block: () -> Unit) {
    if (Looper.myLooper() == looper) {
        voiceCallback(block)
    } else {
        post { voiceCallback(block) }
    }
}

private inline fun voiceCallback(block: () -> Unit) {
    try {
        block()
    } catch (error: Exception) {
        // Binder/platform callbacks must not crash the main looper if client cleanup fails.
        Log.e("VoiceOutput", "Voice callback failed", error)
    }
}
