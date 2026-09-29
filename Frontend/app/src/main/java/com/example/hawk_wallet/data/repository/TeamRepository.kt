package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.TeamApi
import com.example.hawk_wallet.data.remote.model.CommissionPageDto
import com.example.hawk_wallet.data.remote.model.ReferralInfoDto
import com.example.hawk_wallet.data.remote.model.TeamMembersPageDto
import com.example.hawk_wallet.data.remote.model.TeamSummaryDto

/**
 * The member's team, referral code and commission history. The backend
 * computes every count, level, rate and amount; values are returned
 * exactly as it sent them.
 */
class TeamRepository(private val api: TeamApi) {

    suspend fun referral(): ApiResult<ReferralInfoDto> = safeApiCall { api.referral() }

    suspend fun summary(): ApiResult<TeamSummaryDto> = safeApiCall { api.summary() }

    suspend fun members(level: Int?, page: Int, pageSize: Int): ApiResult<TeamMembersPageDto> =
        safeApiCall { api.members(level, page, pageSize) }

    suspend fun commissions(page: Int, pageSize: Int): ApiResult<CommissionPageDto> =
        safeApiCall { api.commissions(page, pageSize) }
}
