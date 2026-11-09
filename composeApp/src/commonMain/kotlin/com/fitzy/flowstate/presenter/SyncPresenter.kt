package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.SyncLogLine
import com.fitzy.flowstate.secrets.SecretNames
import com.fitzy.flowstate.secrets.SecretStore
import com.fitzy.flowstate.sync.StremioApi
import com.fitzy.flowstate.sync.StremioSync
import com.fitzy.flowstate.sync.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val log: List<SyncLogLine> = emptyList(),
)

/** Stremio connection and sync, shown in Settings. Only the session key is stored, never the password. */
class SyncPresenter(
    private val secrets: SecretStore,
    private val api: StremioApi,
    private val sync: StremioSync,
    private val repo: FlowRepository,
    private val scope: CoroutineScope,
    private val onSynced: () -> Unit,
) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank() || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            val key = api.login(email.trim(), password)
            if (key == null) {
                _state.update { it.copy(busy = false, message = "Could not sign in. Check your email and password.") }
                return@launch
            }
            secrets.put(SecretNames.STREMIO_AUTH, key)
            _state.update { read().copy(busy = true) }
            runSync()
        }
    }

    fun logout() {
        secrets.remove(SecretNames.STREMIO_AUTH)
        _state.value = read().copy(message = "Disconnected. Your library stays on this device.")
    }

    fun syncNow() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch { runSync() }
    }

    /** Silent sync at app start. Shows nothing unless it changed something. */
    fun syncIfConnected() {
        if (!_state.value.connected || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        scope.launch { runSync(quiet = true) }
    }

    private suspend fun runSync(quiet: Boolean = false) {
        val result = sync.sync()
        val msg = when (result) {
            is SyncResult.Done -> if (quiet && result.pushed + result.pulled == 0) null else "Synced: ${result.pulled} updated here, ${result.pushed} sent to Stremio."
            SyncResult.NotConnected -> "Not connected to Stremio."
            SyncResult.Failed -> if (quiet) null else "Sync failed. Nothing was changed. Try again later."
        }
        _state.value = read().copy(message = msg)
        if (result is SyncResult.Done && result.pulled > 0) onSynced()
    }

    private fun read() = SyncState(
        connected = secrets.get(SecretNames.STREMIO_AUTH) != null,
        log = repo.recentSyncEvents(),
    )
}
