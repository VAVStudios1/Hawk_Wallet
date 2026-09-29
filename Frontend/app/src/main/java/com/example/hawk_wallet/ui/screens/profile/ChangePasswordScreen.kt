package com.example.hawk_wallet.ui.screens.profile

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.AuthPasswordField
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

@Composable
fun ChangePasswordScreen(
    viewModel: ChangePasswordViewModel,
    onBackClick: () -> Unit,
    /** Called once with the backend's success message; the new tokens are already stored. */
    onPasswordChanged: (message: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let(onPasswordChanged)
    }

    ChangePasswordContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onCurrentPasswordChange = viewModel::onCurrentPasswordChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onSubmit = viewModel::submit
    )
}

@Composable
private fun ChangePasswordContent(
    uiState: ChangePasswordUiState,
    onBackClick: () -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Change Password", onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "After changing your password you'll stay signed in here and be signed out on your other devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            AuthPasswordField(
                value = uiState.currentPassword,
                onValueChange = onCurrentPasswordChange,
                label = "Current Password",
                isError = uiState.currentPasswordError != null,
                errorText = uiState.currentPasswordError
            )
            AuthPasswordField(
                value = uiState.newPassword,
                onValueChange = onNewPasswordChange,
                label = "New Password",
                isError = uiState.newPasswordError != null,
                errorText = uiState.newPasswordError
            )
            AuthPasswordField(
                value = uiState.confirmPassword,
                onValueChange = onConfirmPasswordChange,
                label = "Confirm New Password",
                isError = uiState.confirmPasswordError != null,
                errorText = uiState.confirmPasswordError,
                imeAction = ImeAction.Done
            )

            uiState.generalError?.let { AuthErrorBanner(message = it) }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onSubmit,
                enabled = !uiState.isSubmitting && uiState.successMessage == null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Change Password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChangePasswordPreview() {
    Hawk_WalletTheme {
        ChangePasswordContent(
            uiState = ChangePasswordUiState(currentPasswordError = "Current password is incorrect."),
            onBackClick = {},
            onCurrentPasswordChange = {},
            onNewPasswordChange = {},
            onConfirmPasswordChange = {},
            onSubmit = {}
        )
    }
}
