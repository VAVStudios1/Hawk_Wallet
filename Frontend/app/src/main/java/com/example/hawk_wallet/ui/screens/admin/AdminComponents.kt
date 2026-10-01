package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.AmountRow
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage

/** label over ₹amount, for compact rows of backend amounts (formatted for display only). */
@Composable
internal fun CompactAmount(label: String, amount: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        // A financial value is never cut off: no line limit, no ellipsis.
        Text(
            text = DisplayFormat.rupees(amount),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private val CompactAmountSpacing = 12.dp

/**
 * Up to three [CompactAmount]s side by side when every formatted amount fits
 * its column on one line (measured at the current width and font scale);
 * otherwise the same amounts stacked as label ........ ₹amount rows, so the
 * full value always shows.
 */
@Composable
internal fun CompactAmountRow(vararg amounts: Triple<String, String, Boolean>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    // Measured bold (the widest weight used), so a fit is never optimistic.
    val valueStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        val columnWidthPx = with(LocalDensity.current) {
            ((maxWidth - CompactAmountSpacing * (amounts.size - 1)) / amounts.size).toPx()
        }
        val fitsInColumns = amounts.all { (_, amount, _) ->
            measurer.measure(DisplayFormat.rupees(amount), valueStyle, maxLines = 1).size.width <= columnWidthPx
        }
        if (fitsInColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(CompactAmountSpacing)) {
                amounts.forEach { (label, amount, emphasized) ->
                    CompactAmount(label = label, amount = amount, emphasized = emphasized, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Column {
                amounts.forEach { (label, amount, emphasized) ->
                    AmountRow(label = label, amount = amount, emphasized = emphasized)
                }
            }
        }
    }
}

/** Tags (role / status / verification) that wrap onto another line instead of overflowing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagFlow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/** "Member name · ******3210" secondary line. */
@Composable
internal fun MemberLine(name: String, maskedMobile: String?, modifier: Modifier = Modifier) {
    Text(
        text = if (maskedMobile.isNullOrBlank()) name else "$name · $maskedMobile",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Small trailing text action inside a card header, e.g. "View user". */
@Composable
internal fun CardLinkRow(text: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClick) { Text(text, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * Route guard for SUPER_ADMIN-only screens. [role] is the session's role
 * (null while it loads). Anyone else sees a plain notice instead of the
 * screen - and the backend refuses their requests regardless (403).
 */
@Composable
internal fun SuperAdminOnly(role: String?, onBackClick: () -> Unit, content: @Composable () -> Unit) {
    when {
        role == null -> CenteredLoading()
        isSuperAdmin(role) -> content()
        else -> Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { HawkBackTopBar(title = "Not available", onBackClick = onBackClick) }
        ) { innerPadding ->
            CenteredMessage(
                title = "Super Admins only",
                message = "This section is available to Super Admin accounts only.",
                actionLabel = "Go back",
                onAction = onBackClick,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
