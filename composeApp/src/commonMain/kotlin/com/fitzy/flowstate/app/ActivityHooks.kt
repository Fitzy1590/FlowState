package com.fitzy.flowstate.app

import com.fitzy.flowstate.voice.NoVoiceInput
import com.fitzy.flowstate.voice.VoiceInput

/**
 * Things that need a visible Android activity (microphone and notification permission prompts).
 * The activity fills these in while it is running; the shared layer calls them through here.
 */
class ActivityHooks {
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
