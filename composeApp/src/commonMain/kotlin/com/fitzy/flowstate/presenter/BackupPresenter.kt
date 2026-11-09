package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.app.ActivityHooks
import com.fitzy.flowstate.backup.BackupService
import com.fitzy.flowstate.backup.ImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackupState(val available: Boolean = false, val message: String? = null)

class BackupPresenter(
    private val service: BackupService,
    private val hooks: ActivityHooks,
    private val clock: () -> Long,
    private val onImported: () -> Unit,
) {
    private val _state = MutableStateFlow(BackupState(hooks.files.available))
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /** The file picker may be attached after startup, so the screen asks again when it appears. */
    fun refresh() { _state.value = _state.value.copy(available = hooks.files.available) }

    fun export() {
        hooks.files.save("flowstate-backup-${clock()}.json", service.export()) { ok ->
            _state.value = _state.value.copy(message = if (ok) "Backup saved." else "The backup was not saved.")
        }
    }

    fun import() {
        hooks.files.open { text ->
            _state.value = _state.value.copy(message = when {
                text == null -> "No file was opened."
                else -> when (val r = service.import(text)) {
                    is ImportResult.Done -> { onImported(); "Restored ${r.library} titles, ${r.ratings} scores and ${r.votes} votes." }
                    is ImportResult.Invalid -> r.reason
                }
            })
        }
    }
}
