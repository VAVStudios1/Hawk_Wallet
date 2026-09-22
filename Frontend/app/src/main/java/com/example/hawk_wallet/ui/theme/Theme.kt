package com.example.hawk_wallet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = HawkVioletBlue,
    onPrimary = Color.White,
    secondary = HawkAmber,
    onSecondary = HawkIndigoDark,
    tertiary = HawkAmber,
    onTertiary = HawkIndigoDark,
    background = Color(0xFF10132A),
    onBackground = Color(0xFFE7E9F5),
    surface = Color(0xFF181B36),
    onSurface = Color(0xFFE7E9F5),
    surfaceVariant = Color(0xFF232748),
    onSurfaceVariant = Color(0xFFB7BBDA),
    error = HawkCoral,
    onError = Color.White,
    outline = Color(0xFF3A3F66)
)

private val LightColorScheme = lightColorScheme(
    primary = HawkIndigo,
    onPrimary = Color.White,
    secondary = HawkVioletBlue,
    onSecondary = Color.White,
    tertiary = HawkAmber,
    onTertiary = HawkIndigoDark,
    background = HawkBackground,
    onBackground = HawkNavy,
    surface = HawkSurface,
    onSurface = HawkNavy,
    surfaceVariant = HawkOutline,
    onSurfaceVariant = HawkNavyMuted,
    error = HawkCoral,
    onError = Color.White,
    outline = HawkOutline
)

/**
 * Hawk Wallet does not use Material You dynamic color: brand identity
 * (indigo/violet + amber) must stay consistent across every device.
 */
@Composable
fun Hawk_WalletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
