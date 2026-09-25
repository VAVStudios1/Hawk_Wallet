package com.example.hawk_wallet.data.remote.model

/** GET /api/payout-methods/ -> data. */
data class PayoutMethodListData(
    val methods: List<PayoutMethodListItemDto>
)

data class PayoutMethodListItemDto(
    val id: Long,
    val provider: PayoutProviderDto,
    val label: String,
    val upi_id: String,
    val is_default: Boolean,
    val is_active: Boolean,
    val created_at: String,
    val updated_at: String
)

data class PayoutProviderDto(
    val id: Long,
    val code: String,
    val display_name: String
)

/** POST add / default / deactivate -> data (flat provider fields). */
data class PayoutMethodDto(
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
