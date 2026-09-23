package com.example.hawk_wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkCoralSoft

/**
 * Shared inline error banner for auth screens - same soft/solid coral
 * pairing as StatusChip's FAILED state. [title] is optional; when omitted
 * the banner renders exactly as it always has (single line, vertically
 * centered icon) so existing single-message call sites are unaffected.
 */
@Composable
fun AuthErrorBanner(message: String, modifier: Modifier = Modifier, title: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(HawkCoralSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = if (title != null) Alignment.Top else Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = HawkCoral,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (title != null) {
            Column {
                Text(
                    text = title,
                    color = HawkCoral,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    color = HawkCoral,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Text(
                text = message,
                color = HawkCoral,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
