package com.example.hawk_wallet.ui.screens.auth.forgot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.AuthPasswordField
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.screens.auth.otp.OtpVerificationScreenContent
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

// --- step 1 ------------------------------------------------------------------

@Composable
fun ForgotPasswordMobileScreen(
    viewModel: ForgotPasswordViewModel,
    onBackClick: () -> Unit,
    onOtpRequested: () -> Unit
) {
    val uiState by viewModel.mobileState.collectAsState()

    LaunchedEffect(uiState.otpRequested) {
        if (uiState.otpRequested) {
            viewModel.consumeOtpRequested()
            onOtpRequested()
        }
    }

    ForgotPasswordMobileContent(
        uiState = uiState,
        onMobileNumberChange = viewModel::onMobileNumberChange,
        onSendOtpClick = viewModel::sendOtp,
        onBackClick = onBackClick
    )
}

@Composable
private fun ForgotPasswordMobileContent(
    uiState: ForgotPasswordMobileUiState,
    onMobileNumberChange: (String) -> Unit,
    onSendOtpClick: () -> Unit,
    onBackClick: () -> Unit
) {
    AuthStepScaffold(title = "Forgot Password", onBackClick = onBackClick) {
        StepHeading(
            title = "Reset your password",
            subtitle = "Enter the mobile number of your account. We'll send you a code to reset your password."
        )

        OutlinedTextField(
            value = uiState.mobileNumber,
            onValueChange = onMobileNumberChange,
            label = { Text("Mobile number") },
            isError = uiState.mobileError != null,
            supportingText = { uiState.mobileError?.let { Text(it) } },
            leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        uiState.generalError?.let {
            Spacer(modifier = Modifier.height(16.dp))
            AuthErrorBanner(message = it)
        }

        Spacer(modifier = Modifier.height(28.dp))
        PrimaryAuthButton(text = "Send OTP", isLoading = uiState.isSending, onClick = onSendOtpClick)
    }
}

// --- step 2 ------------------------------------------------------------------

@Composable
fun ForgotPasswordOtpScreen(
    viewModel: ForgotPasswordViewModel,
    onVerified: () -> Unit
) {
    val uiState by viewModel.otpState.collectAsState()

    LaunchedEffect(uiState.verified) {
        if (uiState.verified) {
            viewModel.consumeOtpVerified()
            onVerified()
        }
    }

    OtpVerificationScreenContent(
        uiState = uiState.otp,
        onOtpChange = viewModel::onOtpChange,
        onVerifyClick = viewModel::verifyOtp,
        onResendClick = viewModel::resendOtp,
        // The backend does not say whether the number has an account.
        subtitle = "If ${uiState.otp.mobileNumber} belongs to an account, we have sent it a 6-digit code."
    )
}

// --- step 3 ------------------------------------------------------------------

@Composable
fun ResetPasswordScreen(
    viewModel: ForgotPasswordViewModel,
    onAbandon: () -> Unit,
    onStartAgain: () -> Unit,
    onCompleted: (message: String) -> Unit
) {
    val uiState by viewModel.resetState.collectAsState()

    LaunchedEffect(uiState.completedMessage) {
        uiState.completedMessage?.let {
            viewModel.consumeCompleted()
            onCompleted(it)
        }
    }

    // Leaving this step drops the reset token; the OTP is already used, so
    // going back means starting over.
    val leave = {
        viewModel.abandonReset()
        onAbandon()
    }
    BackHandler(onBack = leave)

    ResetPasswordContent(
        uiState = uiState,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onResetClick = viewModel::resetPassword,
        onBackClick = leave,
        onStartAgainClick = {
            viewModel.abandonReset()
            onStartAgain()
        }
    )
}

@Composable
private fun ResetPasswordContent(
    uiState: ResetPasswordUiState,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onResetClick: () -> Unit,
    onBackClick: () -> Unit,
    onStartAgainClick: () -> Unit
) {
    AuthStepScaffold(title = "Reset Password", onBackClick = onBackClick) {
        StepHeading(
            title = "Create a new password",
            subtitle = "Use at least 8 characters. Your old password will stop working."
        )

        AuthPasswordField(
            value = uiState.newPassword,
            onValueChange = onNewPasswordChange,
            label = "New password",
            isError = uiState.newPasswordError != null,
            errorText = uiState.newPasswordError,
            modifier = Modifier.semantics { contentType = ContentType.NewPassword }
        )
        Spacer(modifier = Modifier.height(16.dp))
        AuthPasswordField(
            value = uiState.confirmPassword,
            onValueChange = onConfirmPasswordChange,
            label = "Confirm new password",
            isError = uiState.confirmPasswordError != null,
            errorText = uiState.confirmPasswordError,
            imeAction = ImeAction.Done,
            modifier = Modifier.semantics { contentType = ContentType.NewPassword }
        )

        uiState.generalError?.let {
            Spacer(modifier = Modifier.height(16.dp))
            AuthErrorBanner(message = it)
        }

        Spacer(modifier = Modifier.height(28.dp))
        if (uiState.mustRestart) {
            OutlinedButton(
                onClick = onStartAgainClick,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Start again", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        } else {
            PrimaryAuthButton(text = "Reset Password", isLoading = uiState.isSaving, onClick = onResetClick)
        }
    }
}

// --- shared layout -----------------------------------------------------------

@Composable
private fun AuthStepScaffold(
    title: String,
    onBackClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = title, onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Top,
            content = content
        )
    }
}

@Composable
private fun StepHeading(title: String, subtitle: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(28.dp))
}

@Composable
private fun PrimaryAuthButton(text: String, isLoading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !isLoading,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ForgotPasswordMobilePreview() {
    Hawk_WalletTheme {
        ForgotPasswordMobileContent(
            uiState = ForgotPasswordMobileUiState(mobileNumber = "9876543210"),
            onMobileNumberChange = {},
            onSendOtpClick = {},
            onBackClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ResetPasswordPreview() {
    Hawk_WalletTheme {
        ResetPasswordContent(
            uiState = ResetPasswordUiState(),
            onNewPasswordChange = {},
            onConfirmPasswordChange = {},
            onResetClick = {},
            onBackClick = {},
            onStartAgainClick = {}
        )
    }
}
