package com.example.hawk_wallet.data.remote.model

import com.google.gson.JsonElement

// SUPER_ADMIN admin management, audit log, and the admin reports. Every count
// and amount is computed by the backend; amounts are its decimal strings.

// --- Admin management: /api/admin/admins/ -----------------------------------

/** What the data attributes to one admin; members, orders and pending pay-ins belong to no admin. */
data class AdminWorkloadDto(
    val open_support_conversations: Int,
    val pending_payouts_created: Int,
    val pay_ins_reviewed: Int,
    val payouts_paid: Int
)

data class AdminAccountDto(
    val id: Long,
    val name: String,
    val mobile_number_masked: String,
    val role: String,
    val is_verified: Boolean,
    val is_active: Boolean,
    val date_joined: String,
    val workload: AdminWorkloadDto,
    val is_self: Boolean,
    /** ADMIN accounts other than the caller's own; the backend enforces it either way. */
    val can_change_status: Boolean
)

data class AdminAccountListData(
    val admins: List<AdminAccountDto>,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val has_next: Boolean
)

/** POST /api/admin/admins/ body. There is no role field: the backend always creates an ADMIN. */
data class CreateAdminRequest(
    val name: String,
    val mobile_number: String,
    val password: String,
    val confirm_password: String
)

// --- Audit log: /api/admin/audit-events/ ------------------------------------

data class AuditActorDto(
    val id: Long,
    val name: String,
    val role: String,
    val mobile_number_masked: String
)

data class AuditEventDto(
    val id: Long,
    val action: String,
    val action_label: String,
    /** Null for system actions. */
    val actor: AuditActorDto?,
    val target_type: String,
    val target_id: String,
    val created_at: String,
    /** Detail only. */
    val ip_address: String? = null,
    /** Detail only: identifiers only, with sensitive keys already removed by the backend. */
    val metadata: Map<String, JsonElement>? = null
)

data class AuditActionDto(
    val value: String,
    val label: String
)

data class AuditEventListData(
    val events: List<AuditEventDto>,
    /** The action filter's options, from the backend. */
    val actions: List<AuditActionDto>,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val has_next: Boolean
)

// --- Reports: /api/admin/reports/ and /api/admin/system-report/ -------------

data class CountsByStatusDto(
    val total: Int,
    val by_status: Map<String, Int>
)

data class PayInReportDto(
    val total: Int,
    val by_status: Map<String, Int>,
    val confirmed_amount: String
)

data class PayoutReportDto(
    val total: Int,
    val by_status: Map<String, Int>,
    val paid_amount: String,
    val pending_amount: String
)

data class MemberReportDto(
    val new: Int,
    val verified: Int,
    val active: Int
)

/** Each section counts records created in the range, by their current status (see the backend docs). */
data class AdminReportDto(
    val range: String,
    val start_date: String,
    val end_date: String,
    val timezone: String,
    val orders: CountsByStatusDto,
    val pay_ins: PayInReportDto,
    val payouts: PayoutReportDto,
    val members: MemberReportDto,
    val support: CountsByStatusDto
)

data class TotalActiveDto(
    val total: Int,
    val active: Int
)

data class AdminWorkloadRowDto(
    val id: Long,
    val name: String,
    val role: String,
    val is_active: Boolean,
    val open_support_conversations: Int,
    val pending_payouts_created: Int,
    val pay_ins_reviewed: Int,
    val payouts_paid: Int
)

data class SystemReportDto(
    val admins: TotalActiveDto,
    val members: TotalActiveDto,
    val active_orders: Int,
    val pending_payins: Int,
    val pending_payouts: Int,
    val open_support: Int,
    /** OPEN chats no active ADMIN can answer (e.g. none left when theirs was deactivated). */
    val stranded_open_support: Int,
    /** Listed by name - an overview, never a ranking. */
    val workload: List<AdminWorkloadRowDto>
)
