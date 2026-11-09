package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.secrets.SecretNames
import com.fitzy.flowstate.secrets.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only whether a key is set. The key values are never held in state. */
data class SettingsState(
    val tmdbSet: Boolean = false,
    val claudeSet: Boolean = false,
    val youtubeSet: Boolean = false,
)

class SettingsPresenter(private val secrets: SecretStore, private val onChanged: () -> Unit) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Returns false and stores nothing when [value] is blank or [name] is not a known secret. */
    fun save(name: String, value: String): Boolean {
        val v = value.trim()
        if (name !in KNOWN || v.isEmpty()) return false
        secrets.put(name, v)
        _state.value = read()
        onChanged()
        return true
    }

    fun clear(name: String) {
        if (name !in KNOWN) return
        secrets.remove(name)
        _state.value = read()
        onChanged()
    }

    private fun read() = SettingsState(
        tmdbSet = secrets.get(SecretNames.TMDB) != null,
        claudeSet = secrets.get(SecretNames.CLAUDE) != null,
        youtubeSet = secrets.get(SecretNames.YOUTUBE) != null,
    )

    private companion object {
        val KNOWN = setOf(SecretNames.TMDB, SecretNames.CLAUDE, SecretNames.YOUTUBE)
    }
}
