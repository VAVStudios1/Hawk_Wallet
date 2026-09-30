package com.example.hawk_wallet.data.remote.model

// Support chat (backend support app). Ids are the backend's public UUIDs;
// attachments carry display metadata only - the file itself is fetched
// through the authorized download endpoints, never a storage URL.

/** Display metadata of an IMAGE / FILE message; the content type is the server's own. */
data class SupportAttachmentDto(
    val name: String,
    val content_type: String,
    val size: Long
)

/** The member's conversation: no admin identity or routing information. */
data class MemberConversationDto(
    val id: String,
    val status: String,
    val unread_count: Int,
    val last_message_at: String?,
    val created_at: String,
    val closed_at: String?
)

/** GET / POST /api/support/conversation/ and POST .../read/ -> data. */
data class MemberConversationData(
    val conversation: MemberConversationDto?
)

/** A message as the member sees it; support replies are labelled, never named. */
data class MemberMessageDto(
    val id: String,
    val type: String,
    val text: String,
    val attachment: SupportAttachmentDto?,
    val from_support: Boolean,
    val sender_name: String?,
    val created_at: String
)

/** GET /api/support/conversation/messages/ -> data. Messages are oldest first. */
data class MemberMessagePageData(
    val messages: List<MemberMessageDto>,
    val has_more: Boolean
)

/** What an admin may see about the member they are helping. */
data class SupportMemberDto(
    val name: String,
    val mobile_number: String,
    val member_since: String
)

/**
 * A conversation in the admin inbox. [is_assigned_to_me] is false for a
 * SUPER_ADMIN viewing another admin's conversation (read-only).
 */
data class AdminConversationDto(
    val id: String,
    val status: String,
    val member: SupportMemberDto,
    val is_assigned_to_me: Boolean,
    val unread_count: Int,
    val last_message_at: String?,
    val created_at: String,
    val closed_at: String?
)

/** GET /api/admin/support/conversations/ -> data (page-number pagination). */
data class AdminConversationListData(
    val conversations: List<AdminConversationDto>,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val has_next: Boolean
)

/** A message as an admin sees it. Other admins are never identified. */
data class AdminMessageDto(
    val id: String,
    val type: String,
    val text: String,
    val attachment: SupportAttachmentDto?,
    val from_member: Boolean,
    val sent_by_me: Boolean,
    val created_at: String
)

/** GET /api/admin/support/conversations/{id}/messages/ -> data. Messages are oldest first. */
data class AdminMessagePageData(
    val messages: List<AdminMessageDto>,
    val has_more: Boolean
)

data class SendSupportTextRequest(
    val text: String
)

data class MarkSupportReadRequest(
    /** The newest message the member / admin has actually seen. */
    val up_to: String
)
