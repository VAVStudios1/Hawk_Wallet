package com.example.hawk_wallet.ui.screens.support

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hawk_wallet.data.repository.SupportAttachmentKind
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.PrimaryActionButton
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkEmeraldSoft
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkNavy
import com.example.hawk_wallet.ui.theme.HawkVioletBlue

// Hawk Wallet chat pieces: the interaction of a messaging app, drawn with
// the app's own palette, cards and type. Nothing here knows which admin
// answers a member.

private val BubbleMaxWidth = 290.dp
private val OutgoingShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
private val IncomingShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
private val ComposerShape = RoundedCornerShape(24.dp)

/** Everything the message list can ask for. */
class SupportChatListActions(
    val onImageVisible: (messageId: String) -> Unit,
    val onImageClick: (messageId: String) -> Unit,
    val onFileClick: (SupportMessageUi) -> Unit,
    val onRetryImage: (messageId: String) -> Unit
)

/** A row of the reversed list: newest first, so the conversation sits on the bottom edge. */
private sealed interface ChatRow {
    val key: String

    data class Message(val message: SupportMessageUi, val showSender: Boolean) : ChatRow {
        override val key = messageKey(message.id)
    }

    data class Day(val label: String, val anchorId: String) : ChatRow {
        override val key = "d:$anchorId"
    }
}

internal fun messageKey(id: String) = "m:$id"
internal fun messageIdFromKey(key: Any?): String? = (key as? String)?.takeIf { it.startsWith("m:") }?.removePrefix("m:")

private fun chatRows(messages: List<SupportMessageUi>): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 8)
    for (index in messages.indices.reversed()) {
        val message = messages[index]
        val older = messages.getOrNull(index - 1)
        // Label an incoming run once, on its first (oldest) bubble.
        val showSender = message.senderLabel != null &&
            (older == null || older.isOutgoing || older.senderLabel != message.senderLabel)
        rows += ChatRow.Message(message, showSender)
        val day = DisplayFormat.day(message.createdAt)
        if (older == null || DisplayFormat.day(older.createdAt) != day) {
            if (day.isNotEmpty()) rows += ChatRow.Day(day, message.id)
        }
    }
    return rows
}

