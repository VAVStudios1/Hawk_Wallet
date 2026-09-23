package com.example.hawk_wallet.ui.screens.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.HawkBrandMark
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * Shown while HawkApp resolves the stored session (see HawkApp.kt) to
 * decide the start destination. Purely presentational - the loading logic
 * and its timing are unchanged, this only replaces what was a bare spinner.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HawkBrandMark(
            badgeSize = 84.dp,
            tagline = "Secure Today, Smarter Tomorrow."
        )
        Spacer(modifier = Modifier.height(32.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SplashScreenPreview() {
    Hawk_WalletTheme {
        SplashScreen()
    }
}
