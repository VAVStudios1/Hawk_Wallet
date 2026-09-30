package com.example.hawk_wallet.ui.screens.support

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** Admin support inbox: open / closed conversations with unread counts. */
@Composable
fun AdminSupportInboxScreen(
    viewModel: AdminSupportInboxViewModel,
    isSuperAdmin: Boolean,
    onBackClick: () -> Unit,
    onConversationClick: (conversationId: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads each time the screen opens, e.g. back from a conversation (unread counts change).
    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminSupportInboxContent(
        uiState = uiState,
        isSuperAdmin = isSuperAdmin,
        onBackClick = onBackClick,
        onRefresh = viewModel::refresh,
        onFilterSelect = viewModel::selectFilter,
        onLoadMore = viewModel::loadMore,
        onConversationClick = onConversationClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminSupportInboxContent(
    uiState: AdminSupportInboxUiState,
    isSuperAdmin: Boolean,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onFilterSelect: (InboxFilter) -> Unit,
    onLoadMore: () -> Unit,
    onConversationClick: (String) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Support", onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FilterTabs(selected = uiState.filter, onSelect = onFilterSelect)
            if (isSuperAdmin) {
                ReadOnlyStrip(
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
                    message = "As a Super Admin you can read every conversation, but you can't reply, mark them as read or close them."
                )
            }
            val firstLoad = uiState.isLoading && !uiState.loaded
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && uiState.loaded,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                if (firstLoad) {
                    CenteredLoading()
                    return@PullToRefreshBox
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val error = uiState.error
                    when {
                        uiState.conversations.isEmpty() && error != null -> item {
                            CenteredMessage(
                                title = "Couldn't load conversations",
                                message = error,
                                actionLabel = "Retry",
                                onAction = onRefresh,
                                modifier = Modifier.padding(top = 96.dp)
                            )
                        }
                        uiState.conversations.isEmpty() -> item {
                            CenteredMessage(
                                title = if (uiState.filter == InboxFilter.OPEN) "No open conversations" else "No closed conversations",
                                message = if (uiState.filter == InboxFilter.OPEN) {
                                    "Member conversations appear here as soon as they're started. Pull down to refresh."
                                } else {
                                    "Conversations you close appear here."
                                },
                                modifier = Modifier.padding(top = 96.dp)
                            )
                        }
                        else -> {
                            if (error != null) {
                                item {
                                    AuthErrorBanner(message = error)
                                    TextButton(onClick = onRefresh) { Text("Retry") }
                                }
                            }
                            items(uiState.conversations, key = { it.id }) { conversation ->
                                ConversationRow(
                                    conversation = conversation,
                                    onClick = { onConversationClick(conversation.id) }
                                )
                            }
                            if (uiState.hasNext) {
                                item(key = "load-more") {
                                    LaunchedEffect(uiState.page) { onLoadMore() }
                                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Open / Closed, in the same TabRow style as Orders and Payment Methods. */
@Composable
private fun FilterTabs(selected: InboxFilter, onSelect: (InboxFilter) -> Unit) {
    TabRow(selectedTabIndex = InboxFilter.entries.indexOf(selected), containerColor = MaterialTheme.colorScheme.background) {
        InboxFilter.entries.forEach { filter ->
            Tab(selected = filter == selected, onClick = { onSelect(filter) }, text = { Text(filter.label) })
        }
    }
}

@Composable
private fun ConversationRow(conversation: SupportConversationUi, onClick: () -> Unit) {
    val member = conversation.member ?: return
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(name = member.name)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = member.mobileNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val activity = conversation.lastMessageAt ?: conversation.createdAt
                Text(
                    text = DisplayFormat.dateTime(activity),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.unreadCount > 0) HawkIndigo else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (conversation.unreadCount > 0) UnreadBadge(conversation.unreadCount)
            }
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConversationStatusChip(isOpen = conversation.isOpen)
            if (conversation.lastMessageAt == null) {
                Text(
                    text = "No messages yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!conversation.isAssignedToMe) {
                Text(
                    text = "View only",
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
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .widthIn(min = 22.dp)
            .height(22.dp)
            .background(HawkIndigo, RoundedCornerShape(50))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

// Previews use placeholder values only; the real screen shows nothing but backend data.

@Preview(showBackground = true)
@Composable
private fun AdminSupportInboxPreview() {
    Hawk_WalletTheme {
        AdminSupportInboxContent(
            uiState = AdminSupportInboxUiState(
                loaded = true,
                conversations = listOf(
                    SupportConversationUi(
                        id = "1",
                        isOpen = true,
                        unreadCount = 2,
                        lastMessageAt = "2026-10-01T10:00:00Z",
                        createdAt = "2026-10-01T09:00:00Z",
                        member = SupportMemberUi("Preview Member", "9000000000", "2026-09-01T09:00:00Z")
                    )
                )
            ),
            isSuperAdmin = false,
            onBackClick = {},
            onRefresh = {},
            onFilterSelect = {},
            onLoadMore = {},
            onConversationClick = {}
        )
    }
}
