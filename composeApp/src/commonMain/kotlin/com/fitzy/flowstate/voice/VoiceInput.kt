package com.fitzy.flowstate.voice

/** Speech to text for the search box. */
interface VoiceInput {
    /** False where the platform cannot do speech recognition (Windows desktop for now). */
    val available: Boolean

    /** Starts listening. [onPartial] gets live text, [onResult] the final text, [onError] a short reason to show. */
    fun start(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit)

    fun stop()
}

object NoVoiceInput : VoiceInput {
    override val available = false
    override fun start(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) =
        onError("Voice input is not available on this device.")
    override fun stop() = Unit
}
