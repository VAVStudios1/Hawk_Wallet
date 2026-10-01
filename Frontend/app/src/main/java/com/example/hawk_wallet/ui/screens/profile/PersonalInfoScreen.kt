package com.example.hawk_wallet.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.team.SectionHeader
import com.example.hawk_wallet.ui.screens.team.SurfaceCard
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** Name must fit the backend's User.name column. */
private const val NAME_MAX_LENGTH = 150

@Composable
fun PersonalInfoScreen(
    viewModel: PersonalInfoViewModel,
    onBackClick: () -> Unit,
    /** Referral codes are a member feature; admin screens turn the row off. */
    showReferralCode: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.savedMessage) {
        uiState.savedMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeSavedMessage()
        }
    }

    PersonalInfoContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onRetry = viewModel::load,
        onNameChange = viewModel::onNameChange,
        onSave = viewModel::save,
        showReferralCode = showReferralCode
    )
}

@Composable
private fun PersonalInfoContent(
    uiState: PersonalInfoUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onRetry: () -> Unit,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    showReferralCode: Boolean = true
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Personal Information", onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        val screenModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        val profile = uiState.profile
        when {
            profile != null -> PersonalInfoForm(
                uiState = uiState,
                profile = profile,
                onNameChange = onNameChange,
                onSave = onSave,
                showReferralCode = showReferralCode,
                modifier = screenModifier
            )
            uiState.isLoading || uiState.loadError == null -> CenteredLoading(screenModifier)
            else -> CenteredMessage(
                title = "Couldn't load your profile",
                message = uiState.loadError,
                actionLabel = "Retry",
                onAction = onRetry,
                modifier = screenModifier
            )
        }
    }
}

@Composable
private fun PersonalInfoForm(
    uiState: PersonalInfoUiState,
    profile: ProfileUi,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    showReferralCode: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column {
            SectionHeader(title = "Name", modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = uiState.name,
                onValueChange = { if (it.length <= NAME_MAX_LENGTH) onNameChange(it) },
                label = { Text("Full name") },
                isError = uiState.nameError != null,
                supportingText = uiState.nameError?.let { message -> { Text(message) } },
                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done
                ),
                singleLine = true,
                enabled = !uiState.isSaving,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column {
            SectionHeader(title = "Account Details", modifier = Modifier.padding(bottom = 8.dp))
            SurfaceCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ProfileInfoRow(icon = Icons.Outlined.Phone, label = "Mobile Number") {
                        InfoValue(profile.mobileNumber)
                    }
                    RowDivider()
                    ProfileInfoRow(icon = Icons.Outlined.Badge, label = "Role") {
                        ProfileTag(text = profile.role, color = HawkIndigo)
                    }
                    RowDivider()
                    ProfileInfoRow(icon = Icons.Outlined.VerifiedUser, label = "Account Status") {
                        ProfileTag(
                            text = profile.status,
                            color = if (profile.isVerified) HawkEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (showReferralCode) {
                        RowDivider()
                        ProfileInfoRow(icon = Icons.Outlined.CardGiftcard, label = "Referral Code") {
                            InfoValue(profile.referralCode)
                        }
                    }
                }
            }
            Text(
                text = "Only your name can be changed. Your mobile number is your login ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
            )
        }

        uiState.saveError?.let { AuthErrorBanner(message = it) }

        Button(
            onClick = onSave,
            enabled = uiState.canSave,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Save Changes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// Previews use placeholder values only; the real screen shows nothing but backend data.

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun PersonalInfoPreview() {
    val profile = ProfileUi(
        name = "Preview User",
        mobileNumber = "9000000000",
        role = "Member",
        isVerified = true,
        status = "Verified",
        referralCode = "ABCDEFGH"
    )
    Hawk_WalletTheme {
        PersonalInfoContent(
            uiState = PersonalInfoUiState(profile = profile, name = "Preview User Renamed"),
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onRetry = {},
            onNameChange = {},
            onSave = {}
        )
    }
}
