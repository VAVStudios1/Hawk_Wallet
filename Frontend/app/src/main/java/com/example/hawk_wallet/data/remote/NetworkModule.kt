package com.example.hawk_wallet.data.remote

import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.remote.api.AdminDashboardApi
import com.example.hawk_wallet.data.remote.api.AdminManagementApi
import com.example.hawk_wallet.data.remote.api.AdminOrderApi
import com.example.hawk_wallet.data.remote.api.AdminUserApi
import com.example.hawk_wallet.data.remote.api.AuthApi
import com.example.hawk_wallet.data.remote.api.BuyMethodApi
import com.example.hawk_wallet.data.remote.api.HomeApi
import com.example.hawk_wallet.data.remote.api.OrderApi
import com.example.hawk_wallet.data.remote.api.PayoutMethodApi
import com.example.hawk_wallet.data.remote.api.ProfileApi
import com.example.hawk_wallet.data.remote.api.SupportChatApi
import com.example.hawk_wallet.data.remote.api.TeamApi
import com.example.hawk_wallet.data.remote.api.TokenRefreshApi
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkModule {
    // Local dev backend only - 10.0.2.2 is the Android emulator's alias for
    // the host machine's localhost. Point this at a real deployed URL once
    // one is configured; nothing here assumes AWS or any specific host.
    private const val BASE_URL = "http://10.14.167.70:8000/"

//    private const val BASE_URL = "https://1ee9-152-59-28-213.ngrok-free.app"

    // One client for the whole app: every API shares the same base URL, JWT
    // interceptor and refresh-on-401 authenticator.
    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var sessionRefresher: SessionRefresher? = null

    private const val AUTH_PATH_PREFIX = "/api/auth/"

    /**
     * Every api/auth/ endpoint (login, register, OTPs, password reset,
     * refresh, logout) is anonymous: no JWT is attached, and a 401 from one
     * never triggers a refresh.
     */
    fun isAnonymousAuthEndpoint(request: Request): Boolean =
        request.url.encodedPath.startsWith(AUTH_PATH_PREFIX)

    /** The app's single refresher, shared by the authenticator and startup. */
    fun provideSessionRefresher(sessionManager: SessionManager): SessionRefresher =
        sessionRefresher ?: synchronized(this) {
            sessionRefresher ?: SessionRefresher(
                sessionManager,
                buildRetrofit(buildClient()).create(TokenRefreshApi::class.java)
            ).also { sessionRefresher = it }
        }

    fun provideAuthApi(sessionManager: SessionManager): AuthApi =
        provideRetrofit(sessionManager).create(AuthApi::class.java)

    fun provideHomeApi(sessionManager: SessionManager): HomeApi =
        provideRetrofit(sessionManager).create(HomeApi::class.java)

    fun providePayoutMethodApi(sessionManager: SessionManager): PayoutMethodApi =
        provideRetrofit(sessionManager).create(PayoutMethodApi::class.java)

    fun provideBuyMethodApi(sessionManager: SessionManager): BuyMethodApi =
        provideRetrofit(sessionManager).create(BuyMethodApi::class.java)

    fun provideOrderApi(sessionManager: SessionManager): OrderApi =
        provideRetrofit(sessionManager).create(OrderApi::class.java)

    fun provideAdminOrderApi(sessionManager: SessionManager): AdminOrderApi =
        provideRetrofit(sessionManager).create(AdminOrderApi::class.java)

    fun provideAdminDashboardApi(sessionManager: SessionManager): AdminDashboardApi =
        provideRetrofit(sessionManager).create(AdminDashboardApi::class.java)

    fun provideAdminUserApi(sessionManager: SessionManager): AdminUserApi =
        provideRetrofit(sessionManager).create(AdminUserApi::class.java)

    fun provideAdminManagementApi(sessionManager: SessionManager): AdminManagementApi =
        provideRetrofit(sessionManager).create(AdminManagementApi::class.java)

    fun provideTeamApi(sessionManager: SessionManager): TeamApi =
        provideRetrofit(sessionManager).create(TeamApi::class.java)

    fun provideProfileApi(sessionManager: SessionManager): ProfileApi =
        provideRetrofit(sessionManager).create(ProfileApi::class.java)

    fun provideSupportChatApi(sessionManager: SessionManager): SupportChatApi =
        provideRetrofit(sessionManager).create(SupportChatApi::class.java)

    private fun provideRetrofit(sessionManager: SessionManager): Retrofit =
        retrofit ?: synchronized(this) {
            retrofit ?: buildRetrofit(buildAuthenticatedClient(sessionManager)).also { retrofit = it }
        }

    private fun buildAuthenticatedClient(sessionManager: SessionManager): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val token = if (isAnonymousAuthEndpoint(original)) {
                null
            } else {
                runBlocking { sessionManager.getAccessToken() }
            }
            val request = original.newBuilder().apply {
                if (!token.isNullOrBlank()) {
                    header(SessionAuthenticator.AUTHORIZATION, SessionAuthenticator.BEARER_PREFIX + token)
                }
            }.build()
            chain.proceed(request)
        }

        return buildClient {
            addInterceptor(authInterceptor)
            authenticator(SessionAuthenticator(provideSessionRefresher(sessionManager)))
        }
    }

    private fun buildClient(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient {
        // BASIC level only - request/response bodies carry passwords, OTPs
        // and JWTs and must never land in Logcat.
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .apply(configure)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    private fun buildRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
}
