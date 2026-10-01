package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.AdminManagementApi
import com.example.hawk_wallet.data.remote.model.AdminAccountDto
import com.example.hawk_wallet.data.remote.model.AdminAccountListData
import com.example.hawk_wallet.data.remote.model.AdminReportDto
import com.example.hawk_wallet.data.remote.model.AuditEventDto
import com.example.hawk_wallet.data.remote.model.AuditEventListData
import com.example.hawk_wallet.data.remote.model.CreateAdminRequest
import com.example.hawk_wallet.data.remote.model.SystemReportDto

/**
 * Admin management, audit log and reports. The backend decides who may call
 * what (SUPER_ADMIN for everything but reports) and computes every value.
 */
class AdminManagementRepository(private val api: AdminManagementApi) {

    suspend fun admins(isActive: Boolean?, search: String?, page: Int): ApiResult<AdminAccountListData> =
        safeApiCall { api.admins(isActive = isActive?.toString(), search = search?.ifBlank { null }, page = page) }

    suspend fun admin(id: Long): ApiResult<AdminAccountDto> = safeApiCall { api.admin(id) }

    /** The backend always creates an ADMIN; the account verifies its mobile with an OTP on first login. */
    suspend fun createAdmin(name: String, mobileNumber: String, password: String, confirmPassword: String): ApiResult<AdminAccountDto> =
        safeApiCall { api.createAdmin(CreateAdminRequest(name, mobileNumber, password, confirmPassword)) }

    suspend fun setAdminActive(id: Long, active: Boolean): ApiResult<AdminAccountDto> =
        safeApiCall { if (active) api.activateAdmin(id) else api.deactivateAdmin(id) }

    suspend fun auditEvents(action: String?, dateFrom: String?, dateTo: String?, search: String?, page: Int): ApiResult<AuditEventListData> =
        safeApiCall {
            api.auditEvents(action = action, dateFrom = dateFrom, dateTo = dateTo, search = search?.ifBlank { null }, page = page)
        }

    suspend fun auditEvent(id: Long): ApiResult<AuditEventDto> = safeApiCall { api.auditEvent(id) }

    suspend fun report(range: String, startDate: String?, endDate: String?): ApiResult<AdminReportDto> =
        safeApiCall { api.report(range, startDate, endDate) }

    suspend fun systemReport(): ApiResult<SystemReportDto> = safeApiCall { api.systemReport() }
}
