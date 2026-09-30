package com.example.hawk_wallet.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private val Context.authDataStore by preferencesDataStore(name = "auth_session")

/** Mirrors the backend's SESSION_IDLE_TIMEOUT; the backend stays authoritative. */
val SESSION_IDLE_TIMEOUT_MILLIS: Long = TimeUnit.HOURS.toMillis(12)

/** What startup needs from a stored session that is still usable locally. */
data class StoredSession(val accessToken: String?, val role: String?)

/**
 * The signed-in session. Access and refresh tokens are stored only encrypted
 * (TokenCipher, Android Keystore AES-GCM); name, role, the last activity time
 * and the remembered login mobile number are not secret and stay plain.
 *
 * Activity: [recordUserActivity] is called for real user interaction only
 * (MainActivity.onUserInteraction) - never for polling, refreshes or timers.
 * It is kept in memory and written to disk at most once a minute and when
 * the app goes to the background ([flushUserActivity]).
 */
class SessionManager(context: Context, private val cipher: TokenCipher = TokenCipher()) {

    private val dataStore = context.applicationContext.authDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        // Pre-encryption plaintext tokens. Only read to detect and drop an
        // old session (it has no activity record to apply the idle rule to).
        val LEGACY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val LEGACY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")

        val ACCESS_TOKEN = stringPreferencesKey("access_token_enc")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token_enc")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_ROLE = stringPreferencesKey("user_role")
        val LAST_ACTIVITY_AT = longPreferencesKey("last_activity_at")

