package com.example.hawk_wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.hawk_wallet.ui.screens.home.HomeScreen
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Hawk_WalletTheme {
                HomeScreen()
            }
        }
    }
}
