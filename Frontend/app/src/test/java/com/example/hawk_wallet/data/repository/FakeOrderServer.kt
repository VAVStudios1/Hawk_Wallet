package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.AdminOrderApi
import com.example.hawk_wallet.data.remote.api.BuyMethodApi
import com.example.hawk_wallet.data.remote.api.OrderApi
import com.example.hawk_wallet.data.remote.api.PayoutMethodApi
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * Like [FakePayoutMethodServer], but serves OrderApi, PayoutMethodApi and
 * BuyMethodApi from one queue. Replies are matched to requests by "METHOD path", so
 * concurrent loads (e.g. offers + orders) get the right body whatever order
 * they run in. Calls run on the calling thread.
 */
class FakeOrderServer {
    val requests = mutableListOf<Request>()
    private val replies = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()

    /** Queues a reply for e.g. `"GET /api/orders/offers/"`. */
    fun reply(route: String, status: Int, json: String) = apply {
        replies.getOrPut(route) { ArrayDeque() }.addLast(status to json)
    }

    fun bodyOf(request: Request): String = Buffer().also { request.body?.writeTo(it) }.readUtf8()

    fun requestsTo(route: String): List<Request> = requests.filter { it.route() == route }

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("http://hawk.test/")
        .client(
            OkHttpClient.Builder().dispatcher(Dispatcher(SameThreadExecutor)).addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += request
                val (status, body) = replies[request.route()]?.removeFirstOrNull()
                    ?: (500 to """{"success":false,"message":"no reply queued for ${request.route()}"}""")
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

    val orderApi: OrderApi = retrofit.create(OrderApi::class.java)
    val payoutMethodApi: PayoutMethodApi = retrofit.create(PayoutMethodApi::class.java)
    val buyMethodApi: BuyMethodApi = retrofit.create(BuyMethodApi::class.java)
    val adminOrderApi: AdminOrderApi = retrofit.create(AdminOrderApi::class.java)

    private fun Request.route() = "$method ${url.encodedPath}"

    private object SameThreadExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    companion object {
        fun offerJson(id: Long, amount: String = "255.00", rate: String = "4.00", income: String = "10.00", target: String = "265.00") =
            """{"id":$id,"currency":"INR","order_amount":"$amount","income_rate":"$rate",
               "income_amount":"$income","payout_target":"$target","valid_to":null}"""

        fun orderJson(id: Long, status: String = "PAYMENT_REQUIRED", latestPayIn: String = "null") =
            """{"id":$id,"reference":"HW-000000000$id","status":"$status","currency":"INR",
               "order_amount":"255.00","income_rate":"4.00","income_amount":"10.00","payout_target":"265.00",
               "payout_method":{"id":3,"provider_code":"PHONEPE","provider_name":"PhonePe","label":"","upi_id":"member@ybl"},
               "claimed_at":"2026-09-26T10:00:00Z","expires_at":null,"activated_at":null,"completed_at":null,
               "created_at":"2026-09-26T10:00:00Z","updated_at":"2026-09-26T10:00:00Z","latest_pay_in":$latestPayIn}"""

        fun intentJson(orderId: Long, token: String = "token-1") =
            """{"order_reference":"HW-000000000$orderId","amount":"255.00","currency":"INR",
               "payee_vpa":"platform@ybl","payee_name":"Hawk Wallet",
               "upi_uri":"upi://pay?pa=platform@ybl&pn=Hawk%20Wallet","intent_token":"$token"}"""

        fun payInJson(orderId: Long, utr: String) =
            """{"id":9,"order":$orderId,"order_reference":"HW-000000000$orderId","amount":"255.00",
               "status":"UNDER_REVIEW","utr":"$utr","payee_vpa":"platform@ybl","payer_app":null,
               "rejection_reason":"","submitted_at":"2026-09-26T10:05:00Z","updated_at":"2026-09-26T10:05:00Z"}"""

        /** One PERSONAL / BUY method in GET /api/buy-methods/ shape. */
        fun buyMethodJson(id: Long, code: String, name: String, upi: String, default: Boolean, active: Boolean = true) =
            """{"id":$id,"provider":{"id":${id + 200},"code":"$code","display_name":"$name"},"label":"","upi_id":"$upi",""" +
                """"is_default":$default,"is_active":$active,"created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:00:00Z"}"""

        fun buyListJson(vararg methods: String) = ok("""{"methods":[${methods.joinToString(",")}]}""")

        /** POST add / default / deactivate reply for a BUY method (flat provider fields). */
        fun buyFlatJson(id: Long, code: String, default: Boolean, active: Boolean = true) =
            ok(
                """{"id":$id,"provider":${id + 200},"provider_code":"$code","provider_name":"x","label":"",""" +
                    """"upi_id":"a@ybl","is_default":$default,"is_active":$active,""" +
                    """"created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:00:00Z"}"""
            )

        fun ok(data: String) = """{"success":true,"message":"ok","data":$data}"""

        fun error(message: String, code: String) =
            """{"success":false,"message":"$message","errors":{"code":"$code"}}"""
    }
}
