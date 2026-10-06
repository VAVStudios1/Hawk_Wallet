package com.example.hawk_wallet.ui.screens.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.AppCompatibility
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.ApkResult
import com.example.hawk_wallet.data.repository.AppUpdate
import com.example.hawk_wallet.data.repository.AppUpdateRepository
import com.example.hawk_wallet.data.repository.UpdateCheckResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity-scoped owner of the update flow: the startup and foreground
 * checks, the optional prompt, and download -> verify -> install.
 *
 * The backend alone decides whether an update is optional or required. A
 * failed check never blocks the app - unless an update is already known to
 * be required, which only a successful check can lift. There is no bypass
 * of a required update, and a cancelled install leaves it required.
 */
class AppUpdateViewModel(private val repository: AppUpdateRepository) : ViewModel() {

    sealed interface Event {
        data class LaunchInstaller(val intent: Intent) : Event
        data class OpenInstallPermissionSettings(val intent: Intent) : Event
    }

    private val _uiState = MutableStateFlow(AppUpdateUiState(installedVersion = repository.installedVersionName))
    val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** "Later" was chosen for this version; not offered again until the app restarts. */
    private var dismissedVersion: String? = null

    init {
        viewModelScope.launch {
            // An APK left from an earlier run is either installed by now or stale.
            repository.clearDownloads()
        }
        check()
        viewModelScope.launch {
            // A slow or unreachable server must not hold the app on the splash.
            delay(STARTUP_CHECK_TIMEOUT_MS)
            _uiState.update { if (it.gate == UpdateGate.CHECKING) it.copy(gate = UpdateGate.OPEN) else it }
        }
        viewModelScope.launch {
            // An API call answered 426 app_update_required.
            AppCompatibility.updateRequired.collect {
                _uiState.update { it.copy(gate = UpdateGate.FORCED, showOptionalPrompt = false) }
                check()
            }
        }
    }

    /** The app came to the foreground (also after the installer or settings closed). */
    fun onForeground() = check()

    fun retryCheck() = check()

    private fun check() {
        if (checkJob?.isActive == true || _uiState.value.isBusy) return
        _uiState.update { it.copy(isChecking = true) }
        checkJob = viewModelScope.launch {
            when (val result = repository.checkForUpdate()) {
                is ApiResult.Success -> applyCheck(result.data)
                is ApiResult.Error -> _uiState.update { state ->
                    when (state.gate) {
                        // Never blocks: a failed first check opens the app.
                        UpdateGate.CHECKING -> state.copy(gate = UpdateGate.OPEN, isChecking = false)
                        UpdateGate.FORCED -> state.copy(checkError = result.message, isChecking = false)
                        UpdateGate.OPEN -> state.copy(isChecking = false)
                    }
                }
            }
        }
    }

    private fun applyCheck(result: UpdateCheckResult) {
        _uiState.update { state ->
            when (result) {
                UpdateCheckResult.UpToDate -> state.copy(
                    gate = UpdateGate.OPEN,
                    update = null,
                    showOptionalPrompt = false,
                    download = UpdateDownloadState.Idle,
                    needsInstallPermission = false,
                    checkError = null,
                    isChecking = false
                )
                UpdateCheckResult.RequiredButUnavailable -> state.copy(
                    gate = UpdateGate.FORCED,
                    update = null,
                    showOptionalPrompt = false,
                    checkError = "An update is required but is not available yet. Please try again later.",
                    isChecking = false
                )
                is UpdateCheckResult.Available -> {
                    val update = result.update
                    val sameRelease = state.update?.apkSha256 == update.apkSha256
                    state.copy(
                        gate = if (update.forceUpdate) UpdateGate.FORCED else UpdateGate.OPEN,
                        update = update,
                        showOptionalPrompt = !update.forceUpdate &&
                            (state.showOptionalPrompt || update.latestVersion != dismissedVersion),
                        // A different release replaces whatever was downloaded.
                        download = if (sameRelease) state.download else UpdateDownloadState.Idle,
                        checkError = null,
                        isChecking = false
                    )
                }
            }
        }
    }

    /** Optional prompt: "Later". Cancels a running download. */
    fun dismissOptional() {
        if (_uiState.value.gate == UpdateGate.FORCED) return
        dismissedVersion = _uiState.value.update?.latestVersion
        downloadJob?.cancel()
        _uiState.update {
            it.copy(showOptionalPrompt = false, download = UpdateDownloadState.Idle, needsInstallPermission = false)
        }
    }

    /** "Update Now" / "Install" / "Try again": downloads if needed, then installs. */
    fun startUpdate() {
        val state = _uiState.value
        val update = state.update ?: return
        if (state.isBusy) return
        val ready = state.download as? UpdateDownloadState.Ready
        if (ready != null) {
            requestInstall(ready.file, update)
            return
        }
        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(download = UpdateDownloadState.Downloading(null)) }
            val result = repository.downloadApk(update) { percent ->
                _uiState.update {
                    if (it.download is UpdateDownloadState.Downloading) {
                        it.copy(download = UpdateDownloadState.Downloading(percent))
                    } else {
                        it
                    }
                }
            }
            when (result) {
                is ApkResult.Verified -> {
                    _uiState.update { it.copy(download = UpdateDownloadState.Ready(result.file)) }
                    requestInstall(result.file, update)
                }
                is ApkResult.Failed -> _uiState.update { it.copy(download = UpdateDownloadState.Failed(result.message)) }
            }
        }
    }

    private fun requestInstall(file: File, update: AppUpdate) {
        if (!repository.canRequestPackageInstalls()) {
            _uiState.update { it.copy(needsInstallPermission = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(needsInstallPermission = false, download = UpdateDownloadState.Verifying) }
            when (val result = repository.verifyDownloaded(file, update)) {
                is ApkResult.Verified -> {
                    _uiState.update { it.copy(download = UpdateDownloadState.Ready(result.file)) }
                    _events.tryEmit(Event.LaunchInstaller(repository.installerIntent(result.file)))
                }
                is ApkResult.Failed -> _uiState.update { it.copy(download = UpdateDownloadState.Failed(result.message)) }
            }
        }
    }

    /** "Allow installs": opens this app's "Install unknown apps" setting. */
    fun openInstallPermissionSettings() {
        _events.tryEmit(Event.OpenInstallPermissionSettings(repository.installPermissionSettingsIntent()))
    }

    /** Back from the "Install unknown apps" setting: continue if it was allowed. */
    fun onInstallPermissionSettingsClosed() {
        if (!repository.canRequestPackageInstalls()) return
        val state = _uiState.value
        val ready = state.download as? UpdateDownloadState.Ready
        val update = state.update
        if (ready != null && update != null) requestInstall(ready.file, update)
        else _uiState.update { it.copy(needsInstallPermission = false) }
    }

    /**
     * Back from the Package Installer while this process is still alive:
     * the install was cancelled or failed (a successful one restarts the
     * app). A required update stays required; re-check with the backend.
     */
    fun onInstallerClosed() = check()

    private companion object {
        const val STARTUP_CHECK_TIMEOUT_MS = 8_000L
    }
}
