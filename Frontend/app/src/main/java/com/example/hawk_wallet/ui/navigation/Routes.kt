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

    /** Wallet back-stack entry key: which Payment Methods tab (BUY / SELL) to open on. */
    const val WALLET_TAB_KEY = "wallet_tab"

    // PERSONAL / BUY accounts (paid FROM) are the BUY tab of WALLET; this adds one.
    const val ADD_BUY_METHOD = "add_buy_method"
    const val OTP = "otp/{mobileNumber}/{purpose}"

    // Orders tab: offers -> details -> payout method -> claim -> pay-in -> status.
    const val ORDERS = "orders"
    const val OFFER_DETAILS = "offer_details"
    const val CHOOSE_PAYOUT_METHOD = "choose_payout_method"
    const val ORDER_ID_ARG = "orderId"
    const val PAY_IN = "pay_in/{$ORDER_ID_ARG}"
    const val ORDER_STATUS = "order_status/{$ORDER_ID_ARG}"

    // Team tab: overview -> all members (optionally one level) / commission history.
    const val TEAM = "team"
    const val TEAM_LEVEL_ARG = "level"
    const val TEAM_MEMBERS = "team_members?$TEAM_LEVEL_ARG={$TEAM_LEVEL_ARG}"
    const val COMMISSION_HISTORY = "commission_history"

    fun teamMembers(level: Int?) = if (level == null) "team_members" else "team_members?$TEAM_LEVEL_ARG=$level"

    // Profile tab: overview -> personal information / change password.
    const val PROFILE = "profile"
    const val PROFILE_PERSONAL_INFO = "profile_personal_info"
    const val PROFILE_CHANGE_PASSWORD = "profile_change_password"

    /** Profile back-stack entry key: a one-shot snackbar message from a sub-screen. */
    const val PROFILE_MESSAGE_KEY = "profile_message"

    // Admin: dashboard -> all orders -> one order (pay-in review); dashboard -> create offer.
    const val ADMIN_ORDERS = "admin_orders"
    const val ADMIN_CREATE_OFFER = "admin_create_offer"
    const val ADMIN_ORDER_DETAILS = "admin_order_details/{$ORDER_ID_ARG}"

    fun adminOrderDetails(orderId: Long) = "admin_order_details/$orderId"

    fun payIn(orderId: Long) = "pay_in/$orderId"
    fun orderStatus(orderId: Long) = "order_status/$orderId"

    fun otp(mobileNumber: String, purpose: String) = "otp/$mobileNumber/$purpose"

    // Mirrors the backend's User.Role values (core/models.py) exactly.
    fun destinationForRole(role: String?): String = when (role) {
        "ADMIN", "SUPER_ADMIN" -> ADMIN_DASHBOARD
        else -> HOME
    }
}
