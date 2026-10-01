package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AdminBottomNavigation
import com.example.hawk_wallet.ui.components.AdminNavDestination
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.profile.RowDivider
import com.example.hawk_wallet.ui.screens.profile.roleLabel
import com.example.hawk_wallet.ui.screens.team.IconBadge
import com.example.hawk_wallet.ui.screens.team.SectionHeader
import com.example.hawk_wallet.ui.screens.team.SurfaceCard
import com.example.hawk_wallet.ui.theme.HawkAmberDark
import com.example.hawk_wallet.ui.theme.HawkAmberSoft
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkVioletBlue
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * Where the Dashboard leads: Pay-In Reviews and Payouts Pending open their
 * queues pre-filtered to the backend status the count is based on, Orders
 * Awaiting Action opens Orders on the backend's awaiting-action filter, and
 * Support Chats opens the Support tab.
 */
data class AdminDashboardActions(
    val onProfileClick: () -> Unit = {},
    val onPayInReviewsClick: () -> Unit = {},
    val onPayoutsPendingClick: () -> Unit = {},
    val onOrdersAwaitingClick: () -> Unit = {},
    val onSupportClick: () -> Unit = {},
    val onBottomNavSelect: (AdminNavDestination) -> Unit = {}
)

/**
 * Landing tab for ADMIN and SUPER_ADMIN: an operational overview and what
 * needs attention, all counts from GET /api/admin/dashboard/. Nothing is
 * counted on the device, and no value is shown until the backend has sent it.
 */
