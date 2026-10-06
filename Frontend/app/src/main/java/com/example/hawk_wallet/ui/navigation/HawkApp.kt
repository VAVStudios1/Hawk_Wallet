package com.example.hawk_wallet.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.rememberNavController
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.remote.JwtExpiry
import com.example.hawk_wallet.data.remote.SessionRefresher
import com.example.hawk_wallet.data.repository.AdminDashboardRepository
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.AdminUserRepository
import com.example.hawk_wallet.data.repository.AppUpdateRepository
import com.example.hawk_wallet.data.repository.AuthRepository
import com.example.hawk_wallet.data.repository.HomeRepository
import com.example.hawk_wallet.data.repository.OrderRepository
import com.example.hawk_wallet.data.repository.BuyMethodRepository
import com.example.hawk_wallet.data.repository.PayoutMethodRepository
import com.example.hawk_wallet.data.repository.ProfileRepository
import com.example.hawk_wallet.data.repository.SupportChatRepository
import com.example.hawk_wallet.data.repository.TeamRepository
import com.example.hawk_wallet.ui.screens.splash.SplashScreen
import com.example.hawk_wallet.ui.screens.update.AppUpdateEffects
import com.example.hawk_wallet.ui.screens.update.AppUpdateViewModel
import com.example.hawk_wallet.ui.screens.update.ForceUpdateScreen
import com.example.hawk_wallet.ui.screens.update.OptionalUpdateDialog
import com.example.hawk_wallet.ui.screens.update.UpdateGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HawkApp(
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
    appUpdateRepository: AppUpdateRepository,
    sessionManager: SessionManager,
    sessionRefresher: SessionRefresher
) {
    var startDestination by remember { mutableStateOf<String?>(null) }

    // In-app updates (activity scope): checked at launch and each time the
    // app returns to the foreground. A required update replaces the whole
    // app below; an optional one is offered over it.
    val appUpdateViewModel: AppUpdateViewModel = viewModel(
        factory = viewModelFactory { initializer { AppUpdateViewModel(appUpdateRepository) } }
    )
    val updateState by appUpdateViewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            appUpdateViewModel.onForeground()
        }
    }
    AppUpdateEffects(appUpdateViewModel)

    LaunchedEffect(Unit) {
        startDestination = resolveStartDestination(sessionManager, sessionRefresher)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val destination = startDestination
        if (updateState.gate == UpdateGate.FORCED) {
            ForceUpdateScreen(
                state = updateState,
                onUpdate = appUpdateViewModel::startUpdate,
                onAllowInstalls = appUpdateViewModel::openInstallPermissionSettings,
                onRetryCheck = appUpdateViewModel::retryCheck,
                modifier = Modifier.fillMaxSize()
            )
        } else if (destination == null || updateState.gate == UpdateGate.CHECKING) {
            SplashScreen(modifier = Modifier.fillMaxSize())
        } else {
            val navController = rememberNavController()
            HawkNavHost(
                navController = navController,
                authRepository = authRepository,
                homeRepository = homeRepository,
                payoutMethodRepository = payoutMethodRepository,
                buyMethodRepository = buyMethodRepository,
                orderRepository = orderRepository,
                adminOrderRepository = adminOrderRepository,
                adminDashboardRepository = adminDashboardRepository,
                adminUserRepository = adminUserRepository,
                adminManagementRepository = adminManagementRepository,
                teamRepository = teamRepository,
                profileRepository = profileRepository,
                supportChatRepository = supportChatRepository,
                sessionManager = sessionManager,
                startDestination = destination
            )
            if (updateState.showOptionalPrompt) {
                OptionalUpdateDialog(
                    state = updateState,
                    onUpdate = appUpdateViewModel::startUpdate,
                    onAllowInstalls = appUpdateViewModel::openInstallPermissionSettings,
                    onLater = appUpdateViewModel::dismissOptional
                )
            }
        }
    }
}

/**
 * Decided behind the splash screen, so no authenticated screen is shown for
 * a session that is already over:
 * - no usable session (none, legacy plaintext, undecryptable, or idle for
 *   12 hours - restoreSession() clears it) -> Login;
 * - access token expired -> refresh first; a refused refresh -> Login;
 * - otherwise -> the role's home. If the server cannot be reached the
 *   session is kept; the first API call will refresh or end it.
 */
private suspend fun resolveStartDestination(
    sessionManager: SessionManager,
    sessionRefresher: SessionRefresher
): String {
    val session = sessionManager.restoreSession() ?: return HawkRoutes.LOGIN
    if (JwtExpiry.isExpiredOrUnreadable(session.accessToken)) {
        val outcome = withContext(Dispatchers.IO) { sessionRefresher.refresh(session.accessToken) }
        if (outcome is SessionRefresher.Outcome.SessionEnded) return HawkRoutes.LOGIN
    }
    return HawkRoutes.destinationForRole(session.role)
}
