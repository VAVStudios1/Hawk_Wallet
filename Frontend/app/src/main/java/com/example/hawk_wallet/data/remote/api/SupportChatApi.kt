package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.AdminConversationDto
import com.example.hawk_wallet.data.remote.model.AdminConversationListData
import com.example.hawk_wallet.data.remote.model.AdminMessageDto
import com.example.hawk_wallet.data.remote.model.AdminMessagePageData
import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.MarkSupportReadRequest
import com.example.hawk_wallet.data.remote.model.MemberConversationData
import com.example.hawk_wallet.data.remote.model.MemberMessageDto
import com.example.hawk_wallet.data.remote.model.MemberMessagePageData
import com.example.hawk_wallet.data.remote.model.SendSupportTextRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Support chat, authenticated by the shared JWT interceptor. Member paths
 * never carry a conversation id: the backend always uses the caller's own
 * conversation. Message lists use before / after / limit cursors.
 */
interface SupportChatApi {

    // --- member ---------------------------------------------------------------

    @GET("api/support/conversation/")
    suspend fun memberConversation(): Response<ApiResponse<MemberConversationData>>

    /** Returns the OPEN conversation if one exists; never creates a second. */
    @POST("api/support/conversation/")
    suspend fun startConversation(): Response<ApiResponse<MemberConversationData>>

    @GET("api/support/conversation/messages/")
    suspend fun memberMessages(
        @Query("before") before: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int? = null
    ): Response<ApiResponse<MemberMessagePageData>>

    @POST("api/support/conversation/messages/")
    suspend fun sendMemberText(@Body request: SendSupportTextRequest): Response<ApiResponse<MemberMessageDto>>

    // Part names must match the backend's SendAttachmentSerializer exactly.
    @Multipart
    @POST("api/support/conversation/attachments/")
    suspend fun sendMemberAttachment(
        @Part("type") type: RequestBody,
        @Part("caption") caption: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<ApiResponse<MemberMessageDto>>

    @POST("api/support/conversation/read/")
    suspend fun markMemberRead(@Body request: MarkSupportReadRequest): Response<ApiResponse<MemberConversationData>>

    /** Raw attachment bytes (not the JSON envelope). */
    @GET("api/support/attachments/{id}/")
    suspend fun memberAttachment(@Path("id") messageId: String): Response<ResponseBody>

    // --- admin ----------------------------------------------------------------

    /** ADMIN: own conversations. SUPER_ADMIN: all, read-only. */
    @GET("api/admin/support/conversations/")
    suspend fun adminConversations(
        @Query("status") status: String? = null,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null
    ): Response<ApiResponse<AdminConversationListData>>

    /** Audited by the backend when a SUPER_ADMIN opens another admin's conversation. */
    @GET("api/admin/support/conversations/{id}/")
    suspend fun adminConversation(@Path("id") conversationId: String): Response<ApiResponse<AdminConversationDto>>

    @GET("api/admin/support/conversations/{id}/messages/")
    suspend fun adminMessages(
        @Path("id") conversationId: String,
        @Query("before") before: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int? = null
    ): Response<ApiResponse<AdminMessagePageData>>

    @POST("api/admin/support/conversations/{id}/messages/")
    suspend fun sendAdminText(
        @Path("id") conversationId: String,
        @Body request: SendSupportTextRequest
    ): Response<ApiResponse<AdminMessageDto>>

    @Multipart
    @POST("api/admin/support/conversations/{id}/attachments/")
    suspend fun sendAdminAttachment(
        @Path("id") conversationId: String,
        @Part("type") type: RequestBody,
        @Part("caption") caption: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<ApiResponse<AdminMessageDto>>

    @POST("api/admin/support/conversations/{id}/read/")
    suspend fun markAdminRead(
        @Path("id") conversationId: String,
        @Body request: MarkSupportReadRequest
    ): Response<ApiResponse<AdminConversationDto>>

    @POST("api/admin/support/conversations/{id}/close/")
    suspend fun closeConversation(@Path("id") conversationId: String): Response<ApiResponse<AdminConversationDto>>

    /** Raw attachment bytes; audited by the backend for a SUPER_ADMIN. */
    @GET("api/admin/support/attachments/{id}/")
    suspend fun adminAttachment(@Path("id") messageId: String): Response<ResponseBody>
}
