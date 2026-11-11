package com.rs.ocportable

import android.app.Activity
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle

/**
 * Hold-to-talk using the phone's own recognizer. It streams partial words into the composer and
 * never auto-sends. The watch cannot use this class: `SpeechRecognizer` only listens to the local
 * microphone, which is why watch audio needs the bundled model.
 */
class VoiceInput(private val activity: Activity) : RecognitionListener {

    interface Callback {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null
    private var listening = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(activity)

    fun start(callback: Callback): Boolean {
        if (listening) return true
        if (!isAvailable()) {
            callback.onError("Speech recognition is not available on this device.")
            return false
        }
        this.callback = callback
        val speech = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = speech
        speech.setRecognitionListener(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        }
        return try {
            speech.startListening(intent)
            listening = true
            true
        } catch (e: Exception) {
            callback.onError(e.message ?: "Could not start speech recognition.")
            destroy()
            false
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        recognizer?.stopListening()
    }

    fun destroy() {
        listening = false
        callback = null
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        listening = false
        callback?.onError(messageFor(error))
        destroy()
    }

    override fun onResults(results: Bundle?) {
        listening = false
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) callback?.onFinal(text)
        destroy()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) callback?.onPartial(text)
    }

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognition needs a network connection."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "Did not catch that."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
        else -> "Speech recognition failed ($error)."
    }
}
