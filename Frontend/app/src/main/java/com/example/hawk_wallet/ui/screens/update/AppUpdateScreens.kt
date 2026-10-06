package com.example.hawk_wallet.ui.screens.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.hawk_wallet.data.repository.AppUpdate
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBrandMark
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import java.util.Locale

/**
 * Runs the update view model's side effects: opening the Package Installer
 * and the "Install unknown apps" setting, and reporting back when they close.
 * Must stay composed whatever the app is showing.
 */
@Composable
fun AppUpdateEffects(viewModel: AppUpdateViewModel) {
    val installerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onInstallerClosed() }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onInstallPermissionSettingsClosed() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AppUpdateViewModel.Event.LaunchInstaller -> installerLauncher.launch(event.intent)
                is AppUpdateViewModel.Event.OpenInstallPermissionSettings -> settingsLauncher.launch(event.intent)
            }
        }
    }
}

/** Replaces the whole app while the backend requires an update. No way past it but updating. */
@Composable
fun ForceUpdateScreen(
    state: AppUpdateUiState,
    onUpdate: () -> Unit,
    onAllowInstalls: () -> Unit,
    onRetryCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HawkBrandMark(badgeSize = 64.dp)
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    UpdateIcon()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Update required",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This version of Hawk Wallet is no longer supported. Update to continue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    val update = state.update
                    if (update == null) {
                        state.checkError?.let {
                            AuthErrorBanner(message = it)
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        PrimaryButton(
                            text = if (state.isChecking) "Checking…" else "Try again",
                            enabled = !state.isChecking,
                            onClick = onRetryCheck
                        )
                    } else {
                        UpdateDetails(installedVersion = state.installedVersion, update = update)
                        Spacer(modifier = Modifier.height(20.dp))
                        UpdateProgress(state)
                        val action = primaryAction(state, onUpdate, onAllowInstalls)
                        PrimaryButton(text = action.label, enabled = action.enabled, onClick = action.onClick)
                    }
                }
            }
        }
    }
}

/** Offered over the app when a newer, optional release exists. */
@Composable
fun OptionalUpdateDialog(
    state: AppUpdateUiState,
    onUpdate: () -> Unit,
    onAllowInstalls: () -> Unit,
    onLater: () -> Unit
) {
    val update = state.update ?: return
    val action = primaryAction(state, onUpdate, onAllowInstalls)
    AlertDialog(
        onDismissRequest = { if (!state.isBusy) onLater() },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { UpdateIcon() },
        title = { Text("Update available", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                UpdateDetails(installedVersion = state.installedVersion, update = update)
                Spacer(modifier = Modifier.height(16.dp))
                UpdateProgress(state)
            }
        },
        confirmButton = {
            TextButton(onClick = action.onClick, enabled = action.enabled) {
                Text(action.label, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, enabled = !state.isBusy) { Text("Later") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

private data class UpdateAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

private fun primaryAction(
    state: AppUpdateUiState,
    onUpdate: () -> Unit,
    onAllowInstalls: () -> Unit
): UpdateAction = when {
    state.needsInstallPermission -> UpdateAction("Allow installs", true, onAllowInstalls)
    else -> when (state.download) {
        is UpdateDownloadState.Downloading -> UpdateAction("Downloading…", false, onUpdate)
        UpdateDownloadState.Verifying -> UpdateAction("Verifying…", false, onUpdate)
        is UpdateDownloadState.Ready -> UpdateAction("Install", true, onUpdate)
        is UpdateDownloadState.Failed -> UpdateAction("Try again", true, onUpdate)
        UpdateDownloadState.Idle -> UpdateAction("Update Now", true, onUpdate)
    }
}

@Composable
private fun UpdateDetails(installedVersion: String, update: AppUpdate) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DetailRow(label = "Installed version", value = installedVersion.ifBlank { "-" })
        DetailRow(label = "New version", value = update.latestVersion)
        update.apkSize?.let { DetailRow(label = "Download size", value = formatSize(it)) }
        if (update.releaseNotes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "What's new",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            update.releaseNotes.forEach { note ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("•", color = HawkIndigo, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Progress, the install-permission explanation, or the last download error. */
@Composable
private fun UpdateProgress(state: AppUpdateUiState) {
    when {
        state.needsInstallPermission -> {
            Text(
                text = "To install the update, allow Hawk Wallet to install apps: tap \"Allow installs\", " +
                    "turn on \"Allow from this source\", then come back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        else -> when (val download = state.download) {
            is UpdateDownloadState.Downloading -> {
                val percent = download.percent
                if (percent == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (percent == null) "Downloading…" else "Downloading… $percent%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            UpdateDownloadState.Verifying -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Verifying the download…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            is UpdateDownloadState.Ready -> {
                Text(
                    text = "Download verified. Tap Install and confirm in the Android installer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            is UpdateDownloadState.Failed -> {
                AuthErrorBanner(message = download.message)
                Spacer(modifier = Modifier.height(16.dp))
            }
            UpdateDownloadState.Idle -> Unit
        }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun UpdateIcon() {
    Icon(
        imageVector = Icons.Filled.SystemUpdate,
        contentDescription = null,
        tint = HawkIndigo,
        modifier = Modifier
            .size(64.dp)
            .background(HawkIndigo.copy(alpha = 0.08f), CircleShape)
            .padding(16.dp)
    )
}

/** Display only. */
private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

@Preview(showBackground = true)
@Composable
private fun ForceUpdateScreenPreview() {
    Hawk_WalletTheme {
        ForceUpdateScreen(
            state = AppUpdateUiState(
                gate = UpdateGate.FORCED,
                installedVersion = "1.0.5",
                update = AppUpdate(
                    latestVersion = "1.0.7",
                    minimumSupportedVersion = "1.0.6",
                    forceUpdate = true,
                    apkUrl = "https://example.com/app.apk",
                    apkSha256 = "0".repeat(64),
                    apkSize = 12_345_678,
                    releaseNotes = listOf("Bug fixes", "Security improvements")
                ),
                download = UpdateDownloadState.Downloading(42)
            ),
            onUpdate = {},
            onAllowInstalls = {},
            onRetryCheck = {}
        )
    }
}
