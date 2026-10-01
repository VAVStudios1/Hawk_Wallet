package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.profile.LogoutDialog
import com.example.hawk_wallet.ui.screens.profile.ProfileHeaderCard
import com.example.hawk_wallet.ui.screens.profile.ProfileOptionRow
import com.example.hawk_wallet.ui.screens.profile.ProfileSection
import com.example.hawk_wallet.ui.screens.profile.ProfileUi
import com.example.hawk_wallet.ui.screens.profile.ProfileUiState
import com.example.hawk_wallet.ui.screens.profile.ProfileViewModel
import com.example.hawk_wallet.ui.screens.profile.RowDivider
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

data class AdminProfileActions(
    val onBackClick: () -> Unit = {},
    val onPersonalInfoClick: () -> Unit = {},
    val onChangePasswordClick: () -> Unit = {},
    val onLoggedOut: () -> Unit = {}
)

/**
 * The admin's own profile, opened from More. Same data (GET /api/profile/)
 * and the same logout as the member Profile - [ProfileViewModel] revokes
 * this device's refresh token, then clears the session - without the
 * member-only rows (payment methods, orders, team).
 */
@Composable
fun AdminProfileScreen(
    viewModel: ProfileViewModel,
    actions: AdminProfileActions,
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

    AdminProfileContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onLogoutConfirmed = viewModel::logout,
        actions = actions
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminProfileContent(
    uiState: ProfileUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onLogoutConfirmed: () -> Unit,
    actions: AdminProfileActions
) {
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Profile", onBackClick = actions.onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading && uiState.profile != null,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Every section stays usable (including Log Out) even if the profile fails to load.
            val section = Modifier.padding(horizontal = ScreenHorizontalPadding)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    // Name, mobile number, role and verification status.
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

// Previews use placeholder values only; the real screen shows nothing but backend data.

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun AdminProfilePreview() {
    Hawk_WalletTheme {
        AdminProfileContent(
            uiState = ProfileUiState(
                profile = ProfileUi(
                    name = "Preview Admin",
                    mobileNumber = "9000000000",
                    role = "Super Admin",
                    isVerified = true,
                    status = "Verified",
                    referralCode = ""
                )
            ),
            snackbarHostState = SnackbarHostState(),
            onRefresh = {},
            onLogoutConfirmed = {},
            actions = AdminProfileActions()
        )
    }
}
