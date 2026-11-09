package com.fitzy.flowstate.app

import com.fitzy.flowstate.voice.NoVoiceInput
import com.fitzy.flowstate.voice.VoiceInput

/**
 * Things that need a visible Android activity (microphone and notification permission prompts).
 * The activity fills these in while it is running; the shared layer calls them through here.
 */
/** Saving and opening a text file through the platform's file picker. */
interface FileTransfer {
    val available: Boolean
    /** Asks where to save, writes [text], then reports whether it worked. */
    fun save(suggestedName: String, text: String, onDone: (Boolean) -> Unit)
    /** Asks for a file and returns its text, or null if cancelled or unreadable. */
    fun open(onText: (String?) -> Unit)
}

object NoFileTransfer : FileTransfer {
    override val available = false
    override fun save(suggestedName: String, text: String, onDone: (Boolean) -> Unit) = onDone(false)
    override fun open(onText: (String?) -> Unit) = onText(null)
}

class ActivityHooks {
    var files: FileTransfer = NoFileTransfer
    var voice: VoiceInput = NoVoiceInput

    /** Asks for permission to show notifications and reports the answer. Platforms that need none say yes. */
    var requestNotificationPermission: ((Boolean) -> Unit) -> Unit = { reply -> reply(true) }
}

/** Forwards to whatever voice input the activity has set. */
class HookedVoice(private val hooks: ActivityHooks) : VoiceInput {
    override val available get() = hooks.voice.available
    override fun start(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) =
        hooks.voice.start(onPartial, onResult, onError)
    override fun stop() = hooks.voice.stop()
}
