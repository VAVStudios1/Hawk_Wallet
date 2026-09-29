package com.example.hawk_wallet.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.ProfileRepository
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Personal Information: loads GET /api/profile/ and saves the name with
 * PATCH /api/profile/ (name only). The backend trims and validates it; the
 * saved value it returns is what the screen shows.
 */
class PersonalInfoViewModel(
    private val repository: ProfileRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonalInfoUiState())
    val uiState: StateFlow<PersonalInfoUiState> = _uiState

    init {
        load()
    }

    fun load() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            when (val result = repository.getProfile()) {
                is ApiResult.Success -> {
                    val profile = result.data.toUi()
                    _uiState.update { it.copy(isLoading = false, profile = profile, name = profile.name) }
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, loadError = result.userMessage())
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value, nameError = null, saveError = null) }
    }

    fun save() {
        val state = _uiState.value
        val name = state.name.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(nameError = "Name is required.") }
            return
        }
        if (!state.canSave) return

        _uiState.update { it.copy(isSaving = true, nameError = null, saveError = null) }
        viewModelScope.launch {
            when (val result = repository.updateName(name)) {
                is ApiResult.Success -> {
                    val profile = result.data.toUi()
                    sessionManager.updateUserName(profile.name)
                    _uiState.update {
                        it.copy(isSaving = false, profile = profile, name = profile.name, savedMessage = "Name updated")
                    }
                }
                is ApiResult.Error -> {
                    val nameFieldError = result.fieldErrors["name"]?.firstOrNull()
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            nameError = nameFieldError,
                            saveError = if (nameFieldError == null) result.userMessage() else null
                        )
                    }
                }
            }
        }
    }

    fun consumeSavedMessage() {
        _uiState.update { it.copy(savedMessage = null) }
    }
}
