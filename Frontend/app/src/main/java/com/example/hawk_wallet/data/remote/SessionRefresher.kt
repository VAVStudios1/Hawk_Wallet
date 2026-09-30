package com.example.hawk_wallet.data.remote

import android.util.Base64
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.remote.api.TokenRefreshApi
import com.example.hawk_wallet.data.remote.model.TokenRefreshRequest
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/**
 * Exchanges the refresh token for a new pair - one exchange at a time.
 *
 * The backend makes refresh tokens single-use (rotation + blacklist), so if
 * several requests fail with 401 together, only the first caller refreshes;
 * the others wait on the lock, see the access token has already changed and
 * reuse it.
 *
 * Sends idle_seconds = time since the user's last real interaction, so
 * refreshes caused by polling never extend an idle session. A refused
 * refresh (any 4xx: session_expired, revoked, invalid) ends the session
 * app-wide via SessionManager.expireSession(). A network or server error
 * leaves the session alone.
 *
 * Blocking: call from a background thread (OkHttp's, or Dispatchers.IO).
 */
class SessionRefresher(
    private val sessionManager: SessionManager,
    private val refreshApi: TokenRefreshApi
) {
    sealed interface Outcome {
        data class Refreshed(val accessToken: String) : Outcome
        data object SessionEnded : Outcome
        data object Unavailable : Outcome
    }

    private val lock = Any()

    /** [failedAccessToken] is the token the 401 was for (null if none was sent). */
    fun refresh(failedAccessToken: String?): Outcome = synchronized(lock) {
        runBlocking<Outcome> {
            val current = sessionManager.getAccessToken()
            if (current != null && current != failedAccessToken) {
                return@runBlocking Outcome.Refreshed(current)
            }

            val refreshToken = sessionManager.getRefreshToken()
            if (refreshToken == null || sessionManager.isIdleExpired()) {
                if (sessionManager.hasSession()) sessionManager.expireSession()
                return@runBlocking Outcome.SessionEnded
            }

            val response = try {
                refreshApi.refresh(TokenRefreshRequest(refreshToken, sessionManager.idleSeconds())).execute()
            } catch (e: IOException) {
                return@runBlocking Outcome.Unavailable
            }

            val tokens = response.body()?.takeIf { response.isSuccessful && it.success }?.data
            when {
                tokens != null -> {
                    sessionManager.updateTokens(tokens.access, tokens.refresh)
                    Outcome.Refreshed(tokens.access)
                }
                response.code() in 400..499 -> {
                    sessionManager.expireSession()
                    Outcome.SessionEnded
                }
                else -> Outcome.Unavailable
            }
        }
    }
}

/**
 * On a 401 from a protected endpoint: refresh once and retry the request
 * once with the new access token. Anonymous api/auth/ endpoints and an
 * already-retried request are left alone.
 */
class SessionAuthenticator(private val refresher: SessionRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (NetworkModule.isAnonymousAuthEndpoint(request)) return null
        if (response.priorResponse != null) return null

        val failedToken = request.header(AUTHORIZATION)?.removePrefix(BEARER_PREFIX)
        return when (val outcome = refresher.refresh(failedToken)) {
            is SessionRefresher.Outcome.Refreshed ->
                request.newBuilder().header(AUTHORIZATION, BEARER_PREFIX + outcome.accessToken).build()
            else -> null
        }
    }

    internal companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}

/** Reads a JWT's `exp` without verifying it - only to skip a pointless request. */
internal object JwtExpiry {
    private const val SKEW_SECONDS = 30L

    fun isExpiredOrUnreadable(token: String?): Boolean {
        val exp = token?.let(::expiresAtSeconds) ?: return true
        return exp - SKEW_SECONDS <= System.currentTimeMillis() / 1000
    }

    private fun expiresAtSeconds(token: String): Long? = try {
        val payload = token.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        JsonParser.parseString(json).asJsonObject.get("exp")?.asLong
    } catch (e: Exception) {
        null
    }
}
