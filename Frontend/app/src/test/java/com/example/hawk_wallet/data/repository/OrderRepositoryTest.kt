package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.error
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.intentJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.offerJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.ok
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.orderJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.payInJson
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderRepositoryTest {

    private val server = FakeOrderServer()
    private val repository = OrderRepository(server.orderApi)

    @Test
    fun offers_returnsBackendValuesUnchanged() = runTest {
        server.reply("GET /api/orders/offers/", 200, ok("""{"offers":[${offerJson(1, "150.00", "3.00", "5.00", "155.00")}]}"""))

        val offer = (repository.offers() as ApiResult.Success).data.single()

        assertEquals("150.00", offer.order_amount)
        assertEquals("3.00", offer.income_rate)
        assertEquals("5.00", offer.income_amount)
        assertEquals("155.00", offer.payout_target)
        assertNull(offer.valid_to)
    }

    @Test
    fun orders_listAndDetail() = runTest {
        server.reply("GET /api/orders/", 200, ok("""{"orders":[${orderJson(7)}]}"""))
        server.reply(
            "GET /api/orders/7/", 200,
            ok(
                orderJson(
                    7,
                    latestPayIn = """{"id":9,"status":"REJECTED","amount":"255.00","utr":"012345678901",
                        "rejection_reason":"Wrong UTR","submitted_at":"2026-09-26T10:05:00Z","updated_at":"2026-09-26T11:00:00Z"}"""
                )
            )
        )

        val listed = (repository.orders() as ApiResult.Success).data.single()
        val detail = (repository.order(7) as ApiResult.Success).data

        assertEquals("HW-0000000007", listed.reference)
        assertNull(listed.latest_pay_in)
        assertEquals("member@ybl", detail.payout_method.upi_id)
        assertEquals("REJECTED", detail.latest_pay_in?.status)
        assertEquals("Wrong UTR", detail.latest_pay_in?.rejection_reason)
    }

    @Test
    fun claim_sendsOnlyOfferMethodAndKey() = runTest {
        server.reply("POST /api/orders/claim/", 201, ok(orderJson(7)))

        val result = repository.claim(offerId = 1, payoutMethodId = 3, idempotencyKey = "key-1")

        assertTrue(result is ApiResult.Success)
        val body = JsonParser.parseString(server.bodyOf(server.requests.single())).asJsonObject
        assertEquals(setOf("offer_id", "payout_method_id", "idempotency_key"), body.keySet())
        assertEquals(1L, body["offer_id"].asLong)
        assertEquals(3L, body["payout_method_id"].asLong)
        assertEquals("key-1", body["idempotency_key"].asString)
    }

    @Test
    fun claimError_exposesBackendCodeAndMessage() = runTest {
        server.reply("POST /api/orders/claim/", 409, error("This offer has no remaining quantity.", "offer_sold_out"))

        val result = repository.claim(1, 3, "key-1") as ApiResult.Error

        assertEquals(409, result.statusCode)
        assertEquals("This offer has no remaining quantity.", result.message)
        assertEquals(listOf("offer_sold_out"), result.fieldErrors["code"])
    }

    @Test
    fun payInIntent_isThePlatformAccount() = runTest {
        server.reply("GET /api/orders/7/pay-in-intent/", 200, ok(intentJson(7)))

        val intent = (repository.payInIntent(7) as ApiResult.Success).data

        assertEquals("255.00", intent.amount)
        assertEquals("platform@ybl", intent.payee_vpa)
        assertTrue(intent.upi_uri.startsWith("upi://pay?"))
    }

    @Test
    fun submitPayIn_sendsUtrKeyTokenAndBuyMethodWithoutPayerApp() = runTest {
        server.reply("POST /api/orders/7/pay-in/", 201, ok(payInJson(7, "012345678901")))

        val result = repository.submitPayIn(7, "012345678901", "key-2", "token-1", buyMethodId = 5)

        assertEquals("UNDER_REVIEW", (result as ApiResult.Success).data.status)
        val body = JsonParser.parseString(server.bodyOf(server.requests.single())).asJsonObject
        assertEquals(setOf("utr", "idempotency_key", "intent_token", "buy_method_id"), body.keySet())
        assertEquals(5L, body["buy_method_id"].asLong)
        assertFalse(body.has("payer_app"))
        assertFalse(body.has("amount"))
    }
}
