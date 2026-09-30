package com.example.hawk_wallet.ui.screens.support

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkVioletBlue

/**
 * One support conversation for an admin. ADMIN (assigned): reads, replies,
 * marks read and closes. SUPER_ADMIN: reads only - no composer, no read
 * marker, no close; the backend enforces the same and audits the view.
 */
@Composable
fun AdminSupportChatScreen(
    viewModel: SupportChatViewModel,
    isSuperAdmin: Boolean,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(isSuperAdmin) { viewModel.setViewerIsSuperAdmin(isSuperAdmin) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HawkBackTopBar(title = uiState.conversation?.member?.name ?: "Conversation", onBackClick = onBackClick)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            when (uiState.phase) {
                SupportChatPhase.READY -> SupportConversationPane(
                    viewModel = viewModel,
                    uiState = uiState,
                    listState = listState,
                    emptyTitle = "No messages yet",
                    emptyMessage = if (uiState.readOnly) {
                        "Messages between the member and support will appear here."
                    } else {
                        "Send the member a message to get started."
                    },
                    header = {
                        uiState.conversation?.let { conversation ->
                            MemberHeader(
                                conversation = conversation,
                                canClose = uiState.canClose,
                                isClosing = uiState.isClosing,
                                onCloseClick = { confirmClose = true }
                            )
                        }
                    }
                )
                SupportChatPhase.LOAD_FAILED -> CenteredMessage(
                    title = "Couldn't open this conversation",
                    message = uiState.loadError,
                    actionLabel = if (uiState.sessionExpired) null else "Retry",
                    onAction = viewModel::load
                )
                // Admins never see the member's "start" state.
                else -> CenteredLoading()
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close conversation") },
            text = {
                Text("The member won't be able to send more messages here. Their next conversation will come back to you.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    viewModel.closeConversation()
                }) { Text("Close", color = HawkCoral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text("Cancel") }
            }
        )
    }
}

/** The member's support details (as the backend allows) and the conversation's status. */
@Composable
private fun MemberHeader(
    conversation: SupportConversationUi,
    canClose: Boolean,
    isClosing: Boolean,
    onCloseClick: () -> Unit
) {
    val member = conversation.member ?: return
    OrderCard(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(name = member.name)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = member.mobileNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Member since ${DisplayFormat.day(member.memberSince).ifEmpty { member.memberSince }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ConversationStatusChip(isOpen = conversation.isOpen)
        }
        if (canClose) {
            Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.weight(1f))
                if (isClosing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onCloseClick) { Text("Close conversation", color = HawkCoral) }
                }
            }
        }
    }
}

/** Gradient initial badge, as the Home / Admin Dashboard avatars. */
@Composable
internal fun MemberAvatar(name: String, sizeDp: Int = 44) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
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
