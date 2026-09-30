package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.SupportChatApi
import com.example.hawk_wallet.data.remote.model.AdminConversationDto
import com.example.hawk_wallet.data.remote.model.AdminConversationListData
import com.example.hawk_wallet.data.remote.model.AdminMessageDto
import com.example.hawk_wallet.data.remote.model.AdminMessagePageData
import com.example.hawk_wallet.data.remote.model.MarkSupportReadRequest
import com.example.hawk_wallet.data.remote.model.MemberConversationDto
import com.example.hawk_wallet.data.remote.model.MemberMessageDto
import com.example.hawk_wallet.data.remote.model.MemberMessagePageData
import com.example.hawk_wallet.data.remote.model.SendSupportTextRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

private val TEXT = "text/plain".toMediaType()

/**
 * Support chat for members and admins. The backend decides who may see or
 * act on a conversation, routes it and owns every status and read marker;
 * values are returned as it sent them. Message contents are never logged.
 */
class SupportChatRepository(
    private val api: SupportChatApi,
    private val files: SupportAttachmentFiles
) {

    // --- member ---------------------------------------------------------------

    /** The member's current conversation, or null when they have never started one. */
    suspend fun memberConversation(): ApiResult<MemberConversationDto?> =
        when (val result = safeApiCall { api.memberConversation() }) {
            is ApiResult.Success -> ApiResult.Success(result.data.conversation, result.message)
            is ApiResult.Error -> result
        }

    /** Returns the existing OPEN conversation or a newly routed one. */
    suspend fun startConversation(): ApiResult<MemberConversationDto> =
        when (val result = safeApiCall { api.startConversation() }) {
            is ApiResult.Success -> result.data.conversation
                ?.let { ApiResult.Success(it, result.message) }
                ?: ApiResult.Error("Something went wrong. Please try again.")
            is ApiResult.Error -> result
        }

    suspend fun memberMessages(before: String? = null, after: String? = null, limit: Int? = null): ApiResult<MemberMessagePageData> =
        safeApiCall { api.memberMessages(before, after, limit) }

    suspend fun sendMemberText(text: String): ApiResult<MemberMessageDto> =
        safeApiCall { api.sendMemberText(SendSupportTextRequest(text)) }

    suspend fun sendMemberAttachment(upload: SupportUpload, caption: String): ApiResult<MemberMessageDto> {
        val (type, captionPart, filePart) = parts(upload, caption)
        return safeApiCall { api.sendMemberAttachment(type, captionPart, filePart) }
    }

    suspend fun markMemberRead(upToMessageId: String): ApiResult<MemberConversationDto?> =
        when (val result = safeApiCall { api.markMemberRead(MarkSupportReadRequest(upToMessageId)) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.conversation, result.message)
            is ApiResult.Error -> result
        }

    suspend fun memberAttachment(messageId: String): ApiResult<ByteArray> =
        safeBytesCall { api.memberAttachment(messageId) }

    // --- admin ----------------------------------------------------------------

    suspend fun adminConversations(status: String?, page: Int): ApiResult<AdminConversationListData> =
        safeApiCall { api.adminConversations(status = status, page = page) }

    suspend fun adminConversation(conversationId: String): ApiResult<AdminConversationDto> =
        safeApiCall { api.adminConversation(conversationId) }

    suspend fun adminMessages(
        conversationId: String,
        before: String? = null,
        after: String? = null,
        limit: Int? = null
    ): ApiResult<AdminMessagePageData> = safeApiCall { api.adminMessages(conversationId, before, after, limit) }

    suspend fun sendAdminText(conversationId: String, text: String): ApiResult<AdminMessageDto> =
        safeApiCall { api.sendAdminText(conversationId, SendSupportTextRequest(text)) }

    suspend fun sendAdminAttachment(conversationId: String, upload: SupportUpload, caption: String): ApiResult<AdminMessageDto> {
        val (type, captionPart, filePart) = parts(upload, caption)
        return safeApiCall { api.sendAdminAttachment(conversationId, type, captionPart, filePart) }
    }

    suspend fun markAdminRead(conversationId: String, upToMessageId: String): ApiResult<AdminConversationDto> =
        safeApiCall { api.markAdminRead(conversationId, MarkSupportReadRequest(upToMessageId)) }

    suspend fun closeConversation(conversationId: String): ApiResult<AdminConversationDto> =
        safeApiCall { api.closeConversation(conversationId) }

    suspend fun adminAttachment(messageId: String): ApiResult<ByteArray> =
        safeBytesCall { api.adminAttachment(messageId) }

    // --- files ----------------------------------------------------------------

    /** Reads a picked content:// URI into an upload, or an [ApiResult.Error] with a user-facing reason. */
    suspend fun readAttachment(uri: String, kind: SupportAttachmentKind): ApiResult<SupportUpload> =
        try {
            ApiResult.Success(files.read(uri, kind), "")
        } catch (e: SupportAttachmentException) {
            ApiResult.Error(e.message ?: "We couldn't read that file.")
        }

    /** Copies a downloaded attachment into the app's private cache for another app to open. */
    suspend fun saveForViewing(bytes: ByteArray, fileName: String): File = files.saveForViewing(bytes, fileName)

    /** Deletes a picked attachment's private cache copy (after sending or when removed). */
    suspend fun discardAttachment(upload: SupportUpload) = files.discard(upload)

    private fun parts(upload: SupportUpload, caption: String) = Triple(
        upload.kind.apiType.toRequestBody(TEXT),
        caption.toRequestBody(TEXT),
        // The complete, closed cache copy: OkHttp streams the whole file and
        // declares its real length as the part's Content-Length.
        MultipartBody.Part.createFormData("file", upload.fileName, upload.file.asRequestBody(upload.mimeType.toMediaType()))
    )
}
