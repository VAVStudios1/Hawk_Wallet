package com.example.hawk_wallet.ui.screens.auth.otp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBrandMark
import com.example.hawk_wallet.ui.components.HawkOtpInput
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

@Composable
fun OtpVerificationScreen(
    viewModel: OtpViewModel,
    onRegistrationVerified: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.registrationVerified) {
        if (uiState.registrationVerified) onRegistrationVerified()
    }

    OtpVerificationScreenContent(
        uiState = uiState,
        onOtpChange = viewModel::onOtpChange,
        onVerifyClick = viewModel::verify,
        onResendClick = viewModel::resend
    )
}

// Matches core/services.py's OTPInvalidError message exactly (backend not
// touched - this only decides which UI copy to show for that one case).
private const val BACKEND_INCORRECT_OTP_MESSAGE = "Incorrect OTP."
private const val INVALID_OTP_TITLE = "Invalid OTP. Please try again."
private const val INVALID_OTP_SUPPORTING_MESSAGE =
    "The code you entered is incorrect. Please check and try again."

private fun formatCooldown(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/** Shared by registration and password-reset OTP entry; [subtitle] defaults to the registration copy. */
@Composable
internal fun OtpVerificationScreenContent(
    uiState: OtpUiState,
    onOtpChange: (String) -> Unit,
    onVerifyClick: () -> Unit,
    onResendClick: () -> Unit,
    subtitle: String = "We have sent a 6-digit code to ${uiState.mobileNumber}",
    extraContent: @Composable () -> Unit = {}
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            HawkBrandMark(badgeSize = 56.dp, showWordmark = false)

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Verify OTP",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))

            HawkOtpInput(
                value = uiState.otp,
                onValueChange = onOtpChange,
                isError = uiState.otpError != null || uiState.generalError != null
            )
            uiState.otpError?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            uiState.generalError?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                if (error == BACKEND_INCORRECT_OTP_MESSAGE) {
                    AuthErrorBanner(title = INVALID_OTP_TITLE, message = INVALID_OTP_SUPPORTING_MESSAGE)
                } else {
                    AuthErrorBanner(message = error)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (uiState.resendCooldownSeconds > 0) {
                    "Resend OTP in ${formatCooldown(uiState.resendCooldownSeconds)}"
                } else {
                    "You can resend the OTP now"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onVerifyClick,
                enabled = !uiState.isVerifying,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (uiState.isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Verify", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = onResendClick,
                enabled = uiState.resendCooldownSeconds == 0 && !uiState.isResending
            ) {
                Text(if (uiState.isResending) "Resending..." else "Didn't receive the code? Resend OTP")
            }
            extraContent()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtpVerificationScreenPreview() {
    Hawk_WalletTheme {
        OtpVerificationScreenContent(
            uiState = OtpUiState(mobileNumber = "9876543210", otp = "123", resendCooldownSeconds = 28),
            onOtpChange = {},
            onVerifyClick = {},
            onResendClick = {}
        )
    }
}
