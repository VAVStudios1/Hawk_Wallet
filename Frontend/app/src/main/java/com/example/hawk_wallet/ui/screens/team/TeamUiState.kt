package com.example.hawk_wallet.ui.screens.team

import com.example.hawk_wallet.data.remote.model.CommissionDto
import com.example.hawk_wallet.data.remote.model.ReferralInfoDto
import com.example.hawk_wallet.data.remote.model.TeamLevelDto
import com.example.hawk_wallet.data.remote.model.TeamMemberDto
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.format.DisplayFormat

private const val HTTP_UNAUTHORIZED = 401
private const val SESSION_EXPIRED = "Your session has expired. Please log in again."

internal fun ApiResult.Error.userMessage(): String =
    if (statusCode == HTTP_UNAUTHORIZED) SESSION_EXPIRED else message

/** GET /api/team/referral/, formatted for display only. */
data class ReferralUi(
    val code: String,
    /** Null until the backend provides an invite link; never built on the device. */
    val link: String?,
    val directTeam: String,
    val totalTeam: String,
    val totalCommission: String,
    /** The backend's total team count is zero. */
    val hasNoTeam: Boolean
)

/** One level from GET /api/team/summary/. */
data class TeamLevelUi(
    val level: Int,
    val commissionEligible: Boolean,
    /** "0.3%"; null when the level earns no commission. */
    val rate: String?,
    val memberCount: Int,
    val commissionCount: Int,
    val commissionTotal: String
)

data class TeamMemberUi(
    val key: String,
    val level: Int,
    val name: String,
    val mobileMasked: String,
    val joined: String
)

data class CommissionUi(
    val id: Long,
    val amount: String,
    val level: Int,
    val rate: String,
    val baseAmount: String,
    val sourceName: String,
    val sourceMobileMasked: String,
    val payInId: Long,
    val orderReference: String,
    val createdAt: String
)

/** The Team tab: referral + summary + the first members and commissions. */
data class TeamUiState(
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val referral: ReferralUi? = null,
    val levels: List<TeamLevelUi> = emptyList(),
    val recentMembers: List<TeamMemberUi> = emptyList(),
    val membersTotal: Int = 0,
    val recentCommissions: List<CommissionUi> = emptyList(),
    val commissionsTotal: Int = 0,
    val errorMessage: String? = null
)

/** One paged list (members or commission history). */
data class PagedListUiState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loaded: Boolean = false,
    val page: Int = 0,
    val hasNext: Boolean = false,
    val total: Int = 0,
    val errorMessage: String? = null
)

// Formatting only: every figure is shown exactly as the backend computed it.

internal fun ReferralInfoDto.toUi() = ReferralUi(
    code = referral_code,
    link = referral_link?.takeIf { it.isNotBlank() },
    directTeam = direct_team_count.toString(),
    totalTeam = total_team_count.toString(),
    totalCommission = DisplayFormat.rupees(total_commission),
    hasNoTeam = total_team_count == 0
)

internal fun TeamLevelDto.toUi() = TeamLevelUi(
    level = level,
    commissionEligible = commission_eligible,
    rate = if (commission_eligible) rate?.let(DisplayFormat::percent) else null,
    memberCount = member_count,
    commissionCount = commission_count,
    commissionTotal = DisplayFormat.rupees(commission_total)
)

// The API sends no member id; position + fields keeps list keys unique.
internal fun TeamMemberDto.toUi(index: Int) = TeamMemberUi(
    key = "$index-$level-$mobile_number_masked-$joined_at",
    level = level,
    name = name,
    mobileMasked = mobile_number_masked,
    joined = DisplayFormat.dateTime(joined_at)
)

internal fun CommissionDto.toUi() = CommissionUi(
    id = id,
    amount = DisplayFormat.rupees(amount),
    level = level,
    rate = DisplayFormat.percent(rate),
    baseAmount = DisplayFormat.rupees(base_amount),
    sourceName = source_name,
    sourceMobileMasked = source_mobile_number_masked,
    payInId = payin_id,
    orderReference = order_reference,
    createdAt = DisplayFormat.dateTime(created_at)
)
