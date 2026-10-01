package com.example.hawk_wallet.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.repository.AdminDashboardRepository
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.AdminUserRepository
import com.example.hawk_wallet.data.repository.AuthRepository
import com.example.hawk_wallet.data.repository.BuyMethodRepository
import com.example.hawk_wallet.data.repository.HomeRepository
import com.example.hawk_wallet.data.repository.OrderRepository
import com.example.hawk_wallet.data.repository.PayoutMethodRepository
import com.example.hawk_wallet.data.repository.ProfileRepository
import com.example.hawk_wallet.data.repository.SupportChatRepository
import com.example.hawk_wallet.data.repository.TeamRepository
import com.example.hawk_wallet.ui.components.AdminBottomNavigation
import com.example.hawk_wallet.ui.components.AdminNavDestination
import com.example.hawk_wallet.ui.components.HawkNavDestination
import com.example.hawk_wallet.ui.model.QuickAction
import com.example.hawk_wallet.ui.screens.admin.AddAdminScreen
import com.example.hawk_wallet.ui.screens.admin.AddAdminViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminAccountDetailScreen
import com.example.hawk_wallet.ui.screens.admin.AdminAccountDetailViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminAccountsScreen
import com.example.hawk_wallet.ui.screens.admin.AdminAccountsViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminDashboardActions
import com.example.hawk_wallet.ui.screens.admin.AdminOrderFilter
import com.example.hawk_wallet.ui.screens.admin.AdminReportsScreen
import com.example.hawk_wallet.ui.screens.admin.AdminReportsViewModel
import com.example.hawk_wallet.ui.screens.admin.AuditEventDetailScreen
import com.example.hawk_wallet.ui.screens.admin.AuditEventDetailViewModel
import com.example.hawk_wallet.ui.screens.admin.AuditLogScreen
import com.example.hawk_wallet.ui.screens.admin.AuditLogViewModel
import com.example.hawk_wallet.ui.screens.admin.SuperAdminOnly
import com.example.hawk_wallet.ui.screens.admin.SystemReportScreen
import com.example.hawk_wallet.ui.screens.admin.SystemReportViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminPayInDetailScreen
import com.example.hawk_wallet.ui.screens.admin.AdminPayInDetailViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminPayInFilter
import com.example.hawk_wallet.ui.screens.admin.AdminPayInsScreen
import com.example.hawk_wallet.ui.screens.admin.AdminPayInsViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminPayoutDetailScreen
import com.example.hawk_wallet.ui.screens.admin.AdminPayoutDetailViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminPayoutFilter
import com.example.hawk_wallet.ui.screens.admin.AdminPayoutsScreen
import com.example.hawk_wallet.ui.screens.admin.AdminPayoutsViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminUserDetailScreen
import com.example.hawk_wallet.ui.screens.admin.AdminUserDetailViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminUsersScreen
import com.example.hawk_wallet.ui.screens.admin.AdminUsersViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminDashboardScreen
import com.example.hawk_wallet.ui.screens.admin.AdminDashboardViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminMoreItem
import com.example.hawk_wallet.ui.screens.admin.AdminMoreScreen
import com.example.hawk_wallet.ui.screens.admin.AdminOrderDetailsScreen
import com.example.hawk_wallet.ui.screens.admin.AdminOrderDetailsViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminOrdersScreen
import com.example.hawk_wallet.ui.screens.admin.AdminOrdersViewModel
import com.example.hawk_wallet.ui.screens.admin.AdminProfileActions
import com.example.hawk_wallet.ui.screens.admin.AdminProfileScreen
import com.example.hawk_wallet.ui.screens.admin.CreateOfferScreen
import com.example.hawk_wallet.ui.screens.admin.CreateOfferViewModel
import com.example.hawk_wallet.ui.screens.auth.forgot.ForgotPasswordMobileScreen
import com.example.hawk_wallet.ui.screens.auth.forgot.ForgotPasswordOtpScreen
import com.example.hawk_wallet.ui.screens.auth.forgot.ForgotPasswordViewModel
import com.example.hawk_wallet.ui.screens.auth.forgot.ResetPasswordScreen
import com.example.hawk_wallet.ui.screens.auth.login.LoginScreen
import com.example.hawk_wallet.ui.screens.auth.login.LoginViewModel
import com.example.hawk_wallet.ui.screens.auth.otp.OtpVerificationScreen
import com.example.hawk_wallet.ui.screens.auth.otp.OtpViewModel
import com.example.hawk_wallet.ui.screens.auth.register.RegisterScreen
import com.example.hawk_wallet.ui.screens.auth.register.RegisterViewModel
import com.example.hawk_wallet.ui.screens.home.HomeActions
import com.example.hawk_wallet.ui.screens.home.HomeScreen
import com.example.hawk_wallet.ui.screens.home.HomeViewModel
import com.example.hawk_wallet.ui.screens.orders.ChoosePayoutMethodScreen
import com.example.hawk_wallet.ui.screens.orders.ClaimViewModel
import com.example.hawk_wallet.ui.screens.orders.OfferDetailsScreen
import com.example.hawk_wallet.ui.screens.orders.OrderStatusScreen
import com.example.hawk_wallet.ui.screens.orders.OrderStatusViewModel
import com.example.hawk_wallet.ui.screens.orders.OrdersScreen
import com.example.hawk_wallet.ui.screens.orders.OrdersViewModel
import com.example.hawk_wallet.ui.screens.orders.PayInScreen
import com.example.hawk_wallet.ui.screens.orders.PayInViewModel
import com.example.hawk_wallet.ui.screens.paymethods.AddBuyMethodScreen
import com.example.hawk_wallet.ui.screens.paymethods.AddPaymentMethodScreen
import com.example.hawk_wallet.ui.screens.paymethods.PAYMENT_METHODS_TAB_BUY
import com.example.hawk_wallet.ui.screens.paymethods.PAYMENT_METHODS_TAB_SELL
import com.example.hawk_wallet.ui.screens.paymethods.BuyMethodsViewModel
import com.example.hawk_wallet.ui.screens.paymethods.PaymentMethodDetailsScreen
import com.example.hawk_wallet.ui.screens.paymethods.PaymentMethodsScreen
import com.example.hawk_wallet.ui.screens.paymethods.PaymentMethodsViewModel
import com.example.hawk_wallet.ui.screens.profile.ChangePasswordScreen
import com.example.hawk_wallet.ui.screens.profile.ChangePasswordViewModel
import com.example.hawk_wallet.ui.screens.profile.PersonalInfoScreen
import com.example.hawk_wallet.ui.screens.profile.PersonalInfoViewModel
import com.example.hawk_wallet.ui.screens.profile.ProfileActions
import com.example.hawk_wallet.ui.screens.profile.ProfileScreen
import com.example.hawk_wallet.ui.screens.profile.ProfileViewModel
import com.example.hawk_wallet.ui.screens.support.AdminSupportChatScreen
import com.example.hawk_wallet.ui.screens.support.AdminSupportChatSource
import com.example.hawk_wallet.ui.screens.support.AdminSupportInboxScreen
import com.example.hawk_wallet.ui.screens.support.AdminSupportInboxViewModel
import com.example.hawk_wallet.ui.screens.support.MemberSupportChatSource
import com.example.hawk_wallet.ui.screens.support.SupportChatScreen
import com.example.hawk_wallet.ui.screens.support.SupportChatViewModel
import com.example.hawk_wallet.ui.screens.support.SupportUnreadViewModel
import com.example.hawk_wallet.ui.screens.team.CommissionHistoryScreen
import com.example.hawk_wallet.ui.screens.team.CommissionHistoryViewModel
import com.example.hawk_wallet.ui.screens.team.TeamMembersScreen
import com.example.hawk_wallet.ui.screens.team.TeamMembersViewModel
import com.example.hawk_wallet.ui.screens.team.TeamScreen
import com.example.hawk_wallet.ui.screens.team.TeamViewModel

