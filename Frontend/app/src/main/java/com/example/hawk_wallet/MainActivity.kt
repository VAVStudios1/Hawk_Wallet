package com.example.hawk_wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.remote.NetworkModule
import com.example.hawk_wallet.data.repository.AuthRepository
import com.example.hawk_wallet.data.repository.HomeRepository
import com.example.hawk_wallet.ui.navigation.HawkApp
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessionManager = SessionManager(applicationContext)
        val authApi = NetworkModule.provideAuthApi(sessionManager)
        val authRepository = AuthRepository(authApi)
        val homeRepository = HomeRepository(NetworkModule.provideHomeApi(sessionManager))

        setContent {
            Hawk_WalletTheme {
                HawkApp(
                    authRepository = authRepository,
                    homeRepository = homeRepository,
                    sessionManager = sessionManager
                )
            }
        }
    }
}
