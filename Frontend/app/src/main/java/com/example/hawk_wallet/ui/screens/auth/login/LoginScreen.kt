package com.example.hawk_wallet.ui.screens.auth.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.hawk_wallet.data.local.PasswordCredentials
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.AuthFooterLink
import com.example.hawk_wallet.ui.components.AuthPasswordField
import com.example.hawk_wallet.ui.components.HawkBrandMark
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onNavigateToRegister: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    onLoggedIn: (role: String) -> Unit,
    onVerificationRequired: (mobileNumber: String) -> Unit,
    pendingNotice: String? = null,
    onPendingNoticeShown: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val autofillManager = LocalAutofillManager.current

    // Offer the logins saved in Credential Manager once per visit.
    LaunchedEffect(Unit) {
        if (viewModel.shouldOfferSavedLogin()) {
            PasswordCredentials.pick(context)?.let(viewModel::onSavedLoginPicked)
        }
    }
    LaunchedEffect(pendingNotice) {
        pendingNotice?.let {
            viewModel.showNotice(it)
            onPendingNoticeShown()
        }
    }
    LaunchedEffect(uiState.loggedInRole) {
        uiState.loggedInRole?.let { role ->
            // Offered before leaving, so the save sheet is not cancelled by
            // navigation. Credential Manager handles the save, so the
            // autofill framework's own save prompt is dismissed.
            uiState.credentialToSave?.let { PasswordCredentials.save(context, it) }
            autofillManager?.cancel()
            viewModel.consumeLoggedIn()
            onLoggedIn(role)
        }
    }
    LaunchedEffect(uiState.verificationRequiredFor) {
        uiState.verificationRequiredFor?.let {
            viewModel.consumeVerificationRequired()
            onVerificationRequired(it)
        }
    }

    LoginScreenContent(
        uiState = uiState,
        onMobileNumberChange = viewModel::onMobileNumberChange,
        onPasswordChange = viewModel::onPasswordChange,
        onLoginClick = viewModel::login,
        onForgotPasswordClick = onForgotPasswordClick,
        onNavigateToRegister = onNavigateToRegister
    )
}

@Composable
private fun LoginScreenContent(
    uiState: LoginUiState,
    onMobileNumberChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    onNavigateToRegister: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center
        ) {
            HawkBrandMark(modifier = Modifier.fillMaxWidth(), badgeSize = 64.dp)

            Spacer(modifier = Modifier.height(36.dp))
            Text(
                text = "Welcome Back!",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Sign in to continue your journey.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))

            uiState.notice?.let {
                LoginNoticeBanner(message = it)
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = uiState.mobileNumber,
                onValueChange = onMobileNumberChange,
                label = { Text("Mobile number") },
                isError = uiState.mobileError != null,
                supportingText = { uiState.mobileError?.let { Text(it) } },
                leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                // The mobile number is the account's username for autofill.
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Username }
            )
            Spacer(modifier = Modifier.height(16.dp))
            AuthPasswordField(
                value = uiState.password,
                onValueChange = onPasswordChange,
                label = "Password",
                isError = uiState.passwordError != null,
                errorText = uiState.passwordError,
                imeAction = ImeAction.Done,
                modifier = Modifier.semantics { contentType = ContentType.Password }
            )
            TextButton(
                onClick = onForgotPasswordClick,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Forgot Password?")
            }

            uiState.generalError?.let {
                Spacer(modifier = Modifier.height(16.dp))
                AuthErrorBanner(message = it)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onLoginClick,
                enabled = !uiState.isLoading,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Sign In", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            AuthFooterLink(
                promptText = "Don't have an account?",
                actionText = "Register",
                onActionClick = onNavigateToRegister
            )
        }
    }
}

/** Informational, not an error: e.g. "session expired" or "password reset". */
@Composable
private fun LoginNoticeBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    Hawk_WalletTheme {
        LoginScreenContent(
            uiState = LoginUiState(),
            onMobileNumberChange = {},
            onPasswordChange = {},
            onLoginClick = {},
            onForgotPasswordClick = {},
            onNavigateToRegister = {}
        )
    }
}
