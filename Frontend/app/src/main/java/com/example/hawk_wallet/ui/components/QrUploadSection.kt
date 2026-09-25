package com.example.hawk_wallet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkIndigo

/** Result of decoding a picked QR image for preview. */
sealed interface QrImageState {
    data object Loading : QrImageState
    data class Loaded(val image: ImageBitmap) : QrImageState
    data object Failed : QrImageState
}

/**
 * Upload box before a QR is chosen; a rounded preview with Change/Remove
 * after. A file that cannot be decoded is reported through [onUnreadable].
 */
@Composable
fun QrUploadSection(
    qrImageUri: String?,
    onPickClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onUnreadable: () -> Unit,
    errorText: String?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (qrImageUri == null) {
            UploadPrompt(onClick = onPickClick, isError = errorText != null)
        } else {
            val state by rememberQrImage(qrImageUri)
            LaunchedEffect(state) {
                if (state is QrImageState.Failed) onUnreadable()
            }
            QrPreview(state = state, onChangeClick = onPickClick, onRemoveClick = onRemoveClick)
        }
        if (errorText != null) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp)
            )
        }
    }
}

@Composable
private fun UploadPrompt(onClick: () -> Unit, isError: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = HawkIndigo.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, if (isError) HawkCoral else HawkIndigo.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(HawkIndigo.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.QrCode2, contentDescription = null, tint = HawkIndigo)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Upload QR Code",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose the QR image from your payment app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun QrPreview(state: QrImageState, onChangeClick: () -> Unit, onRemoveClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    is QrImageState.Loaded -> Image(
                        bitmap = state.image,
                        contentDescription = "Selected QR code",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                    )
                    QrImageState.Loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    QrImageState.Failed -> Icon(Icons.Filled.QrCode2, contentDescription = null, tint = HawkCoral)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChangeClick, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Change")
                }
                TextButton(onClick = onRemoveClick) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = HawkCoral, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove", color = HawkCoral)
                }
            }
        }
    }
}
