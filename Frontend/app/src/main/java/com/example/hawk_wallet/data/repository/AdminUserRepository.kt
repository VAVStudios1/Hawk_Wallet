package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.AdminUserApi
import com.example.hawk_wallet.data.remote.model.AdminUserDetailDto
import com.example.hawk_wallet.data.remote.model.AdminUserListData

/** The admin member directory. Values and counts are returned exactly as the backend sent them. */
class AdminUserRepository(private val api: AdminUserApi) {

    suspend fun users(isActive: Boolean?, isVerified: Boolean?, search: String?, page: Int): ApiResult<AdminUserListData> =
        safeApiCall {
            api.users(
                isActive = isActive?.toString(),
                isVerified = isVerified?.toString(),
                search = search?.ifBlank { null },
                page = page
            )
        }

    suspend fun user(id: Long): ApiResult<AdminUserDetailDto> = safeApiCall { api.user(id) }
}
