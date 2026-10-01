package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.PrimaryActionButton
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.orders.code
import com.example.hawk_wallet.ui.screens.orders.userMessage
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminAccountDetailUiState(
    val admin: AdminAccountUi? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** The status the open confirmation dialog would set; null = no dialog. */
    val confirmActive: Boolean? = null,
    val isSubmitting: Boolean = false,
    val actionError: String? = null,
    val message: String? = null
)

/** Errors meaning the account can't be changed here; close the dialog and show why. */
private val NOT_CHANGEABLE_CODES = setOf("cannot_manage_own_account", "admin_not_manageable")

/**
 * One admin account, from GET /api/admin/admins/{id}/, with Activate /
 * Deactivate (SUPER_ADMIN only; never their own account or another
 * SUPER_ADMIN - the backend enforces both and audits every change).
 */
class AdminAccountDetailViewModel(
    private val repository: AdminManagementRepository,
    private val adminId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminAccountDetailUiState())
    val uiState: StateFlow<AdminAccountDetailUiState> = _uiState

    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.admin(adminId)) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, admin = result.data.toUi()) }
                is ApiResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.userMessage()) }
            }
        }
    }

    fun openStatusDialog() {
        val admin = _uiState.value.admin ?: return
        if (!admin.canChangeStatus || _uiState.value.isSubmitting) return
        _uiState.update { it.copy(confirmActive = !admin.isActive, actionError = null) }
    }

    fun dismissDialog() {
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(confirmActive = null, actionError = null) }
    }

    /** Safe to retry: repeating the current state changes nothing on the backend. */
    fun confirmStatusChange() {
        val target = _uiState.value.confirmActive ?: return
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true, actionError = null) }
        viewModelScope.launch {
            when (val result = repository.setAdminActive(adminId, target)) {
                is ApiResult.Success -> {
                    val admin = result.data.toUi()
                    // After deactivation the backend has already moved their open chats to other
                    // admins; any still counted here had no active admin to go to.
                    val stranded = admin.workload.openSupport
                    val message = when {
                        target -> "Admin activated."
                        stranded > 0 -> "Admin deactivated. $stranded open chat(s) could not be reassigned: no other active admin."
                        else -> "Admin deactivated. Their open chats were reassigned."
                    }
                    _uiState.update {
                        it.copy(isSubmitting = false, confirmActive = null, admin = admin, message = message)
                    }
                }
                is ApiResult.Error -> if (result.code in NOT_CHANGEABLE_CODES) {
                    _uiState.update { it.copy(isSubmitting = false, confirmActive = null, message = result.message) }
                    refresh()
                } else {
                    _uiState.update { it.copy(isSubmitting = false, actionError = result.userMessage()) }
                }
            }
        }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }
}

@Composable
fun AdminAccountDetailScreen(viewModel: AdminAccountDetailViewModel, onBackClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    uiState.confirmActive?.let { activate ->
        StatusDialog(
            name = uiState.admin?.name.orEmpty(),
            activate = activate,
            isSubmitting = uiState.isSubmitting,
            error = uiState.actionError,
            onConfirm = viewModel::confirmStatusChange,
            onDismiss = viewModel::dismissDialog
        )
    }
    AdminAccountDetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onRefresh = viewModel::refresh,
        onChangeStatus = viewModel::openStatusDialog,
        onMessageShown = viewModel::messageShown
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminAccountDetailContent(
    uiState: AdminAccountDetailUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onChangeStatus: () -> Unit,
    onMessageShown: () -> Unit
) {
    val admin = uiState.admin
    val snackbarHostState = remember { SnackbarHostState() }
    uiState.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Admin", onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (admin != null && admin.canChangeStatus) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        // Edge-to-edge: keep the button above the system navigation bar.
                        .navigationBarsPadding()
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp)
                ) {
                    if (admin.isActive) {
                        OutlinedButton(
                            onClick = onChangeStatus,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HawkCoral),
                            border = BorderStroke(1.dp, HawkCoral),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) { Text("Deactivate Admin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    } else {
                        PrimaryActionButton(text = "Activate Admin", onClick = onChangeStatus)
                    }
                }
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when {
            admin == null && uiState.error != null -> CenteredMessage(
                title = "Couldn't load this admin",
                message = uiState.error,
                actionLabel = "Retry",
                onAction = onRefresh,
                modifier = modifier
            )
            admin == null -> CenteredLoading(modifier)
            else -> PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = onRefresh,
                modifier = modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp)
                ) {
                    uiState.error?.let { AuthErrorBanner(message = it, modifier = Modifier.padding(bottom = 12.dp)) }
                    OrderCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UserInitial(name = admin.name, size = 56.dp)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = admin.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = admin.mobileMasked,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        AdminAccountTags(admin = admin, modifier = Modifier.padding(top = 12.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow(label = "Admin ID", value = admin.id.toString())
                        DetailRow(label = "Joined", value = DisplayFormat.dateTime(admin.dateJoined))
                        if (!admin.isVerified) {
                            Text(
                                text = "Mobile not verified yet: an OTP is sent to this number on the admin's first login.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "WORKLOAD") {
                        DetailRow(label = "Open support conversations", value = admin.workload.openSupport.toString())
                        DetailRow(label = "Pending payouts they created", value = admin.workload.pendingPayoutsCreated.toString())
                        DetailRow(label = "Pay-ins reviewed", value = admin.workload.payInsReviewed.toString())
                        DetailRow(label = "Payouts marked paid", value = admin.workload.payoutsPaid.toString())
                        Text(
                            text = "Members, orders and pending pay-ins are shared by all admins, so they have no per-admin count.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (!admin.canChangeStatus) {
                        Text(
                            text = if (admin.isSelf) "You can't change the status of your own account."
                            else "Super Admin accounts can't be changed here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusDialog(
    name: String,
    activate: Boolean,
    isSubmitting: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (activate) "Activate admin?" else "Deactivate admin?") },
        text = {
            Column {
                Text(
                    text = if (activate) {
                        "$name will be able to sign in and use the admin panel again."
                    } else {
                        "$name will be signed out and can't sign in until reactivated. Their open support chats move " +
                            "to other active admins; their orders, reviews, payouts, audit and closed chats stay as they are."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                error?.let {
                    Text(text = it, color = HawkCoral, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isSubmitting,
                colors = if (activate) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = HawkCoral)
            ) {
                if (isSubmitting) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Text(if (activate) "Activate" else "Deactivate", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") } }
    )
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun AdminAccountDetailPreview() {
    Hawk_WalletTheme {
        AdminAccountDetailContent(
            uiState = AdminAccountDetailUiState(admin = previewAdminAccount()),
            onBackClick = {},
            onRefresh = {},
            onChangeStatus = {},
            onMessageShown = {}
        )
    }
}
