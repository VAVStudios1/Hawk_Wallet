package com.example.hawk_wallet.data.remote

import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.remote.api.AuthApi
import com.example.hawk_wallet.data.remote.api.HomeApi
import com.example.hawk_wallet.data.remote.api.PayoutMethodApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkModule {
    // Local dev backend only - 10.0.2.2 is the Android emulator's alias for
    // the host machine's localhost. Point this at a real deployed URL once
    // one is configured; nothing here assumes AWS or any specific host.
    private const val BASE_URL = "http://10.42.16.70:8000/"

    // One client for the whole app: every API shares the same base URL and
    // JWT interceptor.
    @Volatile
    private var retrofit: Retrofit? = null

    fun provideAuthApi(sessionManager: SessionManager): AuthApi =
        provideRetrofit(sessionManager).create(AuthApi::class.java)

    fun provideHomeApi(sessionManager: SessionManager): HomeApi =
        provideRetrofit(sessionManager).create(HomeApi::class.java)

    fun providePayoutMethodApi(sessionManager: SessionManager): PayoutMethodApi =
        provideRetrofit(sessionManager).create(PayoutMethodApi::class.java)

    private fun provideRetrofit(sessionManager: SessionManager): Retrofit =
        retrofit ?: synchronized(this) {
            retrofit ?: buildRetrofit(sessionManager).also { retrofit = it }
        }

    private fun buildRetrofit(sessionManager: SessionManager): Retrofit {
        val authInterceptor = Interceptor { chain ->
            val token = runBlocking { sessionManager.accessTokenFlow.first() }
            val request = chain.request().newBuilder().apply {
                if (!token.isNullOrBlank()) {
                    addHeader("Authorization", "Bearer $token")
                }
            }.build()
            chain.proceed(request)
        }

        // BASIC level only - request/response bodies carry passwords, OTPs
        // and JWTs and must never land in Logcat.
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