        // Survives logout: only pre-fills the login form. Never a password.
        val LAST_LOGIN_MOBILE = stringPreferencesKey("last_login_mobile")
    }

    private val sessionKeys: List<Preferences.Key<*>> = listOf(
        Keys.LEGACY_ACCESS_TOKEN,
        Keys.LEGACY_REFRESH_TOKEN,
        Keys.ACCESS_TOKEN,
        Keys.REFRESH_TOKEN,
        Keys.USER_NAME,
        Keys.USER_ROLE,
        Keys.LAST_ACTIVITY_AT
    )

    // Decrypting on every request is wasteful; remember the last result.
    @Volatile
    private var decryptCache: Pair<String, String>? = null

    @Volatile
    private var lastActivityAtMillis: Long = 0L

    @Volatile
    private var lastPersistedActivityAtMillis: Long = 0L

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits when the session ends without the user logging out (refresh
     * refused, idle timeout). The navigation host sends the user to Login.
     */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    val accessTokenFlow: Flow<String?> =
        dataStore.data.map { decrypt(it[Keys.ACCESS_TOKEN]) }

    val isLoggedInFlow: Flow<Boolean> =
        dataStore.data.map { !it[Keys.REFRESH_TOKEN].isNullOrBlank() }

    val userNameFlow: Flow<String?> =
        dataStore.data.map { it[Keys.USER_NAME] }

    val roleFlow: Flow<String?> =
        dataStore.data.map { it[Keys.USER_ROLE] }

    /** A successful login. Logging in counts as user activity. */
    suspend fun saveSession(accessToken: String, refreshToken: String, name: String, role: String) {
        val now = System.currentTimeMillis()
        lastActivityAtMillis = now
        lastPersistedActivityAtMillis = now
        dataStore.edit { prefs ->
            sessionKeys.forEach { prefs.remove(it) }
            prefs[Keys.ACCESS_TOKEN] = cipher.encrypt(accessToken)
            prefs[Keys.REFRESH_TOKEN] = cipher.encrypt(refreshToken)
            prefs[Keys.USER_NAME] = name
            prefs[Keys.USER_ROLE] = role
            prefs[Keys.LAST_ACTIVITY_AT] = now
        }
    }

    /** Replaces only the token pair (a refresh, or a password change). */
    suspend fun updateTokens(accessToken: String, refreshToken: String) {
        dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = cipher.encrypt(accessToken)
            prefs[Keys.REFRESH_TOKEN] = cipher.encrypt(refreshToken)
        }
    }

    /** Keeps the cached display name in step after the backend accepts a name change. */
    suspend fun updateUserName(name: String) {
        dataStore.edit { prefs -> prefs[Keys.USER_NAME] = name }
    }

    suspend fun getAccessToken(): String? = accessTokenFlow.first()

    suspend fun getRefreshToken(): String? =
        decrypt(dataStore.data.first()[Keys.REFRESH_TOKEN])

    /** Clears the session (logout). The remembered login mobile number stays. */
    suspend fun clearSession() {
        lastActivityAtMillis = 0L
        lastPersistedActivityAtMillis = 0L
        decryptCache = null
        dataStore.edit { prefs -> sessionKeys.forEach { prefs.remove(it) } }
    }

    /** Clears the session and tells the app it expired (see [sessionExpired]). */
    suspend fun expireSession() {
        clearSession()
        _sessionExpired.tryEmit(Unit)
    }

    /**
     * Startup check. Returns the stored session if it can still be used, or
     * clears whatever is there and returns null when it cannot: nothing
     * stored, an old plaintext session, tokens that no longer decrypt, or
     * [SESSION_IDLE_TIMEOUT_MILLIS] without user activity.
     */
    suspend fun restoreSession(): StoredSession? {
        val prefs = dataStore.data.first()
        val hasAnything = sessionKeys.any { prefs.contains(it) }
        val legacy = prefs.contains(Keys.LEGACY_ACCESS_TOKEN) || prefs.contains(Keys.LEGACY_REFRESH_TOKEN)
        val refreshToken = decrypt(prefs[Keys.REFRESH_TOKEN])
        val usable = !legacy && refreshToken != null && !isIdleExpired()
        if (!usable) {
            if (hasAnything) clearSession()
            return null
        }
        return StoredSession(accessToken = decrypt(prefs[Keys.ACCESS_TOKEN]), role = prefs[Keys.USER_ROLE])
    }

    /** Real user interaction (taps, typing). Cheap: disk is written at most once a minute. */
    fun recordUserActivity() {
        val now = System.currentTimeMillis()
        lastActivityAtMillis = now
        if (now - lastPersistedActivityAtMillis >= ACTIVITY_PERSIST_INTERVAL_MILLIS) {
            lastPersistedActivityAtMillis = now
            scope.launch { persistActivity(now) }
        }
    }

    /**
     * Writes the latest activity time now (the app is going to the
     * background). Runs on this manager's own scope, so it is not cut short
     * if the activity is destroyed right after.
     */
    fun flushUserActivity() {
        val latest = lastActivityAtMillis
        if (latest > lastPersistedActivityAtMillis) {
            lastPersistedActivityAtMillis = latest
            scope.launch { persistActivity(latest) }
        }
    }

    /** Last real user activity (epoch millis), or null if there is no session record. */
    suspend fun lastUserActivityAt(): Long? {
        val stored = dataStore.data.first()[Keys.LAST_ACTIVITY_AT] ?: 0L
        val latest = maxOf(stored, lastActivityAtMillis)
        return latest.takeIf { it > 0L }
    }

    /** Whole seconds since the last real user activity, or null if unknown. */
    suspend fun idleSeconds(): Long? {
        val last = lastUserActivityAt() ?: return null
        return TimeUnit.MILLISECONDS.toSeconds((System.currentTimeMillis() - last).coerceAtLeast(0L))
    }

    /** True once the local mirror of the backend idle timeout has run out. */
    suspend fun isIdleExpired(): Boolean {
        val last = lastUserActivityAt() ?: return true
        return System.currentTimeMillis() - last >= SESSION_IDLE_TIMEOUT_MILLIS
    }

    suspend fun hasSession(): Boolean = isLoggedInFlow.first()

    suspend fun rememberLoginMobile(mobileNumber: String) {
        dataStore.edit { prefs -> prefs[Keys.LAST_LOGIN_MOBILE] = mobileNumber }
    }

    suspend fun rememberedLoginMobile(): String? = dataStore.data.first()[Keys.LAST_LOGIN_MOBILE]

    private suspend fun persistActivity(atMillis: Long) {
        dataStore.edit { prefs ->
            // Only while signed in, and never moving backwards.
            if (prefs.contains(Keys.REFRESH_TOKEN) && (prefs[Keys.LAST_ACTIVITY_AT] ?: 0L) < atMillis) {
                prefs[Keys.LAST_ACTIVITY_AT] = atMillis
            }
        }
    }

    private fun decrypt(encrypted: String?): String? {
        if (encrypted.isNullOrBlank()) return null
        decryptCache?.let { (cipherText, plainText) -> if (cipherText == encrypted) return plainText }
        return cipher.decrypt(encrypted)?.also { decryptCache = encrypted to it }
    }

    private companion object {
        val ACTIVITY_PERSIST_INTERVAL_MILLIS: Long = TimeUnit.MINUTES.toMillis(1)
    }
}
