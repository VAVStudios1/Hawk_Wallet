package com.example.hawk_wallet.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authDataStore by preferencesDataStore(name = "auth_session")

class SessionManager(private val context: Context) {

    private object Keys {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_ROLE = stringPreferencesKey("user_role")
    }

    val accessTokenFlow: Flow<String?> =
        context.authDataStore.data.map { it[Keys.ACCESS_TOKEN] }

    val isLoggedInFlow: Flow<Boolean> =
        accessTokenFlow.map { !it.isNullOrBlank() }

    val userNameFlow: Flow<String?> =
        context.authDataStore.data.map { it[Keys.USER_NAME] }

    val roleFlow: Flow<String?> =
        context.authDataStore.data.map { it[Keys.USER_ROLE] }

    suspend fun saveSession(accessToken: String, refreshToken: String, name: String, role: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.REFRESH_TOKEN] = refreshToken
            prefs[Keys.USER_NAME] = name
            prefs[Keys.USER_ROLE] = role
        }
    }

    suspend fun getRefreshToken(): String? =
        context.authDataStore.data.map { it[Keys.REFRESH_TOKEN] }.first()

    suspend fun clearSession() {
        context.authDataStore.edit { it.clear() }
    }
}
