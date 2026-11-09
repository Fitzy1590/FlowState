package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.update.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateState(
    val currentVersion: String,
    val available: UpdateInfo? = null,
    /** True while the "Update now / Later / Skip this version" dialog should show. */
    val prompt: Boolean = false,
    val checking: Boolean = false,
    /** 0..1 while downloading, otherwise null. */
    val progress: Float? = null,
    val message: String? = null,
)

/** SPEC 5: silent check at launch (at most every 6 hours, honouring a skipped version) and a manual check in Settings. */
class UpdatePresenter(
    private val checker: UpdateChecker,
    private val installer: UpdateInstaller,
    private val repo: FlowRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    currentVersion: String,
) {
    private val _state = MutableStateFlow(UpdateState(currentVersion))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun checkOnLaunch() {
        val last = repo.getSetting(LAST_CHECK)?.toLongOrNull() ?: 0L
        if (clock() - last < THROTTLE_MS) return
        run(manual = false)
    }

    fun checkNow() = run(manual = true)

    /** Hides the dialog for now; the update stays available in Settings. */
    fun later() = _state.update { it.copy(prompt = false) }

    fun skipVersion() {
        _state.value.available?.let { repo.putSetting(SKIPPED, it.version) }
        _state.update { it.copy(prompt = false) }
    }

    fun install() {
        val info = _state.value.available ?: return
        if (_state.value.progress != null) return
        _state.update { it.copy(progress = 0f, message = null, prompt = false) }
        scope.launch {
            val path = installer.downloadAndVerify(info) { p -> _state.update { it.copy(progress = p) } }
            if (path == null) {
                _state.update { it.copy(progress = null, message = "The download failed or did not match its checksum. Nothing was installed.") }
                return@launch
            }
            val msg = when (installer.install(path)) {
                InstallResult.STARTED -> null
                InstallResult.NEEDS_PERMISSION -> "Allow FlowState to install apps in the settings screen that just opened, then tap Update again."
                InstallResult.FAILED -> "The installer could not be started."
            }
            _state.update { it.copy(progress = null, message = msg) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun run(manual: Boolean) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, message = null) }
        scope.launch {
            val result = checker.check()
            if (result !is CheckResult.Failed) repo.putSetting(LAST_CHECK, clock().toString())
            _state.update { s ->
                when (result) {
                    is CheckResult.Available -> {
                        val skipped = repo.getSetting(SKIPPED) == result.info.version
                        s.copy(
                            checking = false, available = result.info,
                            prompt = manual || !skipped, // a skipped version only shows again on a manual check
                            message = if (manual) null else s.message,
                        )
                    }
                    CheckResult.UpToDate -> s.copy(checking = false, available = null, prompt = false,
                        message = if (manual) "You're up to date (version ${s.currentVersion})." else null)
                    CheckResult.Failed -> s.copy(checking = false, message = if (manual) "Could not check for updates. Try again later." else null)
                }
            }
        }
    }

    private companion object {
        const val LAST_CHECK = "update.lastCheckAt"
        const val SKIPPED = "update.skippedVersion"
        const val THROTTLE_MS = 6L * 60 * 60 * 1000
    }
}
