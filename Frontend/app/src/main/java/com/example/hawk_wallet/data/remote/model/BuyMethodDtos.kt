package com.example.hawk_wallet.data.remote.model

/** GET /api/buy-methods/ -> data. Same shape as the payout-method list, but PERSONAL / BUY. */
data class BuyMethodListData(
    val methods: List<BuyMethodListItemDto>
)

data class BuyMethodListItemDto(
    val id: Long,
    val provider: PayoutProviderDto,
    val label: String,
    val upi_id: String,
    val is_default: Boolean,
    val is_active: Boolean,
    val created_at: String,
    val updated_at: String
)

/** POST /api/buy-methods/ body (JSON; a BUY method has no QR). */
data class AddBuyMethodRequest(
    val provider_code: String,
    val upi_id: String,
    val label: String
)

/** POST add / default / deactivate -> data (flat provider fields). */
data class BuyMethodDto(
    val id: Long,
    val provider: Long,
    val provider_code: String,
    val provider_name: String,
    val label: String,
    val upi_id: String,
    val is_default: Boolean,
    val is_active: Boolean,
    val created_at: String,
    val updated_at: String
)

/** The BUY method recorded on a pay-in: the member's own account the payment came from. */
data class BuyMethodSummaryDto(
    val id: Long,
    val provider_code: String,
    val provider_name: String,
    val label: String,
    val upi_id: String
)