@Composable
fun AdminDashboardScreen(
    viewModel: AdminDashboardViewModel,
    userName: String,
    userRole: String,
    actions: AdminDashboardActions
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads each time the tab opens, e.g. back from reviewing an order.
    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminDashboardContent(
        uiState = uiState,
        userName = userName,
        userRole = userRole,
        onRefresh = viewModel::refresh,
        actions = actions
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminDashboardContent(
    uiState: AdminDashboardUiState,
    userName: String,
    userRole: String,
    onRefresh: () -> Unit,
    actions: AdminDashboardActions
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AdminBottomNavigation(selected = AdminNavDestination.DASHBOARD, onSelect = actions.onBottomNavSelect)
        }
    ) { innerPadding ->
        val metrics = uiState.metrics
        PullToRefreshBox(
            isRefreshing = uiState.isLoading && metrics != null,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val section = Modifier.padding(horizontal = ScreenHorizontalPadding)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    AdminHeader(userName = userName, userRole = userRole, onProfileClick = actions.onProfileClick)
                }
                val error = uiState.error
                when {
                    metrics == null && error != null -> item {
                        CenteredMessage(
                            title = "Couldn't load the dashboard",
                            message = error,
                            actionLabel = "Retry",
                            onAction = onRefresh,
                            modifier = Modifier.padding(top = 72.dp)
                        )
                    }
                    metrics == null -> item {
                        CenteredLoading(modifier = Modifier.padding(top = 96.dp))
                    }
                    else -> {
                        if (error != null) {
                            // Loaded before, but the latest refresh failed: keep the data and say so.
                            item {
                                Column(modifier = section) {
                                    AuthErrorBanner(message = error)
                                    TextButton(onClick = onRefresh) { Text("Retry") }
                                }
                            }
                        }
                        item { OverviewSection(metrics = metrics, modifier = section) }
                        item {
                            NeedsAttentionSection(
                                metrics = metrics,
                                isSuperAdmin = isSuperAdmin(userRole),
                                actions = actions,
                                modifier = section
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Same avatar and greeting layout as the member Home top bar, with the admin's role. */
@Composable
private fun AdminHeader(userName: String, userRole: String, onProfileClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(HawkIndigo, HawkVioletBlue)))
                .clickable(onClickLabel = "Open profile", onClick = onProfileClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = userName.trim().take(1).uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = "Hello,",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = userName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (userRole.isNotEmpty()) {
            Text(
                text = roleLabel(userRole),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = HawkIndigo,
                modifier = Modifier
                    .background(HawkIndigo.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun OverviewSection(metrics: AdminDashboardMetrics, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionHeader(title = "Overview", modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Total Users", metrics.totalUsers, Icons.Outlined.People, HawkIndigo, Modifier.weight(1f))
            MetricTile(
                "Active Orders",
                metrics.activeOrders,
                Icons.AutoMirrored.Outlined.ReceiptLong,
                HawkEmerald,
                Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Pending Pay-In", metrics.pendingPayIns, Icons.Outlined.Payments, HawkAmberDark, Modifier.weight(1f))
            MetricTile(
                "Pending Payout",
                metrics.pendingPayouts,
                Icons.Outlined.AccountBalanceWallet,
                HawkVioletBlue,
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricTile(label: String, value: Int, icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    SurfaceCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            IconBadge(icon = icon, tint = tint, background = tint.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NeedsAttentionSection(
    metrics: AdminDashboardMetrics,
    isSuperAdmin: Boolean,
    actions: AdminDashboardActions,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionHeader(title = "Needs Attention", modifier = Modifier.padding(bottom = 8.dp))
        SurfaceCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AttentionRow(
                    icon = Icons.Outlined.TaskAlt,
                    title = "Pay-In Reviews",
                    subtitle = "Pay-ins waiting to be confirmed or rejected",
                    count = metrics.pendingPayIns,
                    onClick = actions.onPayInReviewsClick
                )
                RowDivider()
                AttentionRow(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    title = "Payouts Pending",
                    subtitle = "Payouts created but not yet paid",
                    count = metrics.pendingPayouts,
                    onClick = actions.onPayoutsPendingClick
                )
                RowDivider()
                AttentionRow(
                    icon = Icons.Outlined.SupportAgent,
                    title = "Support Chats",
                    subtitle = if (isSuperAdmin) "Open conversations across all admins" else "Open conversations assigned to you",
                    count = metrics.openSupport,
                    onClick = actions.onSupportClick
                )
                // Hidden if the backend ever omits it; never computed here.
                metrics.ordersAwaitingAction?.let { count ->
                    RowDivider()
                    AttentionRow(
                        icon = Icons.Outlined.PendingActions,
                        title = "Orders Awaiting Action",
                        subtitle = "Pay-ins to review or payouts to create / mark paid",
                        count = count,
                        onClick = actions.onOrdersAwaitingClick
                    )
                }
            }
        }
    }
}

/** [ icon ]  Title / subtitle      ( count )  > */
@Composable
private fun AttentionRow(icon: ImageVector, title: String, subtitle: String, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon = icon, tint = HawkIndigo, background = HawkIndigo.copy(alpha = 0.1f))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        CountPill(count = count)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Amber while something is waiting; muted when the backend reports none. */
@Composable
private fun CountPill(count: Int) {
    val waiting = count > 0
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 28.dp)
            .background(
                if (waiting) HawkAmberSoft else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (waiting) HawkAmberDark else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Previews use placeholder values only; the real screen shows nothing but backend data.

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun AdminDashboardPreview() {
    Hawk_WalletTheme {
        AdminDashboardContent(
            uiState = AdminDashboardUiState(
                metrics = AdminDashboardMetrics(
                    totalUsers = 128,
                    activeOrders = 14,
                    pendingPayIns = 3,
                    pendingPayouts = 5,
                    openSupport = 0,
                    ordersAwaitingAction = null
                )
            ),
            userName = "Jordan Admin",
            userRole = "SUPER_ADMIN",
            onRefresh = {},
            actions = AdminDashboardActions()
        )
    }
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun AdminDashboardErrorPreview() {
    Hawk_WalletTheme {
        AdminDashboardContent(
            uiState = AdminDashboardUiState(error = "Unable to reach the server. Check your connection."),
            userName = "Jordan Admin",
            userRole = "ADMIN",
            onRefresh = {},
            actions = AdminDashboardActions()
        )
    }
}
