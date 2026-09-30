package com.example.hawk_wallet.ui.screens.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.SupportChatRepository
import com.example.hawk_wallet.ui.screens.orders.HTTP_UNAUTHORIZED
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How often the member's unread support count is refreshed while the app is in the foreground. */
internal const val UNREAD_POLL_INTERVAL_MS = 8_000L

/**
 * The member's unread support messages, for the Home bell badge. The count
 * is the backend's own unread_count on the member's current conversation
 * (support messages newer than the member's read marker) - never counted
 * on the device. Opening Support Chat marks messages read through the
 * existing read endpoint, so the next refresh brings the badge down.
 */
class SupportUnreadViewModel(private val repository: SupportChatRepository) : ViewModel() {

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount

    private var refreshJob: Job? = null

    /** Re-reads the count now (e.g. when Home is shown again); an in-flight refresh is not duplicated. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { load() }
    }

    /**
     * Runs while the app is in the foreground for a logged-in member (the
     * caller cancels it otherwise): refreshes at once, then every
     * [UNREAD_POLL_INTERVAL_MS].
     */
    suspend fun pollWhileForeground() {
        while (true) {
            if (refreshJob?.isActive != true) load()
            delay(UNREAD_POLL_INTERVAL_MS)
        }
    }

    /** Not a member session (logged out, or an admin): no badge. */
    fun clear() {
        refreshJob?.cancel()
        _unreadCount.value = 0
    }

    private suspend fun load() {
        when (val result = repository.memberConversation()) {
            // No conversation yet means nothing unread.
            is ApiResult.Success -> _unreadCount.value = result.data?.unread_count ?: 0
            // A failed refresh keeps the last known count, except an expired session.
            is ApiResult.Error -> if (result.statusCode == HTTP_UNAUTHORIZED) _unreadCount.value = 0
        }
    }
}
