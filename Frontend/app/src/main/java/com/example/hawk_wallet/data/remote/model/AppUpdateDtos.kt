package com.example.hawk_wallet.data.remote.model

/**
 * GET api/app/update-check/ data. When no usable release is published the
 * release fields are null and nothing is forced.
 */
data class UpdateCheckDto(
    val current_version: String,
    val latest_version: String?,
    val minimum_supported_version: String?,
    val supported: Boolean,
    val update_available: Boolean,
    val force_update: Boolean,
    val apk_url: String?,
    val apk_sha256: String?,
    val apk_size: Long?,
    val release_notes: List<String>?
)
