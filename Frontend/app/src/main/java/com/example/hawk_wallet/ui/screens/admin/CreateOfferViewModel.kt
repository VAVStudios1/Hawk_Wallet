package com.example.hawk_wallet.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.model.AdminOfferDto
import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID

/** The offer the backend created, with its server-computed claim preview. */
data class CreatedOfferUiModel(
    val id: Long,
    val orderAmount: String,
    val incomeRate: String,
    val incomeAmount: String,
    val payoutTarget: String,
    /** Null means no quantity limit. */
    val quantityAvailable: Int?,
    val isActive: Boolean,
    val payInUpiId: String
)

data class CreateOfferUiState(
    val orderAmount: String = "",
    val incomeRate: String = "",
    /** Blank means no quantity limit. */
    val quantity: String = "",
    /** Matches the backend model default: new offers start inactive unless switched on. */
    val isActive: Boolean = false,
    /** Picked image of the QR members pay for this offer's orders (not a payout method). */
    val payInQrUri: String? = null,
    val orderAmountError: String? = null,
    val incomeRateError: String? = null,
    val quantityError: String? = null,
    val payInQrError: String? = null,
    val isSubmitting: Boolean = false,
    val submitError: String? = null,
    /** Set once the backend created the offer. */
    val created: CreatedOfferUiModel? = null
)

private val WHOLE_RUPEES = Regex("^[0-9]{1,10}$")
private val RATE = Regex("^[0-9]{1,3}(\\.[0-9]{1,2})?$")
private val QUANTITY = Regex("^[0-9]{1,9}$")

/**
 * Admin creates an Order Offer via POST /api/admin/offers/.
 *
 * Only what the admin typed is sent; income and payout target come back
 * from the backend. Local checks are for form feedback only - the backend
 * validates everything again. One idempotency key covers every attempt at
 * the same values, so a retry after a lost response cannot create a second
 * offer; changing any value starts a new key.
 */
class CreateOfferViewModel(
    private val repository: AdminOrderRepository,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateOfferUiState())
    val uiState: StateFlow<CreateOfferUiState> = _uiState

    private var idempotencyKey: String = newIdempotencyKey()

    fun onOrderAmountChange(value: String) = edit { it.copy(orderAmount = value.trim(), orderAmountError = null) }

    fun onIncomeRateChange(value: String) = edit { it.copy(incomeRate = value.trim(), incomeRateError = null) }

    fun onQuantityChange(value: String) = edit { it.copy(quantity = value.trim(), quantityError = null) }

    fun onActiveChange(value: Boolean) = edit { it.copy(isActive = value) }

    fun onPayInQrSelected(uri: String) = edit { it.copy(payInQrUri = uri, payInQrError = null) }

    fun onPayInQrRemoved() = edit { it.copy(payInQrUri = null) }

    fun onPayInQrUnreadable() = edit {
        it.copy(
            payInQrUri = null,
            payInQrError = "We couldn't read that image. Please choose a clear photo of the QR code."
        )
    }

    private fun edit(change: (CreateOfferUiState) -> CreateOfferUiState) {
        if (_uiState.value.isSubmitting) return
        val before = _uiState.value
        val after = change(before).copy(submitError = null)
        if (after.copy(submitError = before.submitError) != before) idempotencyKey = newIdempotencyKey()
        _uiState.value = after
    }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting) return

        val amountError = when {
            !WHOLE_RUPEES.matches(state.orderAmount) -> "Enter the amount in whole rupees."
            state.orderAmount.trimStart('0').isEmpty() -> "Amount must be greater than zero."
            else -> null
        }
        val rateError = when {
            !RATE.matches(state.incomeRate) -> "Enter a rate like 4 or 4.25."
            // Comparison only, for form feedback; nothing is calculated.
            BigDecimal(state.incomeRate) > BigDecimal(100) -> "Rate must be between 0 and 100."
            else -> null
        }
        val quantityError = when {
            state.quantity.isEmpty() -> null
            !QUANTITY.matches(state.quantity) || state.quantity.trimStart('0').isEmpty() ->
                "Enter at least 1, or leave empty for no limit."
            else -> null
        }
        val qrUri = state.payInQrUri
        val qrError = if (qrUri == null) "Upload the Pay-In QR." else null
        if (amountError != null || rateError != null || quantityError != null || qrUri == null) {
            _uiState.update {
                it.copy(
                    orderAmountError = amountError,
                    incomeRateError = rateError,
                    quantityError = quantityError,
                    payInQrError = qrError
                )
            }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            val result = repository.createOffer(
                orderAmount = state.orderAmount,
                incomeRate = state.incomeRate,
                quantityAvailable = state.quantity.ifEmpty { null }?.toInt(),
                isActive = state.isActive,
                payInQrUri = qrUri,
                idempotencyKey = idempotencyKey
            )
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isSubmitting = false, created = result.data.toUi())
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isSubmitting = false, submitError = result.userMessage())
                }
            }
        }
    }

    /** Clears the form after a successful create, ready for the next offer. */
    fun startNew() {
        if (_uiState.value.isSubmitting) return
        idempotencyKey = newIdempotencyKey()
        _uiState.value = CreateOfferUiState()
    }
}

private fun AdminOfferDto.toUi() = CreatedOfferUiModel(
    id = id,
    orderAmount = order_amount,
    incomeRate = income_rate,
    incomeAmount = income_amount,
    payoutTarget = payout_target,
    quantityAvailable = quantity_available,
    isActive = is_active,
    payInUpiId = pay_in_upi_id
)
