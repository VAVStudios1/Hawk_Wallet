package com.example.hawk_wallet.data.remote.model

/**
 * Team / referral API response data (backend team/serializers.py). Money
 * and rate values are the backend's decimal strings and are only ever
 * formatted for display - never parsed into Float/Double or recalculated.
 * Every count, level and total is the backend's; the app derives none.
 */

/** GET /api/team/referral/ - [referral_link] is null until the backend has a link base URL. */
data class ReferralInfoDto(
    val referral_code: String,
    val referral_link: String?,
    val direct_team_count: Int,
    val total_team_count: Int,
    val total_commission: String
)

/** GET /api/team/summary/ */
data class TeamSummaryDto(
    val direct_team_count: Int,
    val total_team_count: Int,
    val commission_level_team_count: Int,
    val total_commission: String,
    val commission_count: Int,
    val levels: List<TeamLevelDto>
)

/** One team level. [rate] is a percent string ("0.3000" = 0.3%), null when not [commission_eligible]. */
data class TeamLevelDto(
    val level: Int,
    val commission_eligible: Boolean,
    val rate: String?,
    val member_count: Int,
    val commission_count: Int,
    val commission_total: String
)

/** GET /api/team/members/ - one page. */
data class TeamMembersPageDto(
    val members: List<TeamMemberDto>,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val has_next: Boolean
)

/** Safe member view: the API sends no id and only a masked mobile number. */
data class TeamMemberDto(
    val level: Int,
    val name: String,
    val mobile_number_masked: String,
    val joined_at: String
)

/** GET /api/team/commissions/ - one page, newest first. */
data class CommissionPageDto(
    val commissions: List<CommissionDto>,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val has_next: Boolean
)

/** No UTR: the backend deliberately never exposes it here. */
data class CommissionDto(
    val id: Long,
    val amount: String,
    val level: Int,
    val rate: String,
    val base_amount: String,
    val source_name: String,
    val source_mobile_number_masked: String,
    val payin_id: Long,
    val order_reference: String,
    val created_at: String
)
