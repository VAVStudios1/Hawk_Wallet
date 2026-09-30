package com.example.hawk_wallet.ui.screens.support

import androidx.compose.ui.graphics.ImageBitmap
import com.example.hawk_wallet.data.remote.model.AdminConversationDto
import com.example.hawk_wallet.data.remote.model.AdminMessageDto
import com.example.hawk_wallet.data.remote.model.MemberConversationDto
import com.example.hawk_wallet.data.remote.model.MemberMessageDto
import com.example.hawk_wallet.data.remote.model.SupportAttachmentDto
import com.example.hawk_wallet.data.repository.SupportAttachmentKind

/** Mirrors support.models.MESSAGE_TEXT_MAX_LENGTH. */
internal const val SUPPORT_TEXT_MAX_LENGTH = 4000

/** What members see instead of any admin's name. Mirrors support.serializers.SUPPORT_DISPLAY_NAME. */
internal const val SUPPORT_DISPLAY_NAME = "Hawk Wallet Support"

enum class SupportMessageType {
    TEXT, IMAGE, FILE;

    companion object {
        fun fromApi(value: String): SupportMessageType = entries.firstOrNull { it.name == value } ?: TEXT
    }
}

data class SupportAttachmentUi(
    val name: String,
    /** Detected by the server, never taken from the client. */
    val contentType: String,
    val sizeBytes: Long
)

/**
 * One chat bubble. [isOutgoing] puts it on the right: the member's own
 * messages for a member, support's messages for an admin. [isFromOtherSide]
 * is what counts towards the viewer's unread state.
 */
data class SupportMessageUi(
    val id: String,
    val type: SupportMessageType,
    val text: String,
    val attachment: SupportAttachmentUi?,
    val isOutgoing: Boolean,
    val isFromOtherSide: Boolean,
    /** Small label above an incoming bubble; null when none is shown. */
    val senderLabel: String?,
    val createdAt: String
)

/** What an admin may see about the member; never shown to members. */
data class SupportMemberUi(
    val name: String,
    val mobileNumber: String,
    val memberSince: String
)

data class SupportConversationUi(
    val id: String,
    val isOpen: Boolean,
    val unreadCount: Int,
    val lastMessageAt: String?,
    val createdAt: String,
    /** Admin side only. */
    val member: SupportMemberUi? = null,
    /** Admin side only: false for a SUPER_ADMIN viewing another admin's conversation. */
    val isAssignedToMe: Boolean = true
)

enum class SupportChatPhase {
    /** Looking for the member's conversation (or opening the admin's). */
    LOADING,
    /** Member only: never started one - show "How can we help?". */
    NO_CONVERSATION,
    READY,
    /** The conversation itself could not be loaded; nothing to show yet. */
    LOAD_FAILED
}

sealed interface SupportImageState {
    data object Loading : SupportImageState
    data class Loaded(val bitmap: ImageBitmap) : SupportImageState
    data object Failed : SupportImageState
}

/** A picked attachment waiting in the composer; already checked and read on the device. */
data class PendingAttachmentUi(
    val kind: SupportAttachmentKind,
    val name: String,
    val sizeBytes: Long,
    /** Image kind only: a downsampled preview. */
    val preview: ImageBitmap? = null
)

data class SupportChatUiState(
    val phase: SupportChatPhase = SupportChatPhase.LOADING,
    val conversation: SupportConversationUi? = null,
    val loadError: String? = null,
    val isStarting: Boolean = false,
    val startError: String? = null,

    /** Oldest first. */
    val messages: List<SupportMessageUi> = emptyList(),
    val isLoadingMessages: Boolean = false,
    /** Loading or refreshing messages failed; any loaded messages stay on screen. */
    val messagesError: String? = null,
    val hasOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,

    val draft: String = "",
    val pending: PendingAttachmentUi? = null,
    val isPreparingAttachment: Boolean = false,
    val isSending: Boolean = false,
    /** Send / attachment failure, shown above the composer. */
    val composerError: String? = null,

    /** Opening an attachment failed, closing failed, etc. */
    val notice: String? = null,
    val openingFileId: String? = null,
    val images: Map<String, SupportImageState> = emptyMap(),

    /** SUPER_ADMIN (or another admin's conversation): can read, never send, mark read or close. */
    val readOnly: Boolean = false,
    val isClosing: Boolean = false,
    val sessionExpired: Boolean = false
) {
    val isClosed: Boolean
        get() = conversation?.isOpen == false

    val canCompose: Boolean
        get() = phase == SupportChatPhase.READY && !isClosed && !readOnly && !sessionExpired

    val canSend: Boolean
        get() = canCompose && !isSending && !isPreparingAttachment && (draft.isNotBlank() || pending != null)

    val canClose: Boolean
        get() = conversation?.isOpen == true && !readOnly && conversation.member != null && !sessionExpired
}

// --- mapping ----------------------------------------------------------------------

private fun SupportAttachmentDto.toUi() = SupportAttachmentUi(name = name, contentType = content_type, sizeBytes = size)

internal fun MemberConversationDto.toUi() = SupportConversationUi(
    id = id,
    isOpen = status == "OPEN",
    unreadCount = unread_count,
    lastMessageAt = last_message_at,
    createdAt = created_at
)

internal fun AdminConversationDto.toUi() = SupportConversationUi(
    id = id,
    isOpen = status == "OPEN",
    unreadCount = unread_count,
    lastMessageAt = last_message_at,
    createdAt = created_at,
    member = SupportMemberUi(name = member.name, mobileNumber = member.mobile_number, memberSince = member.member_since),
    isAssignedToMe = is_assigned_to_me
)

internal fun MemberMessageDto.toUi() = SupportMessageUi(
    id = id,
    type = SupportMessageType.fromApi(type),
    text = text,
    attachment = attachment?.toUi(),
    isOutgoing = !from_support,
    isFromOtherSide = from_support,
    // The backend's own label; members never see an admin's identity.
    senderLabel = if (from_support) sender_name ?: SUPPORT_DISPLAY_NAME else null,
    createdAt = created_at
)

internal fun AdminMessageDto.toUi() = SupportMessageUi(
    id = id,
    type = SupportMessageType.fromApi(type),
    text = text,
    attachment = attachment?.toUi(),
    isOutgoing = !from_member,
    isFromOtherSide = from_member,
    senderLabel = null,
    createdAt = created_at
)
