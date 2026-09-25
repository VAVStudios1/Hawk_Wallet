package com.example.hawk_wallet.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import com.example.hawk_wallet.ui.theme.HawkIndigo

private val TileShape = RoundedCornerShape(14.dp)

/**
 * Provider brand mark on a white rounded tile. The logo is scaled to fit
 * (never stretched) so every brand sits at the same visual size. Unknown
 * providers (e.g. added later on the backend) fall back to a neutral icon.
 */
@Composable
fun ProviderLogo(
    provider: PaymentProviderOption?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.surface, TileShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, TileShape),
        contentAlignment = Alignment.Center
    ) {
        if (provider != null) {
            Image(
                painter = painterResource(provider.logoRes),
                contentDescription = "${provider.displayName} logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(30.dp)
            )
        } else {
            Icon(imageVector = Icons.Filled.Payments, contentDescription = null, tint = HawkIndigo)
        }
    }
}
