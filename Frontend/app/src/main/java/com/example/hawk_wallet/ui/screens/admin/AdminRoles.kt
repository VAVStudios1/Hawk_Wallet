package com.example.hawk_wallet.ui.screens.admin

// Mirror the backend's User.Role values (core/models.py) exactly. Used only
// to choose what the admin UI shows; the backend authorizes every request.
internal const val ROLE_ADMIN = "ADMIN"
internal const val ROLE_SUPER_ADMIN = "SUPER_ADMIN"

internal fun isSuperAdmin(role: String?): Boolean = role == ROLE_SUPER_ADMIN
