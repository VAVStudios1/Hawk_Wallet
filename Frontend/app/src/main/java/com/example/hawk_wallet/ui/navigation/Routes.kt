package com.example.hawk_wallet.ui.navigation

object HawkRoutes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home"
    const val ADMIN_DASHBOARD = "admin_dashboard"
    const val OTP = "otp/{mobileNumber}/{purpose}"

    fun otp(mobileNumber: String, purpose: String) = "otp/$mobileNumber/$purpose"

    // Mirrors the backend's User.Role values (core/models.py) exactly.
    fun destinationForRole(role: String?): String = when (role) {
        "ADMIN", "SUPER_ADMIN" -> ADMIN_DASHBOARD
        else -> HOME
    }
}
