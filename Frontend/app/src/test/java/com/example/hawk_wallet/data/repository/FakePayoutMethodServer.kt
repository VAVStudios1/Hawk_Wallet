package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.PayoutMethodApi
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * A real Retrofit + OkHttp stack whose network is an interceptor: requests
 * made through PayoutMethodApi are recorded, and each gets the next queued
 * (status, JSON body) reply. Exercises the actual Retrofit annotations.
 * Calls run on the calling thread, so a ViewModel's request has finished
 * by the time refresh()/save() returns in a test.
 */
class FakePayoutMethodServer {
    val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()

    fun reply(status: Int, json: String) = apply { replies.addLast(status to json) }

    val api: PayoutMethodApi = Retrofit.Builder()
        .baseUrl("http://hawk.test/")
        .client(
            OkHttpClient.Builder().dispatcher(Dispatcher(SameThreadExecutor)).addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += request
                val (status, body) = replies.removeFirstOrNull() ?: (500 to """{"success":false,"message":"no reply queued"}""")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }).build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(PayoutMethodApi::class.java)

    private object SameThreadExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    companion object {
        fun methodJson(id: Long, code: String, name: String, upi: String, default: Boolean, active: Boolean = true) =
            """{"id":$id,"provider":{"id":${id + 100},"code":"$code","display_name":"$name"},"label":"","upi_id":"$upi",""" +
                """"is_default":$default,"is_active":$active,"created_at":"2026-09-25T10:00:00Z","updated_at":"2026-09-25T10:00:00Z"}"""

        fun listJson(vararg methods: String) =
            """{"success":true,"message":"Payout methods.","data":{"methods":[${methods.joinToString(",")}]}}"""

        fun flatJson(id: Long, code: String, default: Boolean, active: Boolean = true) =
            """{"success":true,"message":"ok","data":{"id":$id,"provider":${id + 100},"provider_code":"$code",""" +
                """"provider_name":"x","label":"","upi_id":"a@ybl","is_default":$default,"is_active":$active,""" +
                """"created_at":"2026-09-25T10:00:00Z","updated_at":"2026-09-25T10:00:00Z"}}"""
    }
}
