package com.example.hawk_wallet.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.OrderRepository
import com.example.hawk_wallet.data.repository.PayoutMethodRepository
import com.example.hawk_wallet.ui.model.OfferUiModel
import com.example.hawk_wallet.ui.screens.paymethods.toUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Claim errors meaning the offer itself can no longer be claimed. */
private val OFFER_GONE_CODES = setOf("offer_not_found", "offer_unavailable", "offer_sold_out")

/** Claim errors meaning the chosen payout method is no longer usable; the list is reloaded. */
private val METHOD_CODES = setOf("no_active_payout_method", "payout_method_unavailable")

/**
 * One claim: Offer Details -> Choose Payment Method -> POST /api/orders/claim/.
 * The chosen payout method is where the PLATFORM will later pay the member;
 * it is sent with the claim and fixed on the order by the backend.
 *
 * One idempotency key is used for every attempt at claiming this offer, so a
 * retry after a lost response returns the original order instead of a second one.
 */
class ClaimViewModel(
    private val orderRepository: OrderRepository,
    private val payoutMethodRepository: PayoutMethodRepository,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClaimUiState())
    val uiState: StateFlow<ClaimUiState> = _uiState

    private var idempotencyKey: String? = null

    /** Starts a fresh claim for [offer], as listed by the backend. */
    fun start(offer: OfferUiModel) {
        idempotencyKey = newIdempotencyKey()
        _uiState.value = ClaimUiState(offer = offer)
    }

    /** Loads the member's active payout methods; called whenever the chooser opens. */
    fun loadMethods() {
        _uiState.update { it.copy(methodsLoading = true, methodsError = null) }
        viewModelScope.launch {
            when (val result = payoutMethodRepository.list()) {
                is ApiResult.Success -> _uiState.update { state ->
                    val active = result.data.map { it.toUi() }.filter { it.isActive }
                    // Keep the member's pick if it is still active, else the backend's default.
                    val selected = state.selectedMethodId?.takeIf { id -> active.any { it.id == id } }
                        ?: active.firstOrNull { it.isDefault }?.id
                    state.copy(
                        methodsLoading = false,
                        methodsLoaded = true,
                        methods = active,
                        selectedMethodId = selected
                    )
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(methodsLoading = false, methodsError = result.userMessage())
                }
            }
        }
    }

    fun selectMethod(id: Long) {
        _uiState.update { it.copy(selectedMethodId = id, claimError = null) }
    }

    fun claim() {
        val state = _uiState.value
        if (state.isClaiming || state.offerUnavailable) return
        val offer = state.offer ?: return
        val methodId = state.selectedMethodId ?: return
        val key = idempotencyKey ?: newIdempotencyKey().also { idempotencyKey = it }

        _uiState.update { it.copy(isClaiming = true, claimError = null) }
        viewModelScope.launch {
            when (val result = orderRepository.claim(offer.id, methodId, key)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isClaiming = false, claimedOrderId = result.data.id)
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isClaiming = false,
                            claimError = result.userMessage(),
                            offerUnavailable = result.code in OFFER_GONE_CODES
                        )
                    }
                    if (result.code in METHOD_CODES) loadMethods()
                }
            }
        }
    }

    /**
     * The screen has opened the new order's Pay-In. Only the flag and key are
     * cleared, so the leaving screen never sees an empty state; start() resets
     * everything for the next claim.
     */
    fun claimHandled() {
        idempotencyKey = null
        _uiState.update { it.copy(claimedOrderId = null) }
    }
}
