package com.example.hawk_wallet.ui.screens.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.HawkBottomNavigation
import com.example.hawk_wallet.ui.components.HawkNavDestination
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.launch

@Composable
fun TeamScreen(
    viewModel: TeamViewModel,
    onBackClick: () -> Unit,
    onSeeAllMembers: (level: Int?) -> Unit,
    onSeeAllCommissions: () -> Unit,
    onBottomNavSelect: (HawkNavDestination) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Reloads each time the screen opens (e.g. back from the member list).
    LaunchedEffect(Unit) { viewModel.refresh() }

    TeamContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onBackClick = onBackClick,
        onCopy = { referral ->
            copyInvite(context, referral)
            scope.launch {
                snackbarHostState.showSnackbar(if (referral.link != null) "Invite link copied" else "Referral code copied")
            }
        },
        onShare = { referral -> shareInvite(context, referral) },
        onSeeAllMembers = onSeeAllMembers,
        onSeeAllCommissions = onSeeAllCommissions,
        onBottomNavSelect = onBottomNavSelect
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamContent(
    uiState: TeamUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onBackClick: () -> Unit,
    onCopy: (ReferralUi) -> Unit,
    onShare: (ReferralUi) -> Unit,
    onSeeAllMembers: (Int?) -> Unit,
    onSeeAllCommissions: () -> Unit,
    onBottomNavSelect: (HawkNavDestination) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Team", onBackClick = onBackClick) },
        bottomBar = { HawkBottomNavigation(selected = HawkNavDestination.TEAM, onSelect = onBottomNavSelect) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        val referral = uiState.referral
        val screenModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            referral != null -> PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = onRefresh,
                modifier = screenModifier
            ) {
                TeamSections(
                    uiState = uiState,
                    referral = referral,
                    onRetry = onRefresh,
                    onCopy = { onCopy(referral) },
                    onShare = { onShare(referral) },
                    onSeeAllMembers = onSeeAllMembers,
                    onSeeAllCommissions = onSeeAllCommissions
                )
            }
            uiState.isLoading || !uiState.loaded -> CenteredLoading(screenModifier)
            else -> CenteredMessage(
                title = "Couldn't load your team",
                message = uiState.errorMessage ?: "Please try again.",
                actionLabel = "Retry",
                onAction = onRefresh,
                modifier = screenModifier
            )
        }
    }
}

@Composable
private fun TeamSections(
    uiState: TeamUiState,
    referral: ReferralUi,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSeeAllMembers: (Int?) -> Unit,
    onSeeAllCommissions: () -> Unit
) {
    val section = Modifier.padding(horizontal = ScreenHorizontalPadding)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Invite friends and earn from confirmed payments.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = section
            )
        }

        if (uiState.errorMessage != null) {
            // A refresh (or one section) failed: keep the last good data and say so.
            item {
                Column(modifier = section) {
                    AuthErrorBanner(message = uiState.errorMessage)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }

        item { TeamSummaryCard(referral = referral, modifier = section) }

        item { InviteCard(referral = referral, onCopy = onCopy, onShare = onShare, modifier = section) }

        if (uiState.levels.isNotEmpty()) {
            item {
                Column(modifier = section) {
                    SectionHeader(title = "How Team Commission Works")
                    HowItWorksCard(levels = uiState.levels, onLevelClick = { level -> onSeeAllMembers(level) })
                }
            }
        }

        item {
            Column(modifier = section) {
                if (referral.hasNoTeam) {
                    SectionHeader(title = "Team Members")
                    EmptyTeamCard(onInviteClick = onShare)
                } else {
                    SectionHeader(title = "Team Members", onSeeAll = { onSeeAllMembers(null) })
                    SurfaceCard {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            uiState.recentMembers.forEachIndexed { index, member ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                }
                                MemberRow(member = member)
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(modifier = section) {
                SectionHeader(
                    title = "Commission History",
                    onSeeAll = if (uiState.commissionsTotal > 0) onSeeAllCommissions else null
                )
                SurfaceCard {
                    if (uiState.recentCommissions.isEmpty()) {
                        InlineEmpty(
                            icon = Icons.Outlined.Payments,
                            title = "No commission yet",
                            message = "Commission appears here after a team member's payment is confirmed."
                        )
                    } else {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            uiState.recentCommissions.forEachIndexed { index, commission ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                }
                                CommissionRow(commission = commission)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Previews use placeholder values only; the real screen shows nothing but backend data.

private val previewReferral = ReferralUi(
    code = "K7M2PX9Q",
    link = "https://example.com/register?ref=K7M2PX9Q",
    directTeam = "2",
    totalTeam = "3",
    totalCommission = "₹4.00",
    hasNoTeam = false
)

@Preview(showBackground = true, heightDp = 1800)
@Composable
private fun TeamScreenPreview() {
    Hawk_WalletTheme {
        TeamContent(
            uiState = TeamUiState(
                loaded = true,
                referral = previewReferral,
                levels = listOf(
                    TeamLevelUi(1, true, "0.3%", 2, 1, "₹3.00"),
                    TeamLevelUi(2, true, "0.1%", 1, 1, "₹1.00"),
                    TeamLevelUi(3, true, "0.1%", 0, 0, "₹0.00"),
                    TeamLevelUi(22, false, null, 1, 0, "₹0.00")
                ),
                recentMembers = listOf(
                    TeamMemberUi("1", 1, "Ravi Kumar", "******3210", "Today, 10:24 AM"),
                    TeamMemberUi("2", 2, "Asha", "******7788", "25 Sep 2026, 6:48 PM")
                ),
                membersTotal = 2,
                recentCommissions = listOf(
                    CommissionUi(1, "₹3.00", 1, "0.3%", "₹1,000.00", "Ravi Kumar", "******3210", 12, "HW-3KD9QX2M7P", "Today, 11:02 AM")
                ),
                commissionsTotal = 1
            ),
            snackbarHostState = SnackbarHostState(),
            onRefresh = {},
            onBackClick = {},
            onCopy = {},
            onShare = {},
            onSeeAllMembers = {},
            onSeeAllCommissions = {},
            onBottomNavSelect = {}
        )
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun TeamScreenEmptyPreview() {
    Hawk_WalletTheme {
        TeamContent(
            uiState = TeamUiState(
                loaded = true,
                referral = previewReferral.copy(
                    link = null, directTeam = "0", totalTeam = "0", totalCommission = "₹0.00", hasNoTeam = true
                ),
                levels = listOf(TeamLevelUi(1, true, "0.3%", 0, 0, "₹0.00"))
            ),
            snackbarHostState = SnackbarHostState(),
            onRefresh = {},
            onBackClick = {},
            onCopy = {},
            onShare = {},
            onSeeAllMembers = {},
            onSeeAllCommissions = {},
            onBottomNavSelect = {}
        )
    }
}
