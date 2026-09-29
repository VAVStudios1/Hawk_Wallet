package com.example.hawk_wallet.ui.screens.orders

import com.example.hawk_wallet.data.remote.model.MemberPayInDto
import com.example.hawk_wallet.data.remote.model.OfferDto
import com.example.hawk_wallet.data.remote.model.OrderDto
import com.example.hawk_wallet.data.remote.model.PayInIntentDto
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.model.OfferUiModel
import com.example.hawk_wallet.ui.model.OrderPayoutMethodUiModel
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.OrderUiModel
import com.example.hawk_wallet.ui.model.PayInIntentUiModel
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.model.PayInSummaryUiModel

internal const val HTTP_UNAUTHORIZED = 401
internal const val SESSION_EXPIRED = "Your session has expired. Please log in again."

/** The backend's stable error code (errors.code), if the response carried one. */
internal val ApiResult.Error.code: String?
    get() = fieldErrors["code"]?.firstOrNull()

/** The backend's own message, except a 401, which always means the session has expired. */
internal fun ApiResult.Error.userMessage(): String =
    if (statusCode == HTTP_UNAUTHORIZED) SESSION_EXPIRED else message

internal fun OfferDto.toUi() = OfferUiModel(
    id = id,
    orderAmount = order_amount,
    incomeRate = income_rate,
    incomeAmount = income_amount,
    payoutTarget = payout_target,
    validTo = valid_to
)

internal fun OrderDto.toUi() = OrderUiModel(
    id = id,
    reference = reference,
    status = OrderStatus.fromApi(status),
    orderAmount = order_amount,
    incomeRate = income_rate,
    incomeAmount = income_amount,
    payoutTarget = payout_target,
    payoutMethod = OrderPayoutMethodUiModel(
        providerCode = payout_method.provider_code,
        providerName = payout_method.provider_name,
        upiId = payout_method.upi_id
    ),
    claimedAt = claimed_at,
    expiresAt = expires_at,
    activatedAt = activated_at,
    completedAt = completed_at,
    latestPayIn = latest_pay_in?.toUi()
)

internal fun MemberPayInDto.toUi() = PayInSummaryUiModel(
    status = PayInStatus.fromApi(status),
    amount = amount,
    utr = utr,
    rejectionReason = rejection_reason,
    submittedAt = submitted_at
)

internal fun PayInIntentDto.toUi() = PayInIntentUiModel(
    orderReference = order_reference,
    amount = amount,
    payeeVpa = payee_vpa,
    payeeName = payee_name,
    upiUri = upi_uri,
    intentToken = intent_token
)
