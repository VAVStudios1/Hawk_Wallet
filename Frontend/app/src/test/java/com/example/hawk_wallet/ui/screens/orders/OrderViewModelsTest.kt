package com.example.hawk_wallet.ui.screens.orders

import com.example.hawk_wallet.data.repository.BuyMethodRepository
import com.example.hawk_wallet.data.repository.FakeOrderServer
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.buyListJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.buyMethodJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.error
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.intentJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.offerJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.ok
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.orderJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.payInJson
import com.example.hawk_wallet.data.repository.FakePayoutMethodServer.Companion.listJson
import com.example.hawk_wallet.data.repository.FakePayoutMethodServer.Companion.methodJson
import com.example.hawk_wallet.data.repository.OrderRepository
import com.example.hawk_wallet.data.repository.PayoutMethodRepository
import com.example.hawk_wallet.data.repository.QrImageReader
import com.example.hawk_wallet.data.repository.QrUpload
import com.example.hawk_wallet.ui.model.OfferUiModel
import com.example.hawk_wallet.ui.model.OrderStatus
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val OFFERS = "GET /api/orders/offers/"
private const val ORDERS = "GET /api/orders/"
private const val CLAIM = "POST /api/orders/claim/"
private const val METHODS = "GET /api/payout-methods/"
private const val ORDER_7 = "GET /api/orders/7/"
private const val INTENT_7 = "GET /api/orders/7/pay-in-intent/"
private const val SUBMIT_7 = "POST /api/orders/7/pay-in/"
private const val BUY_METHODS = "GET /api/buy-methods/"

@OptIn(ExperimentalCoroutinesApi::class)
class OrderViewModelsTest {

    private val server = FakeOrderServer()
    private val orderRepository = OrderRepository(server.orderApi)
    private val buyMethodRepository = BuyMethodRepository(server.buyMethodApi)
    private val payoutMethodRepository = PayoutMethodRepository(
        server.payoutMethodApi,
        QrImageReader { QrUpload("qr.png", "image/png", byteArrayOf(1)) }
    )
    private var keys = 0
    private val newKey = { "key-${++keys}" }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun bodyOf(route: String, index: Int = 0) =
        JsonParser.parseString(server.bodyOf(server.requestsTo(route)[index])).asJsonObject

    private val offer = OfferUiModel(1, "255.00", "4.00", "10.00", "265.00", null)

    // --- Orders ------------------------------------------------------------

