package com.example.hawk_wallet.ui.navigation

object HawkRoutes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home"
    const val ADMIN_DASHBOARD = "admin_dashboard"

    // Wallet tab: the member's payout (payment) methods and the add flow.
    const val WALLET = "wallet"
    const val ADD_PAYMENT_METHOD = "add_payment_method"
    const val PAYMENT_METHOD_DETAILS = "payment_method_details"
    const val OTP = "otp/{mobileNumber}/{purpose}"

    fun otp(mobileNumber: String, purpose: String) = "otp/$mobileNumber/$purpose"

    // Mirrors the backend's User.Role values (core/models.py) exactly.
    fun destinationForRole(role: String?): String = when (role) {
        "ADMIN", "SUPER_ADMIN" -> ADMIN_DASHBOARD
        else -> HOME
    }
}