@Composable
fun HawkNavHost(
    navController: NavHostController,
    authRepository: AuthRepository,
    homeRepository: HomeRepository,
    payoutMethodRepository: PayoutMethodRepository,
    buyMethodRepository: BuyMethodRepository,
    orderRepository: OrderRepository,
    adminOrderRepository: AdminOrderRepository,
    adminDashboardRepository: AdminDashboardRepository,
    adminUserRepository: AdminUserRepository,
    adminManagementRepository: AdminManagementRepository,
    teamRepository: TeamRepository,
    profileRepository: ProfileRepository,
    supportChatRepository: SupportChatRepository,
    sessionManager: SessionManager,
    startDestination: String
) {
    // Shared by the Wallet screens (activity scope). It loads nothing until
    // the Payment Methods screen opens, so it never calls the API before login.
    val paymentMethodsViewModel: PaymentMethodsViewModel = viewModel(
        factory = viewModelFactory { initializer { PaymentMethodsViewModel(payoutMethodRepository) } }
    )
    // Shared by the BUY accounts screens (activity scope); loads nothing until they open.
    val buyMethodsViewModel: BuyMethodsViewModel = viewModel(
        factory = viewModelFactory { initializer { BuyMethodsViewModel(buyMethodRepository) } }
    )
    // Shared by Offer Details and Choose Payout Method (one claim at a time).
    val claimViewModel: ClaimViewModel = viewModel(
        factory = viewModelFactory { initializer { ClaimViewModel(orderRepository, payoutMethodRepository) } }
    )

    // The member's unread support count for the Home bell (activity scope). It
    // polls only while the app is in the foreground and a member is logged in;
    // the count is the backend's unread_count, never counted on the device.
    val supportUnreadViewModel: SupportUnreadViewModel = viewModel(
        factory = viewModelFactory { initializer { SupportUnreadViewModel(supportChatRepository) } }
    )
    val isLoggedIn by sessionManager.isLoggedInFlow.collectAsState(initial = false)
    val sessionRole by sessionManager.roleFlow.collectAsState(initial = null)
    val isMemberSession = isLoggedIn && sessionRole == "MEMBER"
    val appLifecycleOwner = LocalLifecycleOwner.current

    // Global session expiry. However the session ended without a logout
    // (refresh refused, idle timeout), and whichever screen is open, the user
    // lands on Login with nothing left on the back stack.
    LaunchedEffect(navController) {
        sessionManager.sessionExpired.collect {
            navController.navigate(HawkRoutes.LOGIN) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
            navController.currentBackStackEntry?.savedStateHandle
                ?.set(HawkRoutes.LOGIN_NOTICE_KEY, SESSION_EXPIRED_NOTICE)
        }
    }
    // Local mirror of the backend's 12-hour idle timeout, checked each time
    // the app comes back to the foreground.
    LaunchedEffect(appLifecycleOwner) {
        appLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (sessionManager.hasSession() && sessionManager.isIdleExpired()) {
                sessionManager.expireSession()
            }
        }
    }
    LaunchedEffect(appLifecycleOwner, isMemberSession) {
        if (isMemberSession) {
            appLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                supportUnreadViewModel.pollWhileForeground()
            }
        } else {
            supportUnreadViewModel.clear()
        }
    }
    val openSupport = { navController.navigate(HawkRoutes.SUPPORT) { launchSingleTop = true } }

    // Admin tabs are pushed on top of the Dashboard, replacing whichever tab
    // was open, so back from any tab returns to the Dashboard.
    val openAdminTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(HawkRoutes.ADMIN_DASHBOARD)
            launchSingleTop = true
        }
    }
    val onAdminBottomNavSelect: (AdminNavDestination) -> Unit = { destination ->
        when (destination) {
            AdminNavDestination.DASHBOARD -> navController.popBackStack(HawkRoutes.ADMIN_DASHBOARD, inclusive = false)
            AdminNavDestination.ORDERS -> openAdminTab(HawkRoutes.adminOrders())
            AdminNavDestination.SUPPORT -> openAdminTab(HawkRoutes.ADMIN_SUPPORT_INBOX)
            AdminNavDestination.USERS -> openAdminTab(HawkRoutes.ADMIN_USERS)
            AdminNavDestination.MORE -> openAdminTab(HawkRoutes.ADMIN_MORE)
        }
    }
    val openAdminProfile = { navController.navigate(HawkRoutes.ADMIN_PROFILE) { launchSingleTop = true } }
    // Admin detail screens cross-link (pay-in -> order / user, user -> order ...); each is a plain push.
    val openAdminOrder: (Long) -> Unit = { id -> navController.navigate(HawkRoutes.adminOrderDetails(id)) }
    val openAdminUser: (Long) -> Unit = { id -> navController.navigate(HawkRoutes.adminUserDetails(id)) }

    // Team is pushed on top of Home, replacing whichever tab (e.g. Orders) was open.
    val openTeam = {
        navController.navigate(HawkRoutes.TEAM) {
            popUpTo(HawkRoutes.HOME)
            launchSingleTop = true
        }
    }

    // Profile, like Team, is pushed on top of Home, replacing whichever tab was open.
    val openProfile = {
        navController.navigate(HawkRoutes.PROFILE) {
            popUpTo(HawkRoutes.HOME)
            launchSingleTop = true
        }
    }

    // Orders is pushed on top of Home; its tabs lead back there or on to Wallet / Team / Profile.
    val onOrdersBottomNavSelect: (HawkNavDestination) -> Unit = { destination ->
        when (destination) {
            HawkNavDestination.HOME -> navController.popBackStack(HawkRoutes.HOME, inclusive = false)
            HawkNavDestination.WALLET -> navController.navigate(HawkRoutes.WALLET) { launchSingleTop = true }
            HawkNavDestination.TEAM -> openTeam()
            HawkNavDestination.PROFILE -> openProfile()
            else -> Unit
        }
    }

    // Opens the member's orders from Team or Profile, replacing that tab on top of Home.
    val openOrders = {
        navController.navigate(HawkRoutes.ORDERS) {
            popUpTo(HawkRoutes.HOME)
            launchSingleTop = true
        }
    }

    // Team's own tabs: back to Home, or across to Orders / Wallet / Profile.
    val onTeamBottomNavSelect: (HawkNavDestination) -> Unit = { destination ->
        when (destination) {
            HawkNavDestination.HOME -> navController.popBackStack(HawkRoutes.HOME, inclusive = false)
            HawkNavDestination.ORDERS -> openOrders()
            HawkNavDestination.WALLET -> navController.navigate(HawkRoutes.WALLET) { launchSingleTop = true }
            HawkNavDestination.PROFILE -> openProfile()
            else -> Unit
        }
    }

    // Profile's own tabs: back to Home, or across to Orders / Wallet / Team.
    val onProfileBottomNavSelect: (HawkNavDestination) -> Unit = { destination ->
        when (destination) {
            HawkNavDestination.HOME -> navController.popBackStack(HawkRoutes.HOME, inclusive = false)
            HawkNavDestination.ORDERS -> openOrders()
            HawkNavDestination.WALLET -> navController.navigate(HawkRoutes.WALLET) { launchSingleTop = true }
            HawkNavDestination.TEAM -> openTeam()
            else -> Unit
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(HawkRoutes.LOGIN) { backStackEntry ->
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory { initializer { LoginViewModel(authRepository, sessionManager) } }
            )
            val handle = backStackEntry.savedStateHandle
            val pendingNotice by handle.getStateFlow<String?>(HawkRoutes.LOGIN_NOTICE_KEY, null).collectAsState()
            LoginScreen(
                viewModel = viewModel,
                onNavigateToRegister = { navController.navigate(HawkRoutes.REGISTER) },
                onForgotPasswordClick = { navController.navigate(HawkRoutes.FORGOT_PASSWORD_GRAPH) },
                onLoggedIn = { role ->
                    navController.navigate(HawkRoutes.destinationForRole(role)) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                // Unverified account: finish registration with its OTP (never a login OTP).
                onVerificationRequired = { mobileNumber ->
                    navController.navigate(HawkRoutes.otp(mobileNumber))
                },
                pendingNotice = pendingNotice,
                onPendingNoticeShown = { handle[HawkRoutes.LOGIN_NOTICE_KEY] = null }
            )
        }

        navigation(
            startDestination = HawkRoutes.FORGOT_PASSWORD_MOBILE,
            route = HawkRoutes.FORGOT_PASSWORD_GRAPH
        ) {
            composable(HawkRoutes.FORGOT_PASSWORD_MOBILE) { entry ->
                ForgotPasswordMobileScreen(
                    viewModel = forgotPasswordViewModel(navController, entry, authRepository),
                    onBackClick = { navController.popBackStack() },
                    onOtpRequested = { navController.navigate(HawkRoutes.FORGOT_PASSWORD_OTP) }
                )
            }
            composable(HawkRoutes.FORGOT_PASSWORD_OTP) { entry ->
                ForgotPasswordOtpScreen(
                    viewModel = forgotPasswordViewModel(navController, entry, authRepository),
                    onVerified = { navController.navigate(HawkRoutes.RESET_PASSWORD) }
                )
            }
            composable(HawkRoutes.RESET_PASSWORD) { entry ->
                val backToMobile = {
                    navController.popBackStack(HawkRoutes.FORGOT_PASSWORD_MOBILE, inclusive = false)
                    Unit
                }
                ResetPasswordScreen(
                    viewModel = forgotPasswordViewModel(navController, entry, authRepository),
                    onAbandon = backToMobile,
                    onStartAgain = backToMobile,
                    onCompleted = { message ->
                        // Popping the graph clears its ViewModel (and any token).
                        navController.popBackStack(HawkRoutes.FORGOT_PASSWORD_GRAPH, inclusive = true)
                        navController.currentBackStackEntry?.savedStateHandle
                            ?.set(HawkRoutes.LOGIN_NOTICE_KEY, message)
                    }
                )
            }
        }

        composable(HawkRoutes.REGISTER) {
            val viewModel: RegisterViewModel = viewModel(
                factory = viewModelFactory { initializer { RegisterViewModel(authRepository) } }
            )
            RegisterScreen(
                viewModel = viewModel,
                onNavigateToLogin = { navController.popBackStack() },
                onRegistered = { mobileNumber ->
                    navController.navigate(HawkRoutes.otp(mobileNumber))
                }
            )
        }

        composable(
            route = HawkRoutes.OTP,
            arguments = listOf(navArgument("mobileNumber") { type = NavType.StringType })
        ) { backStackEntry ->
            val mobileNumber = backStackEntry.arguments?.getString("mobileNumber").orEmpty()
            val viewModel: OtpViewModel = viewModel(
                factory = viewModelFactory { initializer { OtpViewModel(authRepository, mobileNumber) } }
            )
            OtpVerificationScreen(
                viewModel = viewModel,
                onRegistrationVerified = {
                    navController.navigate(HawkRoutes.LOGIN) {
                        popUpTo(HawkRoutes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(HawkRoutes.HOME) {
            val viewModel: HomeViewModel = viewModel(
                factory = viewModelFactory { initializer { HomeViewModel(homeRepository) } }
            )
            val supportUnreadCount by supportUnreadViewModel.unreadCount.collectAsState()
            // Back on Home (e.g. from Support Chat, which marked messages read): refresh at once.
            LaunchedEffect(Unit) { supportUnreadViewModel.refresh() }
            HomeScreen(
                viewModel = viewModel,
                supportUnreadCount = supportUnreadCount,
                actions = HomeActions(
                    // USDT and Task have no screen yet, so they stay inert.
                    onQuickActionClick = { action ->
                        when (action) {
                            QuickAction.ORDER -> navController.navigate(HawkRoutes.ORDERS) { launchSingleTop = true }
                            QuickAction.TEAM -> openTeam()
                            else -> Unit
                        }
                    },
                    onInviteClick = openTeam,
                    // Home's existing Support button opens the member's support chat.
                    onSupportClick = openSupport,
                    // The bell's count is unread support messages, so it opens the same chat.
                    onNotificationClick = openSupport,
                    onBottomNavSelect = { destination ->
                        when (destination) {
                            HawkNavDestination.WALLET ->
                                navController.navigate(HawkRoutes.WALLET) { launchSingleTop = true }
                            HawkNavDestination.ORDERS ->
                                navController.navigate(HawkRoutes.ORDERS) { launchSingleTop = true }
                            HawkNavDestination.TEAM -> openTeam()
                            HawkNavDestination.PROFILE -> openProfile()
                            else -> Unit
                        }
                    }
                )
            )
        }

        composable(HawkRoutes.WALLET) { backStackEntry ->
            PaymentMethodsScreen(
                sellViewModel = paymentMethodsViewModel,
                buyViewModel = buyMethodsViewModel,
                onBackClick = { navController.popBackStack() },
                onAddSellClick = {
                    paymentMethodsViewModel.startNewMethod()
                    navController.navigate(HawkRoutes.ADD_PAYMENT_METHOD)
                },
                onAddBuyClick = {
                    buyMethodsViewModel.startNewMethod()
                    navController.navigate(HawkRoutes.ADD_BUY_METHOD)
                },
                // BUY by default; the claim flow opens it on SELL (see CHOOSE_PAYOUT_METHOD).
                initialTab = backStackEntry.savedStateHandle.get<Int>(HawkRoutes.WALLET_TAB_KEY)
                    ?: PAYMENT_METHODS_TAB_BUY,
                // Wallet's own tabs: back to Home, or across to Orders / Team / Profile.
                onBottomNavSelect = { destination ->
                    when (destination) {
                        HawkNavDestination.HOME -> navController.popBackStack(HawkRoutes.HOME, inclusive = false)
                        HawkNavDestination.ORDERS -> openOrders()
                        HawkNavDestination.TEAM -> openTeam()
                        HawkNavDestination.PROFILE -> openProfile()
                        else -> Unit
                    }
                }
            )
        }

        composable(HawkRoutes.ADD_BUY_METHOD) {
            AddBuyMethodScreen(
                viewModel = buyMethodsViewModel,
                onBackClick = { navController.popBackStack() },
                // Back to wherever the add started (Payment Methods BUY tab or Pay-In), which reloads its accounts.
                onSaved = { navController.popBackStack() }
            )
        }

        composable(HawkRoutes.ADD_PAYMENT_METHOD) {
            AddPaymentMethodScreen(
                viewModel = paymentMethodsViewModel,
                onBackClick = { navController.popBackStack() },
                onContinue = { navController.navigate(HawkRoutes.PAYMENT_METHOD_DETAILS) }
            )
        }

        composable(HawkRoutes.PAYMENT_METHOD_DETAILS) {
            PaymentMethodDetailsScreen(
                viewModel = paymentMethodsViewModel,
                onBackClick = { navController.popBackStack() },
                // Back to the list (skipping provider selection), which shows the success snackbar.
                onSaved = { navController.popBackStack(HawkRoutes.WALLET, inclusive = false) }
            )
        }

        composable(HawkRoutes.ORDERS) {
            val viewModel: OrdersViewModel = viewModel(
                factory = viewModelFactory { initializer { OrdersViewModel(orderRepository) } }
            )
            OrdersScreen(
                viewModel = viewModel,
                onBackClick = { navController.popBackStack() },
                onOfferClick = { offer ->
                    claimViewModel.start(offer)
                    navController.navigate(HawkRoutes.OFFER_DETAILS)
                },
                onOrderClick = { orderId -> navController.navigate(HawkRoutes.orderStatus(orderId)) },
                onBottomNavSelect = onOrdersBottomNavSelect
            )
        }

        composable(HawkRoutes.TEAM) {
            val viewModel: TeamViewModel = viewModel(
                factory = viewModelFactory { initializer { TeamViewModel(teamRepository) } }
            )
            TeamScreen(
                viewModel = viewModel,
                onBackClick = { navController.popBackStack() },
                onSeeAllMembers = { level -> navController.navigate(HawkRoutes.teamMembers(level)) },
                onSeeAllCommissions = { navController.navigate(HawkRoutes.COMMISSION_HISTORY) },
                onBottomNavSelect = onTeamBottomNavSelect
            )
        }

        composable(
            route = HawkRoutes.TEAM_MEMBERS,
            // Optional: absent = every level. Levels beyond 21 are valid.
            arguments = listOf(navArgument(HawkRoutes.TEAM_LEVEL_ARG) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            val level = backStackEntry.arguments?.getString(HawkRoutes.TEAM_LEVEL_ARG)?.toIntOrNull()
            val viewModel: TeamMembersViewModel = viewModel(
                factory = viewModelFactory { initializer { TeamMembersViewModel(teamRepository, level) } }
            )
            TeamMembersScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
        }

        composable(HawkRoutes.COMMISSION_HISTORY) {
            val viewModel: CommissionHistoryViewModel = viewModel(
                factory = viewModelFactory { initializer { CommissionHistoryViewModel(teamRepository) } }
            )
            CommissionHistoryScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
        }

        composable(HawkRoutes.PROFILE) { backStackEntry ->
            val viewModel: ProfileViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ProfileViewModel(profileRepository, authRepository, sessionManager) }
                }
            )
            val handle = backStackEntry.savedStateHandle
            val pendingMessage by handle.getStateFlow<String?>(HawkRoutes.PROFILE_MESSAGE_KEY, null).collectAsState()
            ProfileScreen(
                viewModel = viewModel,
                actions = ProfileActions(
                    onBackClick = { navController.popBackStack() },
                    onPersonalInfoClick = { navController.navigate(HawkRoutes.PROFILE_PERSONAL_INFO) },
                    // The existing Payment Methods screen (BUY / SELL), on its default BUY tab.
                    onPaymentMethodsClick = {
                        navController.navigate(HawkRoutes.WALLET) { launchSingleTop = true }
                    },
                    onOrdersClick = openOrders,
                    onTeamClick = openTeam,
                    onChangePasswordClick = { navController.navigate(HawkRoutes.PROFILE_CHANGE_PASSWORD) },
                    // The session is already cleared: start over at Login with nothing behind it.
                    onLoggedOut = {
                        navController.navigate(HawkRoutes.LOGIN) {
                            popUpTo(0)
                        }
                    },
                    onBottomNavSelect = onProfileBottomNavSelect
                ),
                pendingMessage = pendingMessage,
                onPendingMessageShown = { handle.set<String?>(HawkRoutes.PROFILE_MESSAGE_KEY, null) }
            )
        }

        composable(HawkRoutes.PROFILE_PERSONAL_INFO) {
            val viewModel: PersonalInfoViewModel = viewModel(
                factory = viewModelFactory { initializer { PersonalInfoViewModel(profileRepository, sessionManager) } }
            )
            PersonalInfoScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
        }

        composable(HawkRoutes.PROFILE_CHANGE_PASSWORD) {
            val viewModel: ChangePasswordViewModel = viewModel(
                factory = viewModelFactory { initializer { ChangePasswordViewModel(profileRepository, sessionManager) } }
            )
            ChangePasswordScreen(
                viewModel = viewModel,
                onBackClick = { navController.popBackStack() },
                onPasswordChanged = { message ->
                    // Back to Profile, which shows the backend's success message.
                    navController.previousBackStackEntry?.savedStateHandle
                        ?.set(HawkRoutes.PROFILE_MESSAGE_KEY, message)
                    navController.popBackStack()
                }
            )
        }

        composable(HawkRoutes.OFFER_DETAILS) {
            OfferDetailsScreen(
                viewModel = claimViewModel,
                onBackClick = { navController.popBackStack() },
                onClaimClick = { navController.navigate(HawkRoutes.CHOOSE_PAYOUT_METHOD) }
            )
        }

        composable(HawkRoutes.CHOOSE_PAYOUT_METHOD) {
            ChoosePayoutMethodScreen(
                viewModel = claimViewModel,
                onBackClick = { navController.popBackStack() },
                onAddPaymentMethod = {
                    // Claiming needs a SELL account, so open Payment Methods on the SELL tab.
                    navController.navigate(HawkRoutes.WALLET)
                    navController.currentBackStackEntry?.savedStateHandle
                        ?.set(HawkRoutes.WALLET_TAB_KEY, PAYMENT_METHODS_TAB_SELL)
                },
                onBackToOrders = { navController.popBackStack(HawkRoutes.ORDERS, inclusive = false) },
                onClaimed = { orderId ->
                    // The claim screens are done: back from Pay-In returns to Orders.
                    navController.navigate(HawkRoutes.payIn(orderId)) {
                        popUpTo(HawkRoutes.ORDERS)
                    }
                }
            )
        }

        composable(
            route = HawkRoutes.PAY_IN,
            arguments = listOf(navArgument(HawkRoutes.ORDER_ID_ARG) { type = NavType.LongType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getLong(HawkRoutes.ORDER_ID_ARG) ?: 0L
            val viewModel: PayInViewModel = viewModel(
                factory = viewModelFactory { initializer { PayInViewModel(orderRepository, buyMethodRepository, orderId) } }
            )
            // Status replaces Pay-In, so back from it returns to Orders.
            val openStatus = {
                navController.navigate(HawkRoutes.orderStatus(orderId)) {
                    popUpTo(HawkRoutes.ORDERS)
                }
            }
            PayInScreen(
                viewModel = viewModel,
                onBackClick = { navController.popBackStack() },
                onViewStatus = openStatus,
                onSubmitted = openStatus,
                onAddBuyMethod = {
                    buyMethodsViewModel.startNewMethod()
                    navController.navigate(HawkRoutes.ADD_BUY_METHOD)
                }
            )
        }

        composable(
            route = HawkRoutes.ORDER_STATUS,
            arguments = listOf(navArgument(HawkRoutes.ORDER_ID_ARG) { type = NavType.LongType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getLong(HawkRoutes.ORDER_ID_ARG) ?: 0L
            val viewModel: OrderStatusViewModel = viewModel(
                factory = viewModelFactory { initializer { OrderStatusViewModel(orderRepository, orderId) } }
            )
            OrderStatusScreen(
                viewModel = viewModel,
                onBackClick = { navController.popBackStack() },
                onPayClick = { id -> navController.navigate(HawkRoutes.payIn(id)) }
            )
        }

        composable(HawkRoutes.SUPPORT) {
            val viewModel: SupportChatViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { SupportChatViewModel(MemberSupportChatSource(supportChatRepository), supportChatRepository) }
                }
            )
            SupportChatScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
        }

        // ADMIN and SUPER_ADMIN share this graph; destinationForRole() opens it on
        // the Dashboard. Role differences live inside More and Support, and the
        // backend authorizes every request whatever the UI shows.
        navigation(startDestination = HawkRoutes.ADMIN_DASHBOARD, route = HawkRoutes.ADMIN_GRAPH) {
            composable(HawkRoutes.ADMIN_DASHBOARD) {
                val userName by sessionManager.userNameFlow.collectAsState(initial = null)
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                val viewModel: AdminDashboardViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminDashboardViewModel(adminDashboardRepository) } }
                )
                AdminDashboardScreen(
                    viewModel = viewModel,
                    userName = userName.orEmpty(),
                    userRole = role.orEmpty(),
                    actions = AdminDashboardActions(
                        onProfileClick = openAdminProfile,
                        onPayInReviewsClick = {
                            navController.navigate(HawkRoutes.adminPayIns(AdminPayInFilter.UNDER_REVIEW.apiStatus))
                        },
                        onPayoutsPendingClick = {
                            navController.navigate(HawkRoutes.adminPayouts(AdminPayoutFilter.PENDING.apiStatus))
                        },
                        onOrdersAwaitingClick = {
                            openAdminTab(HawkRoutes.adminOrders(AdminOrderFilter.AWAITING_ACTION.name))
                        },
                        onSupportClick = { openAdminTab(HawkRoutes.ADMIN_SUPPORT_INBOX) },
                        onBottomNavSelect = onAdminBottomNavSelect
                    )
                )
            }

            composable(
                route = HawkRoutes.ADMIN_ORDERS,
                arguments = listOf(navArgument(HawkRoutes.ORDER_FILTER_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) { backStackEntry ->
                val initial = AdminOrderFilter.fromRoute(backStackEntry.arguments?.getString(HawkRoutes.ORDER_FILTER_ARG))
                val viewModel: AdminOrdersViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminOrdersViewModel(adminOrderRepository, initial) } }
                )
                AdminOrdersScreen(
                    viewModel = viewModel,
                    // A top-level tab: no back button; system back returns to the Dashboard.
                    onBackClick = null,
                    onOrderClick = { orderId -> navController.navigate(HawkRoutes.adminOrderDetails(orderId)) },
                    onCreateOfferClick = {
                        navController.navigate(HawkRoutes.ADMIN_CREATE_OFFER) { launchSingleTop = true }
                    },
                    bottomBar = {
                        AdminBottomNavigation(selected = AdminNavDestination.ORDERS, onSelect = onAdminBottomNavSelect)
                    }
                )
            }

            composable(HawkRoutes.ADMIN_SUPPORT_INBOX) {
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                val viewModel: AdminSupportInboxViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminSupportInboxViewModel(supportChatRepository) } }
                )
                AdminSupportInboxScreen(
                    viewModel = viewModel,
                    isSuperAdmin = role == "SUPER_ADMIN",
                    onBackClick = null,
                    onConversationClick = { id -> navController.navigate(HawkRoutes.adminSupportChat(id)) },
                    bottomBar = {
                        AdminBottomNavigation(selected = AdminNavDestination.SUPPORT, onSelect = onAdminBottomNavSelect)
                    }
                )
            }

            composable(HawkRoutes.ADMIN_MORE) {
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                AdminMoreScreen(
                    role = role,
                    onItemClick = { item ->
                        when (item) {
                            AdminMoreItem.PROFILE -> openAdminProfile()
                            AdminMoreItem.PAYMENTS -> navController.navigate(HawkRoutes.adminPayIns()) { launchSingleTop = true }
                            AdminMoreItem.PAYOUTS -> navController.navigate(HawkRoutes.adminPayouts()) { launchSingleTop = true }
                            AdminMoreItem.REPORTS -> navController.navigate(HawkRoutes.ADMIN_REPORTS) { launchSingleTop = true }
                            AdminMoreItem.ADMINS -> navController.navigate(HawkRoutes.ADMIN_ACCOUNTS) { launchSingleTop = true }
                            AdminMoreItem.AUDIT_LOG -> navController.navigate(HawkRoutes.ADMIN_AUDIT_LOG) { launchSingleTop = true }
                            AdminMoreItem.SYSTEM_REPORTS -> navController.navigate(HawkRoutes.ADMIN_SYSTEM_REPORT) { launchSingleTop = true }
                            // Not available until their implementation phase: no route yet.
                            else -> Unit
                        }
                    },
                    onBottomNavSelect = onAdminBottomNavSelect
                )
            }

            composable(HawkRoutes.ADMIN_PROFILE) { backStackEntry ->
                val viewModel: ProfileViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { ProfileViewModel(profileRepository, authRepository, sessionManager) }
                    }
                )
                val handle = backStackEntry.savedStateHandle
                val pendingMessage by handle.getStateFlow<String?>(HawkRoutes.PROFILE_MESSAGE_KEY, null).collectAsState()
                AdminProfileScreen(
                    viewModel = viewModel,
                    actions = AdminProfileActions(
                        onBackClick = { navController.popBackStack() },
                        onPersonalInfoClick = { navController.navigate(HawkRoutes.ADMIN_PERSONAL_INFO) },
                        // The shared Change Password screen hands its message back via PROFILE_MESSAGE_KEY.
                        onChangePasswordClick = { navController.navigate(HawkRoutes.PROFILE_CHANGE_PASSWORD) },
                        // The session is already cleared: start over at Login with nothing behind it.
                        onLoggedOut = {
                            navController.navigate(HawkRoutes.LOGIN) {
                                popUpTo(0)
                            }
                        }
                    ),
                    pendingMessage = pendingMessage,
                    onPendingMessageShown = { handle.set<String?>(HawkRoutes.PROFILE_MESSAGE_KEY, null) }
                )
            }

            composable(HawkRoutes.ADMIN_PERSONAL_INFO) {
                val viewModel: PersonalInfoViewModel = viewModel(
                    factory = viewModelFactory { initializer { PersonalInfoViewModel(profileRepository, sessionManager) } }
                )
                PersonalInfoScreen(
                    viewModel = viewModel,
                    onBackClick = { navController.popBackStack() },
                    showReferralCode = false
                )
            }

            composable(
                route = HawkRoutes.ADMIN_SUPPORT_CHAT,
                arguments = listOf(navArgument(HawkRoutes.CONVERSATION_ID_ARG) { type = NavType.StringType })
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getString(HawkRoutes.CONVERSATION_ID_ARG).orEmpty()
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                val viewModel: SupportChatViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            SupportChatViewModel(AdminSupportChatSource(supportChatRepository, conversationId), supportChatRepository)
                        }
                    }
                )
                AdminSupportChatScreen(
                    viewModel = viewModel,
                    isSuperAdmin = role == "SUPER_ADMIN",
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(HawkRoutes.ADMIN_CREATE_OFFER) {
                val viewModel: CreateOfferViewModel = viewModel(
                    factory = viewModelFactory { initializer { CreateOfferViewModel(adminOrderRepository) } }
                )
                CreateOfferScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
            }

            composable(
                route = HawkRoutes.ADMIN_ORDER_DETAILS,
                arguments = listOf(navArgument(HawkRoutes.ORDER_ID_ARG) { type = NavType.LongType })
            ) { backStackEntry ->
                val orderId = backStackEntry.arguments?.getLong(HawkRoutes.ORDER_ID_ARG) ?: 0L
                val viewModel: AdminOrderDetailsViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminOrderDetailsViewModel(adminOrderRepository, orderId) } }
                )
                AdminOrderDetailsScreen(
                    viewModel = viewModel,
                    onBackClick = { navController.popBackStack() },
                    onOpenUser = openAdminUser
                )
            }

            composable(HawkRoutes.ADMIN_REPORTS) {
                val viewModel: AdminReportsViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminReportsViewModel(adminManagementRepository) } }
                )
                AdminReportsScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
            }

            // SUPER_ADMIN only: each entry is role-gated here, and the backend answers 403 to anyone else.
            composable(HawkRoutes.ADMIN_ACCOUNTS) {
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                SuperAdminOnly(role = role, onBackClick = { navController.popBackStack() }) {
                    val viewModel: AdminAccountsViewModel = viewModel(
                        factory = viewModelFactory { initializer { AdminAccountsViewModel(adminManagementRepository) } }
                    )
                    AdminAccountsScreen(
                        viewModel = viewModel,
                        onBackClick = { navController.popBackStack() },
                        onAdminClick = { id -> navController.navigate(HawkRoutes.adminAccountDetails(id)) },
                        onAddAdminClick = { navController.navigate(HawkRoutes.ADMIN_ADD_ACCOUNT) { launchSingleTop = true } }
                    )
                }
            }

            composable(HawkRoutes.ADMIN_ADD_ACCOUNT) {
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                SuperAdminOnly(role = role, onBackClick = { navController.popBackStack() }) {
                    val viewModel: AddAdminViewModel = viewModel(
                        factory = viewModelFactory { initializer { AddAdminViewModel(adminManagementRepository) } }
                    )
                    AddAdminScreen(
                        viewModel = viewModel,
                        onBackClick = { navController.popBackStack() },
                        // The new admin's detail replaces the form, so back returns to the list.
                        onCreated = { id ->
                            navController.navigate(HawkRoutes.adminAccountDetails(id)) {
                                popUpTo(HawkRoutes.ADMIN_ADD_ACCOUNT) { inclusive = true }
                            }
                        }
                    )
                }
            }

            composable(
                route = HawkRoutes.ADMIN_ACCOUNT_DETAILS,
                arguments = listOf(navArgument(HawkRoutes.ADMIN_ACCOUNT_ID_ARG) { type = NavType.LongType })
            ) { backStackEntry ->
                val adminId = backStackEntry.arguments?.getLong(HawkRoutes.ADMIN_ACCOUNT_ID_ARG) ?: 0L
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                SuperAdminOnly(role = role, onBackClick = { navController.popBackStack() }) {
                    val viewModel: AdminAccountDetailViewModel = viewModel(
                        factory = viewModelFactory { initializer { AdminAccountDetailViewModel(adminManagementRepository, adminId) } }
                    )
                    AdminAccountDetailScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
                }
            }

            composable(HawkRoutes.ADMIN_AUDIT_LOG) {
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                SuperAdminOnly(role = role, onBackClick = { navController.popBackStack() }) {
                    val viewModel: AuditLogViewModel = viewModel(
                        factory = viewModelFactory { initializer { AuditLogViewModel(adminManagementRepository) } }
                    )
                    AuditLogScreen(
                        viewModel = viewModel,
                        onBackClick = { navController.popBackStack() },
                        onEventClick = { id -> navController.navigate(HawkRoutes.adminAuditEventDetails(id)) }
                    )
                }
            }

            composable(
                route = HawkRoutes.ADMIN_AUDIT_EVENT_DETAILS,
                arguments = listOf(navArgument(HawkRoutes.AUDIT_EVENT_ID_ARG) { type = NavType.LongType })
            ) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getLong(HawkRoutes.AUDIT_EVENT_ID_ARG) ?: 0L
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                SuperAdminOnly(role = role, onBackClick = { navController.popBackStack() }) {
                    val viewModel: AuditEventDetailViewModel = viewModel(
                        factory = viewModelFactory { initializer { AuditEventDetailViewModel(adminManagementRepository, eventId) } }
                    )
                    AuditEventDetailScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
                }
            }

            composable(HawkRoutes.ADMIN_SYSTEM_REPORT) {
                val role by sessionManager.roleFlow.collectAsState(initial = null)
                SuperAdminOnly(role = role, onBackClick = { navController.popBackStack() }) {
                    val viewModel: SystemReportViewModel = viewModel(
                        factory = viewModelFactory { initializer { SystemReportViewModel(adminManagementRepository) } }
                    )
                    SystemReportScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
                }
            }

            composable(HawkRoutes.ADMIN_USERS) {
                val viewModel: AdminUsersViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminUsersViewModel(adminUserRepository) } }
                )
                AdminUsersScreen(
                    viewModel = viewModel,
                    onUserClick = openAdminUser,
                    bottomBar = {
                        AdminBottomNavigation(selected = AdminNavDestination.USERS, onSelect = onAdminBottomNavSelect)
                    }
                )
            }

            composable(
                route = HawkRoutes.ADMIN_USER_DETAILS,
                arguments = listOf(navArgument(HawkRoutes.USER_ID_ARG) { type = NavType.LongType })
            ) { backStackEntry ->
                val userId = backStackEntry.arguments?.getLong(HawkRoutes.USER_ID_ARG) ?: 0L
                val viewModel: AdminUserDetailViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminUserDetailViewModel(adminUserRepository, userId) } }
                )
                AdminUserDetailScreen(
                    viewModel = viewModel,
                    onBackClick = { navController.popBackStack() },
                    onOpenOrder = openAdminOrder
                )
            }

            composable(
                route = HawkRoutes.ADMIN_PAY_INS,
                arguments = listOf(navArgument(HawkRoutes.STATUS_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) { backStackEntry ->
                val initial = AdminPayInFilter.fromApi(backStackEntry.arguments?.getString(HawkRoutes.STATUS_ARG))
                val viewModel: AdminPayInsViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminPayInsViewModel(adminOrderRepository, initial) } }
                )
                AdminPayInsScreen(
                    viewModel = viewModel,
                    onBackClick = { navController.popBackStack() },
                    onPayInClick = { id -> navController.navigate(HawkRoutes.adminPayInDetails(id)) }
                )
            }

            composable(
                route = HawkRoutes.ADMIN_PAY_IN_DETAILS,
                arguments = listOf(navArgument(HawkRoutes.PAY_IN_ID_ARG) { type = NavType.LongType })
            ) { backStackEntry ->
                val payInId = backStackEntry.arguments?.getLong(HawkRoutes.PAY_IN_ID_ARG) ?: 0L
                val viewModel: AdminPayInDetailViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminPayInDetailViewModel(adminOrderRepository, payInId) } }
                )
                AdminPayInDetailScreen(
                    viewModel = viewModel,
                    onBackClick = { navController.popBackStack() },
                    onOpenOrder = openAdminOrder,
                    onOpenUser = openAdminUser
                )
            }

            composable(
                route = HawkRoutes.ADMIN_PAYOUTS,
                arguments = listOf(navArgument(HawkRoutes.STATUS_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) { backStackEntry ->
                val initial = AdminPayoutFilter.fromApi(backStackEntry.arguments?.getString(HawkRoutes.STATUS_ARG))
                val viewModel: AdminPayoutsViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminPayoutsViewModel(adminOrderRepository, initial) } }
                )
                AdminPayoutsScreen(
                    viewModel = viewModel,
                    onBackClick = { navController.popBackStack() },
                    onPayoutClick = { id -> navController.navigate(HawkRoutes.adminPayoutDetails(id)) }
                )
            }

            composable(
                route = HawkRoutes.ADMIN_PAYOUT_DETAILS,
                arguments = listOf(navArgument(HawkRoutes.PAYOUT_ID_ARG) { type = NavType.LongType })
            ) { backStackEntry ->
                val payoutId = backStackEntry.arguments?.getLong(HawkRoutes.PAYOUT_ID_ARG) ?: 0L
                val viewModel: AdminPayoutDetailViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminPayoutDetailViewModel(adminOrderRepository, payoutId) } }
                )
                AdminPayoutDetailScreen(
                    viewModel = viewModel,
                    onBackClick = { navController.popBackStack() },
                    onOpenOrder = openAdminOrder,
                    onOpenUser = openAdminUser
                )
            }
        }
    }
}

private const val SESSION_EXPIRED_NOTICE = "Your session has expired. Please log in again."

/** The forgot-password flow's shared ViewModel, scoped to its nested graph. */
@Composable
private fun forgotPasswordViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
    authRepository: AuthRepository
): ForgotPasswordViewModel {
    val graphEntry = remember(entry) { navController.getBackStackEntry(HawkRoutes.FORGOT_PASSWORD_GRAPH) }
    return viewModel(
        viewModelStoreOwner = graphEntry,
        factory = viewModelFactory { initializer { ForgotPasswordViewModel(authRepository) } }
    )
}