    @Test
    fun orders_refreshLoadsOffersAndOwnOrders() {
        server.reply(OFFERS, 200, ok("""{"offers":[${offerJson(1)}]}"""))
        server.reply(ORDERS, 200, ok("""{"orders":[${orderJson(7, "PAYMENT_UNDER_REVIEW")}]}"""))
        val viewModel = OrdersViewModel(orderRepository)

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.loaded)
        assertEquals("265.00", state.offers.single().payoutTarget)
        assertEquals(OrderStatus.PAYMENT_UNDER_REVIEW, state.orders.single().status)
    }

    @Test
    fun orders_oneListFailingKeepsTheOther() {
        server.reply(OFFERS, 401, """{"success":false,"message":"Given token not valid","errors":{"code":"token_not_valid"}}""")
        server.reply(ORDERS, 200, ok("""{"orders":[${orderJson(7)}]}"""))
        val viewModel = OrdersViewModel(orderRepository)

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertEquals(SESSION_EXPIRED, state.offersError)
        assertNull(state.ordersError)
        assertEquals(1, state.orders.size)
    }

    // --- Claim -------------------------------------------------------------

    private fun claimViewModel() = ClaimViewModel(orderRepository, payoutMethodRepository, newKey)

    @Test
    fun claim_showsActiveMethodsAndPreselectsDefault() {
        server.reply(
            METHODS, 200,
            listJson(
                methodJson(2, "GOOGLE_PAY", "Google Pay", "lucky@okaxis", default = true),
                methodJson(1, "PHONEPE", "PhonePe", "lucky@ybl", default = false),
                methodJson(5, "BHIM", "BHIM", "old@upi", default = false, active = false)
            )
        )
        val viewModel = claimViewModel()
        viewModel.start(offer)

        viewModel.loadMethods()

        val state = viewModel.uiState.value
        assertEquals(listOf(2L, 1L), state.methods.map { it.id })
        assertEquals(2L, state.selectedMethodId)
    }

    @Test
    fun claim_sendsTheChosenMethod() {
        server.reply(METHODS, 200, listJson(methodJson(2, "GOOGLE_PAY", "Google Pay", "a@okaxis", default = true), methodJson(1, "PHONEPE", "PhonePe", "b@ybl", default = false)))
        server.reply(CLAIM, 201, ok(orderJson(7)))
        val viewModel = claimViewModel()
        viewModel.start(offer)
        viewModel.loadMethods()

        viewModel.selectMethod(1)
        viewModel.claim()

        val body = bodyOf(CLAIM)
        assertEquals(1L, body["offer_id"].asLong)
        assertEquals(1L, body["payout_method_id"].asLong)
        assertEquals(7L, viewModel.uiState.value.claimedOrderId)
    }

    @Test
    fun claim_retryAfterLostResponseReusesTheIdempotencyKey() {
        server.reply(METHODS, 200, listJson(methodJson(2, "GOOGLE_PAY", "Google Pay", "a@okaxis", default = true)))
        server.reply(CLAIM, 500, """not json""")
        server.reply(CLAIM, 201, ok(orderJson(7)))
        val viewModel = claimViewModel()
        viewModel.start(offer)
        viewModel.loadMethods()

        viewModel.claim()
        assertNotNull(viewModel.uiState.value.claimError)
        viewModel.claim()

        assertEquals(bodyOf(CLAIM, 0)["idempotency_key"].asString, bodyOf(CLAIM, 1)["idempotency_key"].asString)
        assertEquals(7L, viewModel.uiState.value.claimedOrderId)
    }

    @Test
    fun claim_newOfferGetsANewKey() {
        server.reply(METHODS, 200, listJson(methodJson(2, "GOOGLE_PAY", "Google Pay", "a@okaxis", default = true)))
        server.reply(METHODS, 200, listJson(methodJson(2, "GOOGLE_PAY", "Google Pay", "a@okaxis", default = true)))
        server.reply(CLAIM, 201, ok(orderJson(7)))
        server.reply(CLAIM, 201, ok(orderJson(8)))
        val viewModel = claimViewModel()

        viewModel.start(offer)
        viewModel.loadMethods()
        viewModel.claim()
        viewModel.claimHandled()
        viewModel.start(offer.copy(id = 2))
        viewModel.loadMethods()
        viewModel.claim()

        val first = bodyOf(CLAIM, 0)["idempotency_key"].asString
        val second = bodyOf(CLAIM, 1)["idempotency_key"].asString
        assertTrue(first != second)
    }

    @Test
    fun claim_soldOutMarksOfferUnavailableAndBlocksRetry() {
        server.reply(METHODS, 200, listJson(methodJson(2, "GOOGLE_PAY", "Google Pay", "a@okaxis", default = true)))
        server.reply(CLAIM, 409, error("This offer has no remaining quantity.", "offer_sold_out"))
        val viewModel = claimViewModel()
        viewModel.start(offer)
        viewModel.loadMethods()

        viewModel.claim()
        viewModel.claim()

        val state = viewModel.uiState.value
        assertTrue(state.offerUnavailable)
        assertEquals("This offer has no remaining quantity.", state.claimError)
        assertEquals(1, server.requestsTo(CLAIM).size)
    }

    @Test
    fun claim_methodNoLongerUsableReloadsMethods() {
        server.reply(METHODS, 200, listJson(methodJson(2, "GOOGLE_PAY", "Google Pay", "a@okaxis", default = true)))
        server.reply(CLAIM, 400, error("Choose one of your active payout methods.", "payout_method_unavailable"))
        server.reply(METHODS, 200, listJson(methodJson(1, "PHONEPE", "PhonePe", "b@ybl", default = true)))
        val viewModel = claimViewModel()
        viewModel.start(offer)
        viewModel.loadMethods()

        viewModel.claim()

        val state = viewModel.uiState.value
        assertEquals("Choose one of your active payout methods.", state.claimError)
        assertEquals(listOf(1L), state.methods.map { it.id })
        assertEquals(1L, state.selectedMethodId)
        assertFalse(state.offerUnavailable)
    }

    @Test
    fun claim_withoutMethodsShowsEmptyList() {
        server.reply(METHODS, 200, listJson())
        val viewModel = claimViewModel()
        viewModel.start(offer)

        viewModel.loadMethods()
        viewModel.claim()

        assertTrue(viewModel.uiState.value.methods.isEmpty())
        assertNull(viewModel.uiState.value.selectedMethodId)
        assertTrue(server.requestsTo(CLAIM).isEmpty())
    }

    // --- Pay-In ------------------------------------------------------------

    /** The member's BUY account used by default in Pay-In tests (id 5, default). */
    private val defaultBuyMethod = buyMethodJson(5, "GOOGLE_PAY", "Google Pay", "me@okaxis", default = true)

    private fun loadedPayIn(buyMethods: String = buyListJson(defaultBuyMethod)): PayInViewModel {
        server.reply(ORDER_7, 200, ok(orderJson(7)))
        server.reply(INTENT_7, 200, ok(intentJson(7)))
        server.reply(BUY_METHODS, 200, buyMethods)
        return PayInViewModel(orderRepository, buyMethodRepository, 7, newKey).also { it.load() }
    }

    @Test
    fun payIn_showsPlatformAccountAndOrderPayoutMethodSeparately() {
        val state = loadedPayIn().uiState.value

        assertEquals("platform@ybl", state.intent?.payeeVpa)
        assertEquals("255.00", state.intent?.amount)
        assertEquals("member@ybl", state.order?.payoutMethod?.upiId)
        assertEquals("265.00", state.order?.payoutTarget)
    }

    @Test
    fun payIn_orderNotPayableShowsBackendMessage() {
        server.reply(ORDER_7, 200, ok(orderJson(7, "PAYMENT_UNDER_REVIEW")))
        server.reply(INTENT_7, 409, error("This order is not awaiting payment.", "order_not_payable"))
        server.reply(BUY_METHODS, 200, buyListJson(defaultBuyMethod))
        val viewModel = PayInViewModel(orderRepository, buyMethodRepository, 7, newKey)

        viewModel.load()

        assertEquals("This order is not awaiting payment.", viewModel.uiState.value.notPayableMessage)
        assertNull(viewModel.uiState.value.loadError)
    }

    @Test
    fun payIn_utrInputKeepsAsciiDigitsOnlyUpTo12() {
        val viewModel = loadedPayIn()

        viewModel.onUtrChange(" 0123-4567 89012345")

        assertEquals("012345678901", viewModel.uiState.value.utr)
    }

    @Test
    fun payIn_submitSendsUtrTokenKeyAndBuyMethod() {
        server.reply(SUBMIT_7, 201, ok(payInJson(7, "012345678901")))
        val viewModel = loadedPayIn()

        viewModel.onUtrChange("012345678901")
        viewModel.submit()

        val body = bodyOf(SUBMIT_7)
        assertEquals("012345678901", body["utr"].asString)
        assertEquals("token-1", body["intent_token"].asString)
        assertEquals("key-1", body["idempotency_key"].asString)
        assertEquals(5L, body["buy_method_id"].asLong)
        assertTrue(viewModel.uiState.value.submitted)
    }

    @Test
    fun payIn_buyMethodsAreActiveOnlyAndDefaultPreselected() {
        val viewModel = loadedPayIn(
            buyListJson(
                buyMethodJson(6, "BHIM", "BHIM", "old@upi", default = false, active = false),
                buyMethodJson(5, "PHONEPE", "PhonePe", "me@ybl", default = true),
                buyMethodJson(4, "MOBIKWIK", "MobiKwik", "me@ikwik", default = false)
            )
        )

        val state = viewModel.uiState.value
        assertEquals(listOf(5L, 4L), state.buyMethods.map { it.id })
        assertEquals(5L, state.selectedBuyMethodId)
        // The order's SELL payout method is never offered as the account paid from.
        assertFalse(state.buyMethods.any { it.upiId == "member@ybl" })
    }

    @Test
    fun payIn_selectingAnotherBuyMethodIsSent() {
        server.reply(SUBMIT_7, 201, ok(payInJson(7, "012345678901")))
        val viewModel = loadedPayIn(
            buyListJson(
                buyMethodJson(5, "PHONEPE", "PhonePe", "me@ybl", default = true),
                buyMethodJson(4, "MOBIKWIK", "MobiKwik", "me@ikwik", default = false)
            )
        )

        viewModel.selectBuyMethod(4)
        viewModel.selectBuyMethod(999) // not one of the member's accounts: ignored
        viewModel.onUtrChange("012345678901")
        viewModel.submit()

        assertEquals(4L, bodyOf(SUBMIT_7)["buy_method_id"].asLong)
    }

    @Test
    fun payIn_withoutBuyMethodsNothingIsSent() {
        val viewModel = loadedPayIn(buyListJson())

        viewModel.onUtrChange("012345678901")
        viewModel.submit()

        val state = viewModel.uiState.value
        assertTrue(state.buyMethodsLoaded)
        assertTrue(state.buyMethods.isEmpty())
        assertNull(state.selectedBuyMethodId)
        assertEquals("Choose the UPI account you paid from.", state.buyMethodError)
        assertTrue(server.requestsTo(SUBMIT_7).isEmpty())
    }

    @Test
    fun payIn_unavailableBuyMethodShownUnderChooserAndReloaded() {
        server.reply(SUBMIT_7, 400, error("Choose one of your active UPI accounts you paid from.", "buy_method_unavailable"))
        val viewModel = loadedPayIn()
        server.reply(BUY_METHODS, 200, buyListJson(buyMethodJson(8, "BHIM", "BHIM", "new@upi", default = true)))

        viewModel.onUtrChange("012345678901")
        viewModel.submit()

        val state = viewModel.uiState.value
        assertEquals("Choose one of your active UPI accounts you paid from.", state.buyMethodError)
        assertFalse(state.submitted)
        assertEquals(8L, state.selectedBuyMethodId)
        assertEquals(2, server.requestsTo(BUY_METHODS).size)
    }

    @Test
    fun payIn_buyMethodsLoadFailureKeepsPaymentDetails() {
        server.reply(ORDER_7, 200, ok(orderJson(7)))
        server.reply(INTENT_7, 200, ok(intentJson(7)))
        server.reply(BUY_METHODS, 500, error("Unable to load.", "error"))
        val viewModel = PayInViewModel(orderRepository, buyMethodRepository, 7, newKey).also { it.load() }

        val state = viewModel.uiState.value
        assertEquals("platform@ybl", state.intent?.payeeVpa)
        assertNull(state.loadError)
        assertNotNull(state.buyMethodsError)
    }

    @Test
    fun payIn_shortUtrIsNotSent() {
        val viewModel = loadedPayIn()

        viewModel.onUtrChange("12345")
        viewModel.submit()

        assertNotNull(viewModel.uiState.value.utrError)
        assertTrue(server.requestsTo(SUBMIT_7).isEmpty())
    }

    @Test
    fun payIn_duplicateUtrShownUnderField_retryReusesKey() {
        server.reply(SUBMIT_7, 409, error("This UTR has already been submitted.", "utr_already_submitted"))
        server.reply(SUBMIT_7, 201, ok(payInJson(7, "112345678901")))
        val viewModel = loadedPayIn()

        viewModel.onUtrChange("012345678901")
        viewModel.submit()
        assertEquals("This UTR has already been submitted.", viewModel.uiState.value.utrError)
        assertFalse(viewModel.uiState.value.submitted)

        viewModel.onUtrChange("112345678901")
        viewModel.submit()

        assertEquals(bodyOf(SUBMIT_7, 0)["idempotency_key"].asString, bodyOf(SUBMIT_7, 1)["idempotency_key"].asString)
        assertTrue(viewModel.uiState.value.submitted)
    }

    @Test
    fun payIn_invalidIntentReloadsReceivingDetails() {
        server.reply(SUBMIT_7, 400, error("This payment request is not valid for this order. Please start the payment again.", "invalid_pay_in_intent"))
        val viewModel = loadedPayIn()
        server.reply(ORDER_7, 200, ok(orderJson(7)))
        server.reply(INTENT_7, 200, ok(intentJson(7, token = "token-2")))
        server.reply(BUY_METHODS, 200, buyListJson(defaultBuyMethod))

        viewModel.onUtrChange("012345678901")
        viewModel.submit()

        val state = viewModel.uiState.value
        assertNotNull(state.submitError)
        assertEquals("token-2", state.intent?.intentToken)
        assertEquals(2, server.requestsTo(INTENT_7).size)
    }

    @Test
    fun payIn_sessionExpired() {
        server.reply(SUBMIT_7, 401, """{"success":false,"message":"Given token not valid","errors":{"code":"token_not_valid"}}""")
        val viewModel = loadedPayIn()

        viewModel.onUtrChange("012345678901")
        viewModel.submit()

        assertEquals(SESSION_EXPIRED, viewModel.uiState.value.submitError)
    }

    // --- Order status --------------------------------------------------------

    @Test
    fun orderStatus_showsRejectedPayIn() {
        server.reply(
            ORDER_7, 200,
            ok(
                orderJson(
                    7,
                    latestPayIn = """{"id":9,"status":"REJECTED","amount":"255.00","utr":"012345678901",
                        "rejection_reason":"Wrong UTR","submitted_at":"2026-09-26T10:05:00Z","updated_at":"2026-09-26T11:00:00Z"}"""
                )
            )
        )
        val viewModel = OrderStatusViewModel(orderRepository, 7)

        viewModel.refresh()

        val order = viewModel.uiState.value.order!!
        assertEquals(OrderStatus.PAYMENT_REQUIRED, order.status)
        assertEquals("Wrong UTR", order.latestPayIn?.rejectionReason)
    }

    @Test
    fun orderStatus_otherMembersOrderLooksMissing() {
        server.reply(ORDER_7, 404, error("Order not found.", "order_not_found"))
        val viewModel = OrderStatusViewModel(orderRepository, 7)

        viewModel.refresh()

        assertNull(viewModel.uiState.value.order)
        assertEquals("Order not found.", viewModel.uiState.value.error)
    }
}
