package com.example.hawk_wallet.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.repository.AuthRepository
import com.example.hawk_wallet.data.repository.HomeRepository
import com.example.hawk_wallet.ui.screens.admin.AdminDashboardScreen
import com.example.hawk_wallet.ui.screens.auth.login.LoginScreen
import com.example.hawk_wallet.ui.screens.auth.login.LoginViewModel
import com.example.hawk_wallet.ui.screens.auth.otp.OtpVerificationScreen
import com.example.hawk_wallet.ui.screens.auth.otp.OtpViewModel
import com.example.hawk_wallet.ui.screens.auth.register.RegisterScreen
import com.example.hawk_wallet.ui.screens.auth.register.RegisterViewModel
import com.example.hawk_wallet.ui.screens.home.HomeScreen
import com.example.hawk_wallet.ui.screens.home.HomeViewModel
import kotlinx.coroutines.launch

@Composable
fun HawkNavHost(
    navController: NavHostController,
    authRepository: AuthRepository,
    homeRepository: HomeRepository,
    sessionManager: SessionManager,
    startDestination: String
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(HawkRoutes.LOGIN) {
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory { initializer { LoginViewModel(authRepository) } }
            )
            LoginScreen(
                viewModel = viewModel,
                onNavigateToRegister = { navController.navigate(HawkRoutes.REGISTER) },
                onOtpRequested = { mobileNumber ->
                    navController.navigate(HawkRoutes.otp(mobileNumber, "login"))
                }
            )
        }

        composable(HawkRoutes.REGISTER) {
            val viewModel: RegisterViewModel = viewModel(
                factory = viewModelFactory { initializer { RegisterViewModel(authRepository) } }
            )
            RegisterScreen(
                viewModel = viewModel,
                onNavigateToLogin = { navController.popBackStack() },
                onRegistered = { mobileNumber ->
                    navController.navigate(HawkRoutes.otp(mobileNumber, "registration"))
                }
            )
        }

        composable(
            route = HawkRoutes.OTP,
            arguments = listOf(
                navArgument("mobileNumber") { type = NavType.StringType },
                navArgument("purpose") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val mobileNumber = backStackEntry.arguments?.getString("mobileNumber").orEmpty()
            val purpose = backStackEntry.arguments?.getString("purpose").orEmpty()
            val viewModel: OtpViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { OtpViewModel(authRepository, sessionManager, mobileNumber, purpose) }
                }
            )
            OtpVerificationScreen(
                viewModel = viewModel,
                onRegistrationVerified = {
                    navController.navigate(HawkRoutes.LOGIN) {
                        popUpTo(HawkRoutes.LOGIN) { inclusive = true }
                    }
                },
                onLoginVerified = { role ->
                    navController.navigate(HawkRoutes.destinationForRole(role)) {
                        popUpTo(0)
                    }
                }
            )
        }

        composable(HawkRoutes.HOME) {
            val viewModel: HomeViewModel = viewModel(
                factory = viewModelFactory { initializer { HomeViewModel(homeRepository) } }
            )
            HomeScreen(viewModel = viewModel)
        }

        composable(HawkRoutes.ADMIN_DASHBOARD) {
            val userName by sessionManager.userNameFlow.collectAsState(initial = null)
            val role by sessionManager.roleFlow.collectAsState(initial = null)
            val scope = rememberCoroutineScope()

            AdminDashboardScreen(
                userName = userName.orEmpty(),
                userRole = role.orEmpty(),
                onLogoutClick = {
                    scope.launch {
                        sessionManager.clearSession()
                        navController.navigate(HawkRoutes.LOGIN) {
                            popUpTo(0)
                        }
                    }
                }
            )
        }
    }
}
