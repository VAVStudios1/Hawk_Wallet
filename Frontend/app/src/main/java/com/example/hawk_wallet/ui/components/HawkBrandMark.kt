package com.example.hawk_wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkIndigoDark
import com.example.hawk_wallet.ui.theme.HawkVioletBlue
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * Shared brand mark for auth/splash screens: a gradient badge + wordmark.
 * Placeholder for the real hawk/eagle artwork - swap the icon/asset here
 * once brand illustration is available; every screen that shows the mark
 * pulls from this single composable.
 */
@Composable
fun HawkBrandMark(
    modifier: Modifier = Modifier,
    badgeSize: Dp = 72.dp,
    showWordmark: Boolean = true,
    tagline: String? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(badgeSize)
                .clip(RoundedCornerShape(badgeSize * 0.28f))
                .background(Brush.linearGradient(listOf(HawkIndigoDark, HawkIndigo, HawkVioletBlue))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = "Hawk Wallet",
                tint = Color.White,
                modifier = Modifier.size(badgeSize * 0.5f)
            )
        }
        if (showWordmark) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Hawk Wallet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (tagline != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HawkBrandMarkPreview() {
    Hawk_WalletTheme {
        HawkBrandMark(tagline = "Secure Today, Smarter Tomorrow.")
    }
}
