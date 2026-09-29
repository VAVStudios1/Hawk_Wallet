package com.example.hawk_wallet.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.screens.team.IconBadge
import com.example.hawk_wallet.ui.screens.team.SectionHeader
import com.example.hawk_wallet.ui.screens.team.SurfaceCard
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkVioletBlue

/**
 * Profile header: the Home top bar's gradient initial badge, the user's
 * name and mobile number, and role / verification tags - all from
 * GET /api/profile/.
 */
@Composable
internal fun ProfileHeaderCard(
    profile: ProfileUi?,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    SurfaceCard(modifier = modifier) {
        Column(modifier = Modifier.padding(20.dp)) {
            when {
                profile != null -> ProfileHeaderContent(profile)
                isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                else -> Column {
                    AuthErrorBanner(message = errorMessage ?: "Couldn't load your profile.")
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
            // Loaded before, but the latest refresh failed: keep the data and say so.
            if (profile != null && errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                AuthErrorBanner(message = errorMessage)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ProfileHeaderContent(profile: ProfileUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Brush.linearGradient(listOf(HawkIndigo, HawkVioletBlue)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = profile.name.trim().take(1).uppercase().ifEmpty { "•" },
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = profile.mobileNumber,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTag(text = profile.role, color = HawkIndigo)
                ProfileTag(
                    text = profile.status,
                    color = if (profile.isVerified) HawkEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Soft tinted label, as the Team level tag. */
@Composable
internal fun ProfileTag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** "Title" above a white card of rows separated by dividers. */
@Composable
internal fun ProfileSection(
    title: String,
    modifier: Modifier = Modifier,
    rows: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        SectionHeader(title = title, modifier = Modifier.padding(bottom = 8.dp))
        SurfaceCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = rows)
        }
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
}

/** [ icon ]  Title                    > */
@Composable
internal fun ProfileOptionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HawkIndigo,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon = icon, tint = tint, background = tint.copy(alpha = 0.1f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

/** Read-only "Label / value" line on Personal Information. */
@Composable
internal fun ProfileInfoRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon = icon, tint = HawkIndigo, background = HawkIndigo.copy(alpha = 0.1f))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            value()
        }
    }
}

@Composable
internal fun InfoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
