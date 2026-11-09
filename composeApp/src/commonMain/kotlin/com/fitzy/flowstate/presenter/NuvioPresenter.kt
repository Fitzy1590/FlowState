package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.SyncLogLine
import com.fitzy.flowstate.sync.SyncResult
import com.fitzy.flowstate.sync.nuvio.*
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NuvioStage { DISCONNECTED, SERVER_FOUND, CHOOSE_PROFILE, CONNECTED }

data class NuvioState(
    val stage: NuvioStage = NuvioStage.DISCONNECTED,
    val serverHost: String = "",
    val profiles: List<NuvioProfile> = emptyList(),
    val profileName: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val log: List<SyncLogLine> = emptyList(),
)

/** Connect to a self-hosted Nuvio server, sign in, pick a profile, sync. Only tokens are stored, never the password. */
class NuvioPresenter(
    private val http: HttpClient,
    private val api: NuvioApi,
    private val store: NuvioAccountStore,
    private val sync: NuvioSync,
    private val repo: FlowRepository,
    private val scope: CoroutineScope,
    private val hasTmdb: () -> Boolean = { true },
    private val onSynced: () -> Unit,
) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<NuvioState> = _state.asStateFlow()
    private var found: NuvioServer? = null
    private var signedIn: NuvioAccount? = null

    fun findServer(address: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            NuvioDiscovery.discover(http, address).fold(
                onSuccess = { s ->
                    found = s
                    _state.update { it.copy(busy = false, stage = NuvioStage.SERVER_FOUND, serverHost = hostOf(s.backendUrl)) }
                },
                onFailure = { e ->
                    val reason = (e as? DiscoveryException)?.failure?.describe() ?: DiscoveryFailure.ConnectionFailed.describe()
                    _state.update { it.copy(busy = false, message = reason) }
                },
            )
        }
    }

    fun signIn(email: String, password: String) {
        val server = found ?: return
        if (_state.value.busy || email.isBlank() || password.isEmpty()) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            val account = api.signIn(server, email.trim(), password)
            if (account == null) { _state.update { it.copy(busy = false, message = "Could not sign in. Check your email and password.") }; return@launch }
            store.save(account) // the API reads the login from here
            signedIn = account
            val profiles = api.profiles()
            when {
                profiles == null -> { store.clear(); _state.update { it.copy(busy = false, message = "Signed in, but the profile list could not be read.") } }
                profiles.size <= 1 -> connect(account, profiles.firstOrNull() ?: NuvioProfile(1, ""))
                else -> _state.update { it.copy(busy = false, stage = NuvioStage.CHOOSE_PROFILE, profiles = profiles) }
            }
        }
    }

    fun chooseProfile(p: NuvioProfile) {
        val account = signedIn ?: store.load() ?: return
        scope.launch { connect(account, p) }
    }

    fun syncNow() {
        if (_state.value.busy || _state.value.stage != NuvioStage.CONNECTED) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch { runSync(quiet = false) }
    }

    /** Quiet sync at app start. */
    fun syncIfConnected() {
        if (_state.value.busy || _state.value.stage != NuvioStage.CONNECTED) return
        _state.update { it.copy(busy = true) }
        scope.launch { runSync(quiet = true) }
    }

    fun logout() {
        store.clear(); found = null; signedIn = null
        _state.value = read().copy(message = "Disconnected. Your library stays on this device.")
    }

    private suspend fun connect(account: NuvioAccount, p: NuvioProfile) {
        store.save(account.copy(profileIndex = p.index, profileName = p.name))
        _state.update { read().copy(busy = true) }
        runSync(quiet = false)
    }

    private suspend fun runSync(quiet: Boolean) {
        val result = sync.sync()
        val msg = when (result) {
            is SyncResult.Done -> if (quiet && result.pushed + result.pulled + result.skipped == 0) null else buildString {
                append("Synced: ${result.pulled} updated here, ${result.pushed} sent to Nuvio.")
                skippedNote(result.skipped, hasTmdb())?.let { append(" ").append(it) }
            }
            SyncResult.NotConnected -> "Not connected to Nuvio."
            SyncResult.Failed -> if (quiet) null else "Sync failed. Nothing was changed. If this keeps happening, sign in again."
        }
        _state.value = read().copy(message = msg)
        if (result is SyncResult.Done && result.pulled > 0) onSynced()
    }

    private fun read(): NuvioState {
        val a = store.load()
        return if (a == null) NuvioState(log = repo.recentSyncEvents().filter { it.service == "nuvio" })
        else NuvioState(NuvioStage.CONNECTED, hostOf(a.backendUrl), profileName = a.profileName, log = repo.recentSyncEvents().filter { it.service == "nuvio" })
    }

    private fun hostOf(url: String) = runCatching { Url(url).host }.getOrDefault(url)
}
