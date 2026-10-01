package com.example.hawk_wallet.ui.screens.admin

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.PrimaryActionButton
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkCoral

// Confirm / reject pay-in and mark paid / cancel / QR payout dialogs, shared
// by Order Details, Pay-In Details and Payout Details. They only collect
// input and show the backend's answer; every rule is enforced server-side.

/** Bottom bar with a destructive (outlined coral) and a primary action. */
@Composable
internal fun AdminActionBar(
    secondaryLabel: String,
    onSecondary: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // Edge-to-edge: keep the buttons above the gesture / 3-button navigation bar.
            .navigationBarsPadding()
            .padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp)
    ) {
        OutlinedButton(
            onClick = onSecondary,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = HawkCoral),
            border = BorderStroke(1.dp, HawkCoral),
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
        ) {
            Text(secondaryLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        PrimaryActionButton(text = primaryLabel, onClick = onPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DialogError(error: String?) {
    error?.let {
        Text(text = it, color = HawkCoral, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SubmitButton(label: String, isSubmitting: Boolean, onClick: () -> Unit, color: Color? = null) {
    TextButton(
        onClick = onClick,
        enabled = !isSubmitting,
        colors = if (color != null) ButtonDefaults.textButtonColors(contentColor = color) else ButtonDefaults.textButtonColors()
    ) {
        if (isSubmitting) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ConfirmPayInDialog(
    amount: String,
    utr: String,
    isSubmitting: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm pay-in?") },
        text = {
            Column {
                Text(
                    text = "Only confirm after checking that ${DisplayFormat.rupees(amount)} with UTR " +
                        "$utr has reached the platform account. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                DialogError(error)
            }
        },
        confirmButton = { SubmitButton("Confirm", isSubmitting, onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") } }
    )
}

@Composable
internal fun RejectPayInDialog(
    reason: String,
    reasonError: String?,
    isSubmitting: Boolean,
    error: String?,
    onReasonChange: (String) -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reject pay-in?") },
        text = {
            Column {
                Text(
                    text = "The member will see this reason and can submit a new UTR.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Reason") },
                    isError = reasonError != null,
                    supportingText = { Text(reasonError ?: "${reason.length}/$REJECTION_REASON_MAX_LENGTH") },
                    enabled = !isSubmitting,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                DialogError(error)
            }
        },
        confirmButton = { SubmitButton("Reject", isSubmitting, onReject, color = HawkCoral) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") } }
    )
}

@Composable
internal fun CreatePayoutDialog(
    amount: String,
    amountError: String?,
    remainingToCommit: String?,
    isSubmitting: Boolean,
    error: String?,
    onAmountChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create payout") },
        text = {
            Column {
                Text(
                    text = "A pending payout to the member's SELL payout method. The server checks the amount " +
                        "against what is left to commit" +
                        (remainingToCommit?.let { " (${DisplayFormat.rupees(it)})" } ?: "") + ".",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = onAmountChange,
                    label = { Text("Amount (₹)") },
                    isError = amountError != null,
                    supportingText = { amountError?.let { Text(it) } },
                    enabled = !isSubmitting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                DialogError(error)
            }
        },
        confirmButton = { SubmitButton("Create", isSubmitting, onCreate) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Close") } }
    )
}

@Composable
internal fun MarkPayoutPaidDialog(
    amount: String,
    upiId: String,
    utr: String,
    utrError: String?,
    isSubmitting: Boolean,
    error: String?,
    onUtrChange: (String) -> Unit,
    onMarkPaid: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark payout paid?") },
        text = {
            Column {
                Text(
                    text = "Only mark paid after ${DisplayFormat.rupees(amount)} has been sent to " +
                        "$upiId. Enter the bank UTR of that transfer. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = utr,
                    onValueChange = onUtrChange,
                    label = { Text("UTR") },
                    isError = utrError != null,
                    supportingText = { Text(utrError ?: "${utr.length}/$UTR_LENGTH digits") },
                    enabled = !isSubmitting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                DialogError(error)
            }
        },
        confirmButton = { SubmitButton("Mark Paid", isSubmitting, onMarkPaid) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Close") } }
    )
}

@Composable
internal fun CancelPayoutDialog(
    amount: String,
    reason: String,
    reasonError: String?,
    isSubmitting: Boolean,
    error: String?,
    onReasonChange: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cancel payout?") },
        text = {
            Column {
                Text(
                    text = "Cancel the pending ${DisplayFormat.rupees(amount)} payout if it was created by " +
                        "mistake. Its amount becomes available to commit again. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Reason") },
                    isError = reasonError != null,
                    supportingText = { Text(reasonError ?: "${reason.length}/$CANCELLATION_REASON_MAX_LENGTH") },
                    enabled = !isSubmitting,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                DialogError(error)
            }
        },
        confirmButton = { SubmitButton("Cancel Payout", isSubmitting, onCancel, color = HawkCoral) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Keep") } }
    )
}

/** The SELL destination's QR, as bytes from the authorized endpoint - never a URL or storage path. */
@Composable
internal fun PayoutQrDialog(isLoading: Boolean, error: String?, image: ByteArray?, onDismiss: () -> Unit) {
    val bitmap = remember(image) { image?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payout QR") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> CircularProgressIndicator()
                    error != null -> Text(error, color = HawkCoral, style = MaterialTheme.typography.bodyMedium)
                    bitmap != null -> Image(
                        bitmap = bitmap,
                        contentDescription = "Member's payout QR code",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )
                    image != null -> Text(
                        "This QR image couldn't be displayed.",
                        color = HawkCoral,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
