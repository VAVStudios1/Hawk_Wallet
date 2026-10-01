package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.data.remote.model.AdminUserDto
import com.example.hawk_wallet.data.repository.AdminUserRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.profile.ProfileTag
import com.example.hawk_wallet.ui.screens.profile.roleLabel
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkVioletBlue
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** User filters, each mapping to one existing boolean field (null = not filtered). */
enum class AdminUserFilter(val isActive: Boolean?, val isVerified: Boolean?, val label: String) {
    ALL(null, null, "All"),
    ACTIVE(true, null, "Active"),
    INACTIVE(false, null, "Inactive"),
    VERIFIED(null, true, "Verified"),
    UNVERIFIED(null, false, "Unverified")
}

/** A member account as the backend sent it; the mobile number only ever arrives masked. */
data class AdminUserUi(
    val id: Long,
    val name: String,
    val mobileMasked: String,
    val role: String,
    val isVerified: Boolean,
    val isActive: Boolean,
    val dateJoined: String
)

internal fun AdminUserDto.toUi() = AdminUserUi(
    id = id,
    name = name,
    mobileMasked = mobile_number_masked,
    role = roleLabel(role),
    isVerified = is_verified,
    isActive = is_active,
    dateJoined = date_joined
)

/** Every member account, one server page at a time, from GET /api/admin/users/. */
class AdminUsersViewModel(
    private val repository: AdminUserRepository
) : AdminListViewModel<AdminUserUi, AdminUserFilter>(AdminUserFilter.ALL) {

    override suspend fun fetch(filter: AdminUserFilter, search: String, page: Int): ApiResult<AdminPage<AdminUserUi>> =
        when (val result = repository.users(filter.isActive, filter.isVerified, search, page)) {
            is ApiResult.Success -> ApiResult.Success(
                AdminPage(result.data.users.map { it.toUi() }, result.data.page, result.data.has_next, result.data.total),
                result.message
            )
            is ApiResult.Error -> result
        }

    override fun idOf(item: AdminUserUi): Long = item.id
}

private val USER_TEXTS = AdminListTexts(
    title = "Users",
    searchPlaceholder = "Name or mobile number",
    emptyTitle = "No users",
    emptyMessage = "Member accounts appear here as soon as people register. Pull down to refresh.",
    errorTitle = "Couldn't load users"
)

/** The admin Users tab: no back button, the admin bottom bar in [bottomBar]. */
@Composable
fun AdminUsersScreen(
    viewModel: AdminUsersViewModel,
    onUserClick: (userId: Long) -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminUsersContent(
        state = uiState,
        onFilterSelect = viewModel::selectFilter,
        onSearchInputChange = viewModel::onSearchInputChange,
        onSearch = viewModel::submitSearch,
        onClearSearch = viewModel::clearSearch,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onUserClick = onUserClick,
        bottomBar = bottomBar
    )
}

@Composable
private fun AdminUsersContent(
    state: AdminListUiState<AdminUserUi, AdminUserFilter>,
    onFilterSelect: (AdminUserFilter) -> Unit,
    onSearchInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onUserClick: (Long) -> Unit,
    bottomBar: @Composable () -> Unit
) {
    AdminListScaffold(
        state = state,
        texts = USER_TEXTS,
        filters = AdminUserFilter.entries,
        filterLabel = { it.label },
        onBackClick = null,
        onFilterSelect = onFilterSelect,
        onSearchInputChange = onSearchInputChange,
        onSearch = onSearch,
        onClearSearch = onClearSearch,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        itemKey = { it.id },
        bottomBar = bottomBar
    ) { user ->
        UserCard(user = user, onClick = { onUserClick(user.id) })
    }
}

@Composable
private fun UserCard(user: AdminUserUi, onClick: () -> Unit) {
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserInitial(name = user.name, size = 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MemberLine(
                    name = user.mobileMasked,
                    maskedMobile = "Joined ${DisplayFormat.day(user.dateJoined)}"
                )
            }
        }
        UserStatusTags(user = user, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
internal fun UserStatusTags(user: AdminUserUi, modifier: Modifier = Modifier) {
    TagFlow(modifier = modifier) { UserStatusTagItems(isVerified = user.isVerified, isActive = user.isActive) }
}

/** The verification and account-status tags, for use inside a [TagFlow]. */
@Composable
internal fun UserStatusTagItems(isVerified: Boolean, isActive: Boolean) {
    ProfileTag(
        text = if (isVerified) "Verified" else "Unverified",
        color = if (isVerified) HawkEmerald else MaterialTheme.colorScheme.onSurfaceVariant
    )
    ProfileTag(
        text = if (isActive) "Active" else "Deactivated",
        color = if (isActive) HawkIndigo else HawkCoral
    )
}

/** The gradient initial badge used across Hawk Wallet headers. */
@Composable
internal fun UserInitial(name: String, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(Brush.linearGradient(listOf(HawkIndigo, HawkVioletBlue)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifEmpty { "•" },
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

internal fun previewUser(id: Long = 12, active: Boolean = true, verified: Boolean = true) = AdminUserUi(
    id = id,
    name = "Asha Kumar",
    mobileMasked = "******3210",
    role = "Member",
    isVerified = verified,
    isActive = active,
    dateJoined = "2026-09-20T09:00:00Z"
)

@Preview(showBackground = true)
@Composable
private fun AdminUsersPreview() {
    Hawk_WalletTheme {
        AdminUsersContent(
            state = AdminListUiState(
                filter = AdminUserFilter.ALL,
                items = listOf(previewUser(), previewUser(13, active = false, verified = false)),
                loaded = true,
                total = 2
            ),
            onFilterSelect = {},
            onSearchInputChange = {},
            onSearch = {},
            onClearSearch = {},
            onRefresh = {},
            onLoadMore = {},
            onUserClick = {},
            bottomBar = {}
        )
    }
}