/** The conversation: newest at the bottom, older pages load as the user scrolls up. */
@Composable
internal fun SupportMessageList(
    uiState: SupportChatUiState,
    listState: LazyListState,
    actions: SupportChatListActions,
    modifier: Modifier = Modifier
) {
    val rows = remember(uiState.messages) { chatRows(uiState.messages) }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (row in rows) {
            item(key = row.key) {
                when (row) {
                    is ChatRow.Message -> MessageBubble(
                        message = row.message,
                        showSender = row.showSender,
                        image = uiState.images[row.message.id],
                        isOpeningFile = uiState.openingFileId == row.message.id,
                        actions = actions
                    )
                    is ChatRow.Day -> DayDivider(row.label)
                }
            }
        }
        if (uiState.isLoadingOlder) {
            item(key = "older-loading") {
                Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun DayDivider(label: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MessageBubble(
    message: SupportMessageUi,
    showSender: Boolean,
    image: SupportImageState?,
    isOpeningFile: Boolean,
    actions: SupportChatListActions
) {
    val outgoing = message.isOutgoing
    val contentColor = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val metaColor = if (outgoing) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start
    ) {
        if (showSender && message.senderLabel != null) {
            Text(
                text = message.senderLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = HawkIndigo,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp)
            )
        }
        val bubbleModifier = if (outgoing) {
            Modifier
                .shadow(2.dp, OutgoingShape, clip = false)
                .background(Brush.linearGradient(listOf(HawkIndigo, HawkVioletBlue)), OutgoingShape)
        } else {
            Modifier
                .shadow(1.dp, IncomingShape, clip = false)
                .background(MaterialTheme.colorScheme.surface, IncomingShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, IncomingShape)
        }
        Column(
            modifier = Modifier
                .widthIn(max = BubbleMaxWidth)
                .then(bubbleModifier)
                .padding(if (message.type == SupportMessageType.IMAGE) 4.dp else 0.dp)
        ) {
            when (message.type) {
                SupportMessageType.IMAGE -> ImageAttachment(message.id, image, actions)
                SupportMessageType.FILE -> FileAttachment(message, outgoing, isOpeningFile, actions)
                SupportMessageType.TEXT -> Unit
            }
            val textPadding = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp)
            if (message.text.isNotEmpty()) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = textPadding
                )
            }
            Text(
                text = DisplayFormat.time(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = metaColor,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(start = 14.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun ImageAttachment(messageId: String, image: SupportImageState?, actions: SupportChatListActions) {
    LaunchedEffect(messageId) { actions.onImageVisible(messageId) }
    val shape = RoundedCornerShape(15.dp)
    when (image) {
        is SupportImageState.Loaded -> {
            val ratio = (image.bitmap.width.toFloat() / image.bitmap.height.coerceAtLeast(1)).coerceIn(0.6f, 1.8f)
            Image(
                bitmap = image.bitmap,
                contentDescription = "Image attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(240.dp)
                    .aspectRatio(ratio)
                    .clip(shape)
                    .clickable { actions.onImageClick(messageId) }
            )
        }
        SupportImageState.Failed -> Box(
            modifier = Modifier
                .width(240.dp)
                .height(150.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.background)
                .clickable { actions.onRetryImage(messageId) },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "Tap to retry",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> Box(
            modifier = Modifier
                .width(240.dp)
                .height(150.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun FileAttachment(
    message: SupportMessageUi,
    outgoing: Boolean,
    isOpening: Boolean,
    actions: SupportChatListActions
) {
    val attachment = message.attachment ?: return
    val tileColor = if (outgoing) Color.White.copy(alpha = 0.16f) else HawkIndigo.copy(alpha = 0.08f)
    val iconTint = if (outgoing) Color.White else HawkIndigo
    val titleColor = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val metaColor = if (outgoing) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .padding(start = 8.dp, end = 8.dp, top = 8.dp)
            .widthIn(min = 220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tileColor)
            .clickable(enabled = !isOpening) { actions.onFileClick(message) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(if (outgoing) Color.White.copy(alpha = 0.18f) else HawkIndigo.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isOpening) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = iconTint)
            } else {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = iconTint)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${formatBytes(attachment.sizeBytes)} · PDF",
                style = MaterialTheme.typography.labelSmall,
                color = metaColor
            )
        }
    }
}

// --- states & banners --------------------------------------------------------------

/** Gradient icon badge, as the Home / Profile avatars. */
@Composable
private fun SupportBadge(size: Int = 72) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(Brush.linearGradient(listOf(HawkIndigo, HawkVioletBlue)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.SupportAgent, contentDescription = null, tint = Color.White, modifier = Modifier.size((size / 2).dp))
    }
}

/** "How can we help?" - shown until the member explicitly starts a conversation. */
@Composable
internal fun SupportIntroState(
    isStarting: Boolean,
    error: String?,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SupportBadge()
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "How can we help?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Start a conversation with $SUPPORT_DISPLAY_NAME.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (error != null) {
            AuthErrorBanner(message = error, modifier = Modifier.padding(bottom = 12.dp))
        }
        PrimaryActionButton(text = "Start Conversation", onClick = onStart, loading = isStarting)
    }
}

/** An open conversation with no messages yet. */
@Composable
internal fun SupportChatEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(HawkIndigo.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = HawkIndigo)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Error with a retry, kept above the conversation so loaded messages stay visible. */
@Composable
internal fun SupportInlineError(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        AuthErrorBanner(message = message)
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** A soft info strip (closed / view-only), in the style of the Profile tags. */
@Composable
internal fun SupportInfoStrip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    tint: Color = HawkIndigo,
    action: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(tint.copy(alpha = 0.10f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (message != null) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (action != null) {
                Spacer(modifier = Modifier.height(12.dp))
                action()
            }
        }
    }
}

@Composable
internal fun ClosedConversationStrip(modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    SupportInfoStrip(
        icon = Icons.Outlined.Lock,
        title = "This conversation is closed.",
        message = null,
        modifier = modifier,
        action = action
    )
}

@Composable
internal fun ReadOnlyStrip(
    modifier: Modifier = Modifier,
    message: String = "You can read this conversation, but you can't reply, mark it as read or close it."
) {
    SupportInfoStrip(
        icon = Icons.Outlined.Visibility,
        title = "View only",
        message = message,
        modifier = modifier
    )
}

/** Open / Closed, in the StatusChip style. */
@Composable
internal fun ConversationStatusChip(isOpen: Boolean, modifier: Modifier = Modifier) {
    val (container, content, label) = if (isOpen) {
        Triple(HawkEmeraldSoft, HawkEmerald, "Open")
    } else {
        Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "Closed")
    }
    Text(
        text = label,
        color = content,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

// --- composer ------------------------------------------------------------------------

class SupportComposerActions(
    val onDraftChange: (String) -> Unit,
    val onPickImage: () -> Unit,
    val onPickPdf: () -> Unit,
    val onRemoveAttachment: () -> Unit,
    val onSend: () -> Unit
)

/** [ attach ] [ message .......... ] [ send ], with a picked attachment previewed above. */
@Composable
internal fun SupportComposer(uiState: SupportChatUiState, actions: SupportComposerActions, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            uiState.composerError?.let {
                AuthErrorBanner(message = it, modifier = Modifier.padding(bottom = 8.dp))
            }
            uiState.pending?.let { PendingAttachmentPreview(it, enabled = !uiState.isSending, onRemove = actions.onRemoveAttachment) }
            if (uiState.isPreparingAttachment) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Preparing attachment…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                AttachButton(
                    enabled = uiState.canCompose && !uiState.isSending && uiState.pending == null && !uiState.isPreparingAttachment,
                    onPickImage = actions.onPickImage,
                    onPickPdf = actions.onPickPdf
                )
                Spacer(modifier = Modifier.width(4.dp))
                MessageField(
                    value = uiState.draft,
                    enabled = uiState.canCompose && !uiState.isSending,
                    placeholder = if (uiState.pending != null) "Add a caption" else "Message",
                    onValueChange = actions.onDraftChange,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                SendButton(enabled = uiState.canSend, sending = uiState.isSending, onClick = actions.onSend)
            }
            if (uiState.draft.length > SUPPORT_TEXT_MAX_LENGTH - 500) {
                Text(
                    text = "${uiState.draft.length}/$SUPPORT_TEXT_MAX_LENGTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp, end = 56.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachButton(enabled: Boolean, onPickImage: () -> Unit, onPickPdf: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Filled.AttachFile,
                contentDescription = "Attach",
                tint = if (enabled) HawkIndigo else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Photo") },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null, tint = HawkIndigo) },
                onClick = {
                    menuOpen = false
                    onPickImage()
                }
            )
            DropdownMenuItem(
                text = { Text("PDF document") },
                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = HawkIndigo) },
                onClick = {
                    menuOpen = false
                    onPickPdf()
                }
            )
        }
    }
}

@Composable
private fun MessageField(
    value: String,
    enabled: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        maxLines = 5,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(HawkIndigo),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(MaterialTheme.colorScheme.background, ComposerShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, ComposerShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun SendButton(enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    val background = if (enabled) {
        Brush.linearGradient(listOf(HawkIndigo, HawkVioletBlue))
    } else {
        SolidColor(MaterialTheme.colorScheme.outline)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (sending) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PendingAttachmentPreview(pending: PendingAttachmentUi, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val preview = pending.preview
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(HawkIndigo.copy(alpha = 0.10f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (pending.kind == SupportAttachmentKind.FILE) Icons.Outlined.PictureAsPdf else Icons.Outlined.Image,
                    contentDescription = null,
                    tint = HawkIndigo
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pending.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatBytes(pending.sizeBytes) + if (pending.kind == SupportAttachmentKind.FILE) " · PDF" else " · Photo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Filled.Close, contentDescription = "Remove attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- full-screen image -----------------------------------------------------------------

/** Full-screen image with pinch-to-zoom; the bitmap was fetched through the authorized endpoint. */
@Composable
internal fun SupportImageViewer(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HawkNavy.copy(alpha = 0.96f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Image attachment",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            )
            Surface(
                onClick = onDismiss,
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.14f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/** 1536 -> "1.5 KB". Display only. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
