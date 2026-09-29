package com.example.hawk_wallet.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.HawkBottomNavigation
import com.example.hawk_wallet.ui.components.HawkNavDestination
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** Where each Profile row leads; all are existing screens except the two profile sub-screens. */
data class ProfileActions(
    val onBackClick: () -> Unit = {},
    val onPersonalInfoClick: () -> Unit = {},
    val onPaymentMethodsClick: () -> Unit = {},
    val onOrdersClick: () -> Unit = {},
    val onTeamClick: () -> Unit = {},
    val onChangePasswordClick: () -> Unit = {},
    val onLoggedOut: () -> Unit = {},
    val onBottomNavSelect: (HawkNavDestination) -> Unit = {}
)

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    actions: ProfileActions,
    /** One-shot message handed back by a sub-screen (e.g. after a password change). */
    pendingMessage: String? = null,
    onPendingMessageShown: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Reloads each time the screen opens (e.g. back from Personal Information).
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(uiState.loggedOut) {
        if (uiState.loggedOut) actions.onLoggedOut()
    }

    LaunchedEffect(pendingMessage) {
        if (pendingMessage != null) {
            snackbarHostState.showSnackbar(pendingMessage)
            onPendingMessageShown()
        }
    }

    ProfileContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onLogoutConfirmed = viewModel::logout,
        actions = actions
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileContent(
    uiState: ProfileUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onLogoutConfirmed: () -> Unit,
    actions: ProfileActions
) {
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Profile", onBackClick = actions.onBackClick) },
        bottomBar = { HawkBottomNavigation(selected = HawkNavDestination.PROFILE, onSelect = actions.onBottomNavSelect) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading && uiState.profile != null,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Every section stays usable (including Logout) even if the profile fails to load.
            val section = Modifier.padding(horizontal = ScreenHorizontalPadding)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    ProfileHeaderCard(
                        profile = uiState.profile,
                        isLoading = uiState.isLoading,
                        errorMessage = uiState.errorMessage,
                        onRetry = onRefresh,
                        modifier = section
                    )
                }

                item {
                    ProfileSection(title = "Account", modifier = section) {
                        ProfileOptionRow(
                            icon = Icons.Outlined.Person,
                            title = "Personal Information",
                            onClick = actions.onPersonalInfoClick
                        )
                        RowDivider()
                        ProfileOptionRow(
                            icon = Icons.Outlined.AccountBalanceWallet,
                            title = "Payment Methods",
                            onClick = actions.onPaymentMethodsClick
                        )
                    }
                }

                item {
                    ProfileSection(title = "Activity", modifier = section) {
                        ProfileOptionRow(
                            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                            title = "My Orders",
                            onClick = actions.onOrdersClick
                        )
                        RowDivider()
                        ProfileOptionRow(
                            icon = Icons.Outlined.Groups,
                            title = "Team",
                            onClick = actions.onTeamClick
                        )
                    }
                }

                item {
                    ProfileSection(title = "Security", modifier = section) {
                        ProfileOptionRow(
                            icon = Icons.Outlined.Lock,
                            title = "Change Password",
                            onClick = actions.onChangePasswordClick
                        )
                    }
                }

                item {
                    ProfileSection(title = "Session", modifier = section) {
                        ProfileOptionRow(
                            icon = Icons.AutoMirrored.Outlined.Logout,
                            title = "Log Out",
                            tint = HawkCoral,
                            titleColor = HawkCoral,
                            enabled = !uiState.isLoggingOut,
                            onClick = { showLogoutDialog = true },
                            trailing = {
                                if (uiState.isLoggingOut) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showLogoutDialog) {
        LogoutDialog(
            onConfirm = {
                showLogoutDialog = false
                onLogoutConfirmed()
            },
            onDismiss = { showLogoutDialog = false }
        )
    }
}

@Composable
private fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Out") },
        text = { Text("Are you sure you want to log out?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Log Out", color = HawkCoral) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// Previews use placeholder values only; the real screen shows nothing but backend data.

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun ProfileScreenPreview() {
    Hawk_WalletTheme {
        ProfileContent(
            uiState = ProfileUiState(
                profile = ProfileUi(
                    name = "Preview User",
                    mobileNumber = "9000000000",
                    role = "Member",
                    isVerified = true,
                    status = "Verified",
                    referralCode = "ABCDEFGH"
                )
            ),
            snackbarHostState = SnackbarHostState(),
            onRefresh = {},
            onLogoutConfirmed = {},
            actions = ProfileActions()
        )
    }
}

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun ProfileScreenErrorPreview() {
    Hawk_WalletTheme {
        ProfileContent(
            uiState = ProfileUiState(errorMessage = "Unable to reach the server. Check your connection."),
            snackbarHostState = SnackbarHostState(),
            onRefresh = {},
            onLogoutConfirmed = {},
            actions = ProfileActions()
        )
    }
}
