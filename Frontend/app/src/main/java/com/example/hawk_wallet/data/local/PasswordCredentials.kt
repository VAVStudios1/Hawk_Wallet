package com.example.hawk_wallet.data.local

import android.content.Context
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException

/** A saved login: the mobile number is the credential's id. */
data class SavedLogin(val mobileNumber: String, val password: String)

/**
 * Login passwords go through Android Credential Manager (e.g. Google
 * Password Manager) - the app itself never stores one. Both calls show
 * system UI and need an Activity context. Every failure, including the
 * user dismissing the sheet or having nothing saved, is quietly null/false:
 * the user can always type their password.
 */
object PasswordCredentials {

    suspend fun save(activityContext: Context, login: SavedLogin): Boolean = try {
        CredentialManager.create(activityContext).createCredential(
            activityContext,
            CreatePasswordRequest(id = login.mobileNumber, password = login.password)
        )
        true
    } catch (e: CreateCredentialException) {
        false
    }

    suspend fun pick(activityContext: Context): SavedLogin? = try {
        val response = CredentialManager.create(activityContext).getCredential(
            activityContext,
            GetCredentialRequest(listOf(GetPasswordOption()))
        )
        (response.credential as? PasswordCredential)?.let { SavedLogin(it.id, it.password) }
    } catch (e: GetCredentialException) {
        null
    }
}
