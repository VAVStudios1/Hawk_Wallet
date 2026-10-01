package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.AuthPasswordField
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.screens.orders.PrimaryActionButton
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.orders.userMessage
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Mirrors core.models.mobile_number_validator (UX only; the backend validates again). */
private val MOBILE_NUMBER = Regex("[6-9][0-9]{9}")

data class AddAdminUiState(
    val name: String = "",
    val mobileNumber: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val nameError: String? = null,
    val mobileError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val generalError: String? = null,
    val isSubmitting: Boolean = false,
    /** Set once the backend created the account. */
    val createdAdminId: Long? = null
)

/**
 * Add Admin (SUPER_ADMIN): POST /api/admin/admins/. The backend always
 * creates an ADMIN - this screen has no role choice - and applies the same
 * name, mobile and password rules as registration. A duplicate submit is
 * refused by the backend's unique mobile number.
 */
class AddAdminViewModel(private val repository: AdminManagementRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AddAdminUiState())
    val uiState: StateFlow<AddAdminUiState> = _uiState

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value.take(150), nameError = null) }

    fun onMobileChange(value: String) =
        _uiState.update { it.copy(mobileNumber = value.filter(Char::isDigit).take(10), mobileError = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, passwordError = null) }

    fun onConfirmPasswordChange(value: String) =
        _uiState.update { it.copy(confirmPassword = value, confirmPasswordError = null) }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting || state.createdAdminId != null) return
        val nameError = if (state.name.isBlank()) "Enter the admin's full name." else null
        val mobileError = if (!MOBILE_NUMBER.matches(state.mobileNumber)) "Enter a valid 10-digit mobile number." else null
        val passwordError = if (state.password.isEmpty()) "Enter a password." else null
        val confirmError = if (state.confirmPassword != state.password) "Passwords do not match." else null
        if (nameError != null || mobileError != null || passwordError != null || confirmError != null) {
            _uiState.update {
                it.copy(nameError = nameError, mobileError = mobileError, passwordError = passwordError, confirmPasswordError = confirmError)
            }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, generalError = null) }
        viewModelScope.launch {
            when (val result = repository.createAdmin(state.name.trim(), state.mobileNumber, state.password, state.confirmPassword)) {
                is ApiResult.Success -> _uiState.update { it.copy(isSubmitting = false, createdAdminId = result.data.id) }
                is ApiResult.Error -> {
                    val fields = result.fieldErrors
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            nameError = fields["name"]?.firstOrNull(),
                            mobileError = fields["mobile_number"]?.firstOrNull(),
                            passwordError = fields["password"]?.joinToString(" "),
                            confirmPasswordError = fields["confirm_password"]?.firstOrNull(),
                            generalError = result.userMessage()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddAdminScreen(
    viewModel: AddAdminViewModel,
    onBackClick: () -> Unit,
    onCreated: (adminId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.createdAdminId) {
        uiState.createdAdminId?.let(onCreated)
    }

    AddAdminContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onNameChange = viewModel::onNameChange,
        onMobileChange = viewModel::onMobileChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onSubmit = viewModel::submit
    )
}

@Composable
private fun AddAdminContent(
    uiState: AddAdminUiState,
    onBackClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onMobileChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Add Admin", onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Creates an Admin account (never a Super Admin). The new admin verifies this mobile number " +
                    "with an OTP the first time they sign in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = uiState.name,
                onValueChange = onNameChange,
                label = { Text("Full Name") },
                isError = uiState.nameError != null,
                supportingText = uiState.nameError?.let { { Text(it) } },
                singleLine = true,
                enabled = !uiState.isSubmitting,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.mobileNumber,
                onValueChange = onMobileChange,
                label = { Text("Mobile Number") },
                isError = uiState.mobileError != null,
                supportingText = uiState.mobileError?.let { { Text(it) } },
                singleLine = true,
                enabled = !uiState.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            AuthPasswordField(
                value = uiState.password,
                onValueChange = onPasswordChange,
                label = "Password",
                isError = uiState.passwordError != null,
                errorText = uiState.passwordError
            )
            AuthPasswordField(
                value = uiState.confirmPassword,
                onValueChange = onConfirmPasswordChange,
                label = "Confirm Password",
                isError = uiState.confirmPasswordError != null,
                errorText = uiState.confirmPasswordError,
                imeAction = ImeAction.Done
            )
            uiState.generalError?.let { AuthErrorBanner(message = it) }
            PrimaryActionButton(text = "Create Admin", onClick = onSubmit, loading = uiState.isSubmitting)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AddAdminPreview() {
    Hawk_WalletTheme {
        AddAdminContent(
            uiState = AddAdminUiState(name = "Neha Admin", mobileNumber = "9876543210", confirmPasswordError = "Passwords do not match."),
            onBackClick = {},
            onNameChange = {},
            onMobileChange = {},
            onPasswordChange = {},
            onConfirmPasswordChange = {},
            onSubmit = {}
        )
    }
}
