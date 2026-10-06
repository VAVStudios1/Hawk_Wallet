package com.example.hawk_wallet.ui.screens.update

import com.example.hawk_wallet.data.repository.AppUpdate
import java.io.File

/** Whether the rest of the app may be used. */
enum class UpdateGate {
    /** First check after launch still running (bounded by a short timeout). */
    CHECKING,
    /** Normal use; an optional update may be offered. */
    OPEN,
    /** The backend requires an update: nothing else is reachable. */
    FORCED
}

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    /** [percent] is null while the size is unknown. */
    data class Downloading(val percent: Int?) : UpdateDownloadState
    data object Verifying : UpdateDownloadState
    /** Verified and ready for the Package Installer. */
    data class Ready(val file: File) : UpdateDownloadState
    data class Failed(val message: String) : UpdateDownloadState
}

data class AppUpdateUiState(
    val gate: UpdateGate = UpdateGate.CHECKING,
    val installedVersion: String = "",
    /** The release offered by the backend; null when none (or the check failed while forced). */
    val update: AppUpdate? = null,
    val showOptionalPrompt: Boolean = false,
    val download: UpdateDownloadState = UpdateDownloadState.Idle,
    /** "Install unknown apps" is off for Hawk Wallet. */
    val needsInstallPermission: Boolean = false,
    /** Shown on the Force Update screen when the update details could not be loaded. */
    val checkError: String? = null,
    val isChecking: Boolean = false
) {
    val isBusy: Boolean
        get() = download is UpdateDownloadState.Downloading || download is UpdateDownloadState.Verifying
}
