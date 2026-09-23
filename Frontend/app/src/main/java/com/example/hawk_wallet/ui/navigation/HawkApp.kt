package com.example.hawk_wallet.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.repository.AuthRepository
import com.example.hawk_wallet.ui.screens.splash.SplashScreen
import kotlinx.coroutines.flow.first

@Composable
fun HawkApp(
    authRepository: AuthRepository,
    sessionManager: SessionManager
) {
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val loggedIn = sessionManager.isLoggedInFlow.first()
        startDestination = if (loggedIn) {
            HawkRoutes.destinationForRole(sessionManager.roleFlow.first())
        } else {
            HawkRoutes.LOGIN
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val destination = startDestination
        if (destination == null) {
            SplashScreen(modifier = Modifier.fillMaxSize())
        } else {
            val navController = rememberNavController()
            HawkNavHost(
                navController = navController,
                authRepository = authRepository,
                sessionManager = sessionManager,
                startDestination = destination
            )
        }
    }
}
