package com.fitzy.flowstate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.fitzy.flowstate.voice.VoiceInput

/** Uses the device's speech recognizer. Asks for the microphone permission the first time. Must be created before the activity starts. */
class AndroidVoiceInput(private val activity: ComponentActivity) : VoiceInput {
    private class Callbacks(val partial: (String) -> Unit, val result: (String) -> Unit, val error: (String) -> Unit)

    private var recognizer: SpeechRecognizer? = null
    private var pending: Callbacks? = null

    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cb = pending ?: return@registerForActivityResult
        if (granted) listen(cb) else { pending = null; cb.error("Microphone permission was denied.") }
    }

    override val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(activity)

    override fun start(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val cb = Callbacks(onPartial, onResult, onError)
        pending = cb
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen(cb)
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    private fun listen(cb: Callbacks) {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) { text(b)?.let(cb.partial) }
            override fun onResults(b: Bundle?) {
                val t = text(b)
                cleanup()
                if (t.isNullOrBlank()) cb.error("Nothing was heard. Try again.") else cb.result(t)
            }
            override fun onError(code: Int) { cleanup(); cb.error(describe(code)) }
            override fun onReadyForSpeech(p: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(v: Float) = Unit
            override fun onBufferReceived(b: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(t: Int, b: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
        )
    }

    private fun cleanup() { recognizer?.destroy(); recognizer = null; pending = null }

    private fun text(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nothing was heard. Try again."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Try again."
        else -> "Speech recognition failed (code $code)."
    }
}
