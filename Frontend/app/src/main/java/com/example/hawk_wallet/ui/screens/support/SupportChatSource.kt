package com.example.hawk_wallet.ui.screens.support

import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.SupportChatRepository
import com.example.hawk_wallet.data.repository.SupportUpload

/** One page of messages, oldest first. */
class SupportMessagePage(val messages: List<SupportMessageUi>, val hasMore: Boolean)

/**
 * The member and admin sides of one chat: the same screen logic, different
 * endpoints. Every rule (who may see, send, mark read or close) is the
 * backend's; this only picks which endpoint to call.
 */
interface SupportChatSource {
    /**
     * Member: GET /conversation/ (null = never started). Admin: GET the
     * conversation detail, which the backend audits for a SUPER_ADMIN, so
     * it is not polled.
     */
    suspend fun conversation(): ApiResult<SupportConversationUi?>

    /** Whether [conversation] may be re-read while polling. */
    val pollsConversation: Boolean

    suspend fun start(): ApiResult<SupportConversationUi>
    suspend fun messages(before: String? = null, after: String? = null, limit: Int? = null): ApiResult<SupportMessagePage>
    suspend fun sendText(text: String): ApiResult<SupportMessageUi>
    suspend fun sendAttachment(upload: SupportUpload, caption: String): ApiResult<SupportMessageUi>
    suspend fun markRead(upToMessageId: String): ApiResult<Unit>
    suspend fun attachment(messageId: String): ApiResult<ByteArray>
    suspend fun close(): ApiResult<SupportConversationUi>
}

private fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data), message)
    is ApiResult.Error -> this
}

private val NOT_AVAILABLE = ApiResult.Error("This action is not available.")

/** The member's own conversation; no conversation id ever leaves the backend's control. */
class MemberSupportChatSource(private val repository: SupportChatRepository) : SupportChatSource {

    override val pollsConversation = true

    override suspend fun conversation(): ApiResult<SupportConversationUi?> =
        repository.memberConversation().map { it?.toUi() }

    override suspend fun start() = repository.startConversation().map { it.toUi() }

    override suspend fun messages(before: String?, after: String?, limit: Int?) =
        repository.memberMessages(before, after, limit).map { page ->
            SupportMessagePage(page.messages.map { it.toUi() }, page.has_more)
        }

    override suspend fun sendText(text: String) = repository.sendMemberText(text).map { it.toUi() }

    override suspend fun sendAttachment(upload: SupportUpload, caption: String) =
        repository.sendMemberAttachment(upload, caption).map { it.toUi() }

    override suspend fun markRead(upToMessageId: String) = repository.markMemberRead(upToMessageId).map { }

    override suspend fun attachment(messageId: String) = repository.memberAttachment(messageId)

    override suspend fun close(): ApiResult<SupportConversationUi> = NOT_AVAILABLE
}

/** One conversation in an admin's inbox (ADMIN: assigned to them; SUPER_ADMIN: any, read-only). */
class AdminSupportChatSource(
    private val repository: SupportChatRepository,
    private val conversationId: String
) : SupportChatSource {

    override val pollsConversation = false

    override suspend fun conversation(): ApiResult<SupportConversationUi?> =
        repository.adminConversation(conversationId).map { it.toUi() }

    override suspend fun start(): ApiResult<SupportConversationUi> = NOT_AVAILABLE

    override suspend fun messages(before: String?, after: String?, limit: Int?) =
        repository.adminMessages(conversationId, before, after, limit).map { page ->
            SupportMessagePage(page.messages.map { it.toUi() }, page.has_more)
        }

    override suspend fun sendText(text: String) = repository.sendAdminText(conversationId, text).map { it.toUi() }

    override suspend fun sendAttachment(upload: SupportUpload, caption: String) =
        repository.sendAdminAttachment(conversationId, upload, caption).map { it.toUi() }

    override suspend fun markRead(upToMessageId: String) = repository.markAdminRead(conversationId, upToMessageId).map { }

    override suspend fun attachment(messageId: String) = repository.adminAttachment(messageId)

    override suspend fun close() = repository.closeConversation(conversationId).map { it.toUi() }
}
