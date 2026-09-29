package com.example.hawk_wallet.ui.screens.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.TeamRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How many members / commissions the Team tab previews before "See All". */
private const val PREVIEW_SIZE = 5

/**
 * The Team tab, backed by GET /api/team/referral/, /summary/, and the first
 * page of /members/ and /commissions/. Everything is shown exactly as the
 * backend returned it; a failed refresh keeps the last good data.
 */
class TeamViewModel(private val repository: TeamRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(TeamUiState())
    val uiState: StateFlow<TeamUiState> = _uiState

    /** Loads everything; called whenever the Team screen opens and on pull-to-refresh. */
    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val referral = async { repository.referral() }
            val summary = async { repository.summary() }
            val members = async { repository.members(level = null, page = 1, pageSize = PREVIEW_SIZE) }
            val commissions = async { repository.commissions(page = 1, pageSize = PREVIEW_SIZE) }
            val referralResult = referral.await()
            val summaryResult = summary.await()
            val membersResult = members.await()
            val commissionsResult = commissions.await()

            val firstError = listOf(referralResult, summaryResult, membersResult, commissionsResult)
                .filterIsInstance<ApiResult.Error>()
                .firstOrNull()

            _uiState.update { state ->
                val membersPage = (membersResult as? ApiResult.Success)?.data
                val commissionsPage = (commissionsResult as? ApiResult.Success)?.data
                state.copy(
                    isLoading = false,
                    loaded = true,
                    referral = (referralResult as? ApiResult.Success)?.data?.toUi() ?: state.referral,
                    levels = (summaryResult as? ApiResult.Success)?.data?.levels?.map { it.toUi() } ?: state.levels,
                    recentMembers = membersPage?.members?.mapIndexed { index, member -> member.toUi(index) }
                        ?: state.recentMembers,
                    membersTotal = membersPage?.total ?: state.membersTotal,
                    recentCommissions = commissionsPage?.commissions?.map { it.toUi() } ?: state.recentCommissions,
                    commissionsTotal = commissionsPage?.total ?: state.commissionsTotal,
                    errorMessage = firstError?.userMessage()
                )
            }
        }
    }
}
