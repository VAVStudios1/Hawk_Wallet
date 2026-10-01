package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.data.remote.model.AdminAccountDto
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.profile.ProfileTag
import com.example.hawk_wallet.ui.screens.profile.roleLabel
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkVioletBlue
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

enum class AdminAccountFilter(val isActive: Boolean?, val label: String) {
    ALL(null, "All"),
    ACTIVE(true, "Active"),
    DEACTIVATED(false, "Deactivated")
}

/** What the backend attributes to one admin; nothing is counted on the device. */
data class AdminWorkloadUi(
    val openSupport: Int,
    val pendingPayoutsCreated: Int,
    val payInsReviewed: Int,
    val payoutsPaid: Int
)

data class AdminAccountUi(
    val id: Long,
    val name: String,
    val mobileMasked: String,
    /** The backend role value (ADMIN / SUPER_ADMIN); see [roleText] for display. */
    val role: String,
    val isVerified: Boolean,
    val isActive: Boolean,
    val dateJoined: String,
    val workload: AdminWorkloadUi,
    val isSelf: Boolean,
    val canChangeStatus: Boolean
) {
    val roleText: String get() = roleLabel(role)
}

internal fun AdminAccountDto.toUi() = AdminAccountUi(
    id = id,
    name = name,
    mobileMasked = mobile_number_masked,
    role = role,
    isVerified = is_verified,
    isActive = is_active,
    dateJoined = date_joined,
    workload = AdminWorkloadUi(
        openSupport = workload.open_support_conversations,
        pendingPayoutsCreated = workload.pending_payouts_created,
        payInsReviewed = workload.pay_ins_reviewed,
        payoutsPaid = workload.payouts_paid
    ),
    isSelf = is_self,
    canChangeStatus = can_change_status
)

/** ADMIN and SUPER_ADMIN accounts, one server page at a time, from GET /api/admin/admins/. */
class AdminAccountsViewModel(
    private val repository: AdminManagementRepository
) : AdminListViewModel<AdminAccountUi, AdminAccountFilter>(AdminAccountFilter.ALL) {

    override suspend fun fetch(filter: AdminAccountFilter, search: String, page: Int): ApiResult<AdminPage<AdminAccountUi>> =
        when (val result = repository.admins(filter.isActive, search, page)) {
            is ApiResult.Success -> ApiResult.Success(
                AdminPage(result.data.admins.map { it.toUi() }, result.data.page, result.data.has_next, result.data.total),
                result.message
            )
            is ApiResult.Error -> result
        }

    override fun idOf(item: AdminAccountUi): Long = item.id
}

private val ADMIN_TEXTS = AdminListTexts(
    title = "Admins",
    searchPlaceholder = "Name or mobile number",
    emptyTitle = "No admins",
    emptyMessage = "Admin accounts appear here. Use + to add an admin.",
    errorTitle = "Couldn't load admins"
)

@Composable
fun AdminAccountsScreen(
    viewModel: AdminAccountsViewModel,
    onBackClick: () -> Unit,
    onAdminClick: (adminId: Long) -> Unit,
    onAddAdminClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads each time the screen opens, e.g. after adding or deactivating an admin.
    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminAccountsContent(
        state = uiState,
        onBackClick = onBackClick,
        onFilterSelect = viewModel::selectFilter,
        onSearchInputChange = viewModel::onSearchInputChange,
        onSearch = viewModel::submitSearch,
        onClearSearch = viewModel::clearSearch,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onAdminClick = onAdminClick,
        onAddAdminClick = onAddAdminClick
    )
}

@Composable
private fun AdminAccountsContent(
    state: AdminListUiState<AdminAccountUi, AdminAccountFilter>,
    onBackClick: () -> Unit,
    onFilterSelect: (AdminAccountFilter) -> Unit,
    onSearchInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onAdminClick: (Long) -> Unit,
    onAddAdminClick: () -> Unit
) {
    AdminListScaffold(
        state = state,
        texts = ADMIN_TEXTS,
        filters = AdminAccountFilter.entries,
        filterLabel = { it.label },
        onBackClick = onBackClick,
        onFilterSelect = onFilterSelect,
        onSearchInputChange = onSearchInputChange,
        onSearch = onSearch,
        onClearSearch = onClearSearch,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        itemKey = { it.id },
        topBarActions = {
            IconButton(onClick = onAddAdminClick) {
                Icon(Icons.Outlined.PersonAddAlt, contentDescription = "Add admin")
            }
        }
    ) { admin ->
        AdminAccountCard(admin = admin, onClick = { onAdminClick(admin.id) })
    }
}

@Composable
private fun AdminAccountCard(admin: AdminAccountUi, onClick: () -> Unit) {
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserInitial(name = admin.name, size = 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = admin.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MemberLine(name = admin.mobileMasked, maskedMobile = "Joined ${DisplayFormat.day(admin.dateJoined)}")
            }
        }
        AdminAccountTags(admin = admin, modifier = Modifier.padding(top = 10.dp))
        WorkloadStrip(workload = admin.workload, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
internal fun AdminAccountTags(admin: AdminAccountUi, modifier: Modifier = Modifier) {
    TagFlow(modifier = modifier) {
        ProfileTag(text = admin.roleText, color = if (isSuperAdmin(admin.role)) HawkVioletBlue else HawkIndigo)
        UserStatusTagItems(isVerified = admin.isVerified, isActive = admin.isActive)
        if (admin.isSelf) ProfileTag(text = "You", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Four small counts in one row; labels say exactly what each counts. */
@Composable
internal fun WorkloadStrip(workload: AdminWorkloadUi, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkloadCount("Open chats", workload.openSupport, Modifier.weight(1f))
        WorkloadCount("Pending payouts", workload.pendingPayoutsCreated, Modifier.weight(1f))
        WorkloadCount("Pay-ins reviewed", workload.payInsReviewed, Modifier.weight(1f))
        WorkloadCount("Payouts paid", workload.payoutsPaid, Modifier.weight(1f))
    }
}

@Composable
private fun WorkloadCount(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
    }
}

internal fun previewAdminAccount(id: Long = 2, self: Boolean = false) = AdminAccountUi(
    id = id,
    name = "Neha Admin",
    mobileMasked = "******4321",
    role = "ADMIN",
    isVerified = true,
    isActive = true,
    dateJoined = "2026-09-20T09:00:00Z",
    workload = AdminWorkloadUi(openSupport = 3, pendingPayoutsCreated = 1, payInsReviewed = 12, payoutsPaid = 8),
    isSelf = self,
    canChangeStatus = !self
)

@Preview(showBackground = true)
@Composable
private fun AdminAccountsPreview() {
    Hawk_WalletTheme {
        AdminAccountsContent(
            state = AdminListUiState(
                filter = AdminAccountFilter.ALL,
                items = listOf(previewAdminAccount(), previewAdminAccount(1, self = true).copy(role = "SUPER_ADMIN")),
                loaded = true,
                total = 2
            ),
            onBackClick = {},
            onFilterSelect = {},
            onSearchInputChange = {},
            onSearch = {},
            onClearSearch = {},
            onRefresh = {},
            onLoadMore = {},
            onAdminClick = {},
            onAddAdminClick = {}
        )
    }
}
