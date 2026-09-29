package com.example.hawk_wallet.ui.screens.admin

import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.FakeOrderServer
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.error
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.ok
import com.example.hawk_wallet.data.repository.QrImageReader
import com.example.hawk_wallet.data.repository.QrUpload
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.model.PayoutStatus
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val ADMIN_ORDERS = "GET /api/admin/orders/"
private const val ADMIN_ORDER_7 = "GET /api/admin/orders/7/"
private const val CONFIRM_9 = "POST /api/admin/pay-ins/9/confirm/"
private const val REJECT_9 = "POST /api/admin/pay-ins/9/reject/"
private const val CREATE_OFFER = "POST /api/admin/offers/"
private const val CREATE_PAYOUT_7 = "POST /api/admin/orders/7/payouts/"
private const val PAID_21 = "POST /api/admin/payouts/21/paid/"
private const val CANCEL_21 = "POST /api/admin/payouts/21/cancel/"
private const val QR_21 = "GET /api/admin/payouts/21/payout-method/qr/"

private const val UNDER_REVIEW_FIELDS =
    ""","payout_method":{"id":3,"provider_code":"PHONEPE","provider_name":"PhonePe","label":"","upi_id":"asha@ybl"},
       "latest_pay_in":{"id":9,"status":"UNDER_REVIEW","amount":"255.00","utr":"012345678901",
       "rejection_reason":"",
       "buy_method":{"id":5,"provider_code":"GOOGLE_PAY","provider_name":"Google Pay","label":"","upi_id":"asha@okaxis"},
       "submitted_at":"2026-09-26T10:05:00Z",
       "reviewed_at":null,"updated_at":"2026-09-26T10:05:00Z"}"""

private fun reviewedJson(status: String, orderStatus: String, reason: String = "") =
    """{"id":9,"order":7,"status":"$status","order_status":"$orderStatus",
       "rejection_reason":"$reason","reviewed_at":"2026-09-26T11:00:00Z"}"""

private const val OFFER_JSON =
    """{"id":4,"currency":"INR","order_amount":"255.00","income_rate":"4.00","income_amount":"10.00",
       "payout_target":"265.00","quantity_available":10,"is_active":true,"pay_in_upi_id":"collect@ybl",
       "created_at":"2026-09-26T11:00:00Z"}"""

private fun adminOrderJson(id: Long, status: String = "PAYMENT_UNDER_REVIEW", detail: String = "") =
    """{"id":$id,"reference":"HW-000000000$id","status":"$status","currency":"INR",
       "member":{"id":12,"name":"Asha Kumar"},
       "order_amount":"255.00","income_rate":"4.00","income_amount":"10.00","payout_target":"265.00",
       "payout_committed_total":"0.00","payout_paid_total":"0.00",
       "claimed_at":"2026-09-26T10:00:00Z","expires_at":null,"activated_at":null,"completed_at":null,
       "created_at":"2026-09-26T10:00:00Z","updated_at":"2026-09-26T10:00:00Z"$detail}"""

private const val DETAIL_FIELDS =
    ""","payout_method":{"id":3,"provider_code":"PHONEPE","provider_name":"PhonePe","label":"Main","upi_id":"asha@ybl"},
       "latest_pay_in":{"id":9,"status":"REJECTED","amount":"255.00","utr":"012345678901",
       "rejection_reason":"UTR not found.","submitted_at":"2026-09-26T10:05:00Z",
       "reviewed_at":"2026-09-26T11:00:00Z","updated_at":"2026-09-26T11:00:00Z"}"""

@OptIn(ExperimentalCoroutinesApi::class)
class AdminOrderViewModelsTest {

    private val server = FakeOrderServer()
    private val repository = AdminOrderRepository(
        server.adminOrderApi,
        QrImageReader { QrUpload("qr.png", "image/png", byteArrayOf(1)) }
    )
    private var keys = 0
    private val newKey = { "key-${++keys}" }

    /** Text parts of a multipart request, by form field name; file parts map to their filename. */
    private fun partsOf(route: String, index: Int = 0): Map<String, String> =
        (server.requestsTo(route)[index].body as MultipartBody).parts.associate { part ->
            val disposition = part.headers!!["Content-Disposition"]!!
            val name = Regex("name=\"([^\"]+)\"").find(disposition)!!.groupValues[1]
            val file = Regex("filename=\"([^\"]+)\"").find(disposition)?.groupValues?.get(1)
            name to (file ?: Buffer().also { part.body.writeTo(it) }.readUtf8())
        }

    private fun bodyOf(route: String, index: Int = 0) =
        JsonParser.parseString(server.bodyOf(server.requestsTo(route)[index])).asJsonObject

    private fun loadedUnderReview(): AdminOrderDetailsViewModel {
        server.reply(ADMIN_ORDER_7, 200, ok(adminOrderJson(7, "PAYMENT_UNDER_REVIEW", UNDER_REVIEW_FIELDS)))
        return AdminOrderDetailsViewModel(repository, 7, newKey).also { it.refresh() }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun orders_refreshLoadsBackendValuesAsIs() {
        server.reply(ADMIN_ORDERS, 200, ok("""{"orders":[${adminOrderJson(7)},${adminOrderJson(8, "COMPLETED")}]}"""))
        val viewModel = AdminOrdersViewModel(repository)

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.loaded)
        assertNull(state.error)
        assertEquals(listOf(7L, 8L), state.orders.map { it.id })
        val first = state.orders.first()
        assertEquals("Asha Kumar", first.memberName)
        assertEquals("265.00", first.payoutTarget)
        assertEquals("10.00", first.incomeAmount)
        assertEquals(OrderStatus.PAYMENT_UNDER_REVIEW, first.status)
        assertNull(first.payoutMethod)
    }

    @Test
    fun orders_emptyList() {
        server.reply(ADMIN_ORDERS, 200, ok("""{"orders":[]}"""))
        val viewModel = AdminOrdersViewModel(repository)

        viewModel.refresh()

        assertTrue(viewModel.uiState.value.loaded)
        assertTrue(viewModel.uiState.value.orders.isEmpty())
    }

    @Test
    fun orders_forbiddenShowsBackendMessage() {
        server.reply(ADMIN_ORDERS, 403, error("Only active admins can view all orders.", "permission_denied"))
        val viewModel = AdminOrdersViewModel(repository)

        viewModel.refresh()

        assertEquals("Only active admins can view all orders.", viewModel.uiState.value.error)
    }

    @Test
    fun orders_failedRefreshKeepsLastList() {
        server.reply(ADMIN_ORDERS, 200, ok("""{"orders":[${adminOrderJson(7)}]}"""))
        server.reply(ADMIN_ORDERS, 401, error("Token expired.", "token_not_valid"))
        val viewModel = AdminOrdersViewModel(repository)

        viewModel.refresh()
        viewModel.refresh()

        val state = viewModel.uiState.value
        assertEquals(1, state.orders.size)
        assertEquals("Your session has expired. Please log in again.", state.error)
    }

    @Test
    fun details_loadsPayInAndPayoutMethod() {
        server.reply(ADMIN_ORDER_7, 200, ok(adminOrderJson(7, "PAYMENT_REQUIRED", DETAIL_FIELDS)))
        val viewModel = AdminOrderDetailsViewModel(repository, 7)

        viewModel.refresh()

        val order = viewModel.uiState.value.order!!
        assertEquals("asha@ybl", order.payoutMethod?.upiId)
        assertEquals("Main", order.payoutMethodLabel)
        val payIn = order.latestPayIn!!
        assertEquals(PayInStatus.REJECTED, payIn.status)
        assertEquals("012345678901", payIn.utr)
        assertEquals("UTR not found.", payIn.rejectionReason)
        assertEquals("2026-09-26T11:00:00Z", payIn.reviewedAt)
        // An older pay-in without a recorded BUY account.
        assertNull(payIn.paidFrom)
    }

    @Test
    fun details_showBuyAndSellSidesSeparately() {
        val order = loadedUnderReview().uiState.value.order!!

        // SELL: where the platform pays the member.
        assertEquals("asha@ybl", order.payoutMethod?.upiId)
        // BUY: the member's own account the pay-in came from.
        val paidFrom = order.latestPayIn!!.paidFrom!!
        assertEquals("GOOGLE_PAY", paidFrom.providerCode)
        assertEquals("Google Pay", paidFrom.providerName)
        assertEquals("asha@okaxis", paidFrom.upiId)
    }

    @Test
    fun details_notFoundShowsError() {
        server.reply(ADMIN_ORDER_7, 404, error("Order not found.", "order_not_found"))
        val viewModel = AdminOrderDetailsViewModel(repository, 7)

        viewModel.refresh()

        assertNull(viewModel.uiState.value.order)
        assertEquals("Order not found.", viewModel.uiState.value.error)
    }

    // --- Pay-in review -------------------------------------------------------

    @Test
    fun review_onlyOfferedForPayInUnderReview() {
        server.reply(ADMIN_ORDER_7, 200, ok(adminOrderJson(7, "PAYMENT_REQUIRED", DETAIL_FIELDS)))
        val viewModel = AdminOrderDetailsViewModel(repository, 7, newKey)
        viewModel.refresh()

        assertFalse(viewModel.uiState.value.canReview)
        viewModel.openConfirm()
        assertNull(viewModel.uiState.value.reviewDialog)

        assertTrue(loadedUnderReview().uiState.value.canReview)
    }

    @Test
    fun confirm_sendsKeyThenReloadsOrder() {
        val viewModel = loadedUnderReview()
        server.reply(CONFIRM_9, 200, ok(reviewedJson("CONFIRMED", "ACTIVE")))
        server.reply(ADMIN_ORDER_7, 200, ok(adminOrderJson(7, "ACTIVE", DETAIL_FIELDS)))

        viewModel.openConfirm()
        viewModel.confirm()

        val state = viewModel.uiState.value
        assertEquals(1, server.requestsTo(CONFIRM_9).size)
        assertTrue(bodyOf(CONFIRM_9).get("idempotency_key").asString.isNotBlank())
        assertNull(state.reviewDialog)
        assertEquals("Pay-in confirmed.", state.message)
        assertEquals(OrderStatus.ACTIVE, state.order?.status)
        assertEquals(2, server.requestsTo(ADMIN_ORDER_7).size)
    }

    @Test
    fun confirm_retryAfterFailureReusesSameKey() {
        val viewModel = loadedUnderReview()
        server.reply(CONFIRM_9, 500, error("Server error.", "error"))
        server.reply(CONFIRM_9, 200, ok(reviewedJson("CONFIRMED", "ACTIVE")))
        server.reply(ADMIN_ORDER_7, 200, ok(adminOrderJson(7, "ACTIVE", DETAIL_FIELDS)))

        viewModel.openConfirm()
        viewModel.confirm()
        assertEquals("Server error.", viewModel.uiState.value.reviewError)
        assertEquals(PayInReviewDialog.CONFIRM, viewModel.uiState.value.reviewDialog)
        viewModel.confirm()

        assertEquals(bodyOf(CONFIRM_9, 0).get("idempotency_key"), bodyOf(CONFIRM_9, 1).get("idempotency_key"))
        assertNull(viewModel.uiState.value.reviewDialog)
    }

    @Test
    fun reject_requiresReasonLocally() {
        val viewModel = loadedUnderReview()

        viewModel.openReject()
        viewModel.onRejectionReasonChange("   ")
        viewModel.reject()

        assertNotNull(viewModel.uiState.value.rejectionReasonError)
        assertTrue(server.requestsTo(REJECT_9).isEmpty())
    }

    @Test
    fun reject_sendsReasonAsTyped() {
        val viewModel = loadedUnderReview()
        server.reply(REJECT_9, 200, ok(reviewedJson("REJECTED", "PAYMENT_REQUIRED", "UTR not found.")))
        server.reply(ADMIN_ORDER_7, 200, ok(adminOrderJson(7, "PAYMENT_REQUIRED", DETAIL_FIELDS)))

        viewModel.openReject()
        viewModel.onRejectionReasonChange("UTR not found.")
        viewModel.reject()

        assertEquals("UTR not found.", bodyOf(REJECT_9).get("rejection_reason").asString)
        assertEquals("Pay-in rejected.", viewModel.uiState.value.message)
        assertEquals(PayInStatus.REJECTED, viewModel.uiState.value.order?.latestPayIn?.status)
    }

    @Test
    fun reject_changedReasonUsesNewKey() {
        val viewModel = loadedUnderReview()
        server.reply(REJECT_9, 500, error("Server error.", "error"))
        server.reply(REJECT_9, 500, error("Server error.", "error"))

        viewModel.openReject()
        viewModel.onRejectionReasonChange("First reason")
        viewModel.reject()
        viewModel.onRejectionReasonChange("Second reason")
        viewModel.reject()

        assertNotEquals(bodyOf(REJECT_9, 0).get("idempotency_key"), bodyOf(REJECT_9, 1).get("idempotency_key"))
    }

    @Test
    fun review_alreadyReviewedClosesDialogAndReloads() {
        val viewModel = loadedUnderReview()
        server.reply(CONFIRM_9, 409, error("This pay-in has already been confirmed or rejected.", "payin_already_reviewed"))
        server.reply(ADMIN_ORDER_7, 200, ok(adminOrderJson(7, "ACTIVE", DETAIL_FIELDS)))

        viewModel.openConfirm()
        viewModel.confirm()

        val state = viewModel.uiState.value
        assertNull(state.reviewDialog)
        assertEquals("This pay-in has already been confirmed or rejected.", state.message)
        assertEquals(OrderStatus.ACTIVE, state.order?.status)
    }

    // --- Create offer ----------------------------------------------------------

    private fun filledOfferForm() = CreateOfferViewModel(repository, newKey).apply {
        onOrderAmountChange("255")
        onIncomeRateChange("4")
        onQuantityChange("10")
        onActiveChange(true)
        onPayInQrSelected("content://qr")
    }

    @Test
    fun createOffer_sendsOnlyTypedValuesAndShowsServerPreview() {
        server.reply(CREATE_OFFER, 201, ok(OFFER_JSON))
        val viewModel = filledOfferForm()

        viewModel.submit()

        val parts = partsOf(CREATE_OFFER)
        assertEquals(
            setOf("order_amount", "income_rate", "quantity_available", "is_active", "idempotency_key", "pay_in_qr"),
            parts.keys
        )
        assertEquals("255", parts["order_amount"])
        assertEquals("4", parts["income_rate"])
        assertEquals("10", parts["quantity_available"])
        assertEquals("true", parts["is_active"])
        assertEquals("qr.png", parts["pay_in_qr"])
        val created = viewModel.uiState.value.created!!
        assertEquals("10.00", created.incomeAmount)
        assertEquals("265.00", created.payoutTarget)
        assertEquals("collect@ybl", created.payInUpiId)
    }

    @Test
    fun createOffer_blankQuantityMeansNoLimit() {
        server.reply(CREATE_OFFER, 201, ok(OFFER_JSON))
        val viewModel = filledOfferForm().apply { onQuantityChange("") }

        viewModel.submit()

        assertFalse(partsOf(CREATE_OFFER).containsKey("quantity_available"))
    }

    @Test
    fun createOffer_invalidFormNeverCallsBackend() {
        val viewModel = CreateOfferViewModel(repository, newKey).apply {
            onOrderAmountChange("255.50")
            onIncomeRateChange("101")
            onQuantityChange("0")
        }

        viewModel.submit()

        val state = viewModel.uiState.value
        assertNotNull(state.orderAmountError)
        assertNotNull(state.incomeRateError)
        assertNotNull(state.quantityError)
        assertNotNull(state.payInQrError)
        assertTrue(server.requestsTo(CREATE_OFFER).isEmpty())
    }

    @Test
    fun createOffer_retrySameValuesReusesKey_editStartsNewKey() {
        server.reply(CREATE_OFFER, 500, error("Server error.", "error"))
        server.reply(CREATE_OFFER, 500, error("Server error.", "error"))
        server.reply(CREATE_OFFER, 201, ok(OFFER_JSON))
        val viewModel = filledOfferForm()

        viewModel.submit()
        assertEquals("Server error.", viewModel.uiState.value.submitError)
        viewModel.submit()
        viewModel.onIncomeRateChange("5")
        viewModel.submit()

        val sent = (0..2).map { partsOf(CREATE_OFFER, it).getValue("idempotency_key") }
        assertEquals(sent[0], sent[1])
        assertNotEquals(sent[1], sent[2])
        assertNotNull(viewModel.uiState.value.created)
    }

    @Test
    fun createOffer_backendErrorShown() {
        server.reply(CREATE_OFFER, 403, error("Only active admins can create offers.", "permission_denied"))
        val viewModel = filledOfferForm()

        viewModel.submit()

        assertEquals("Only active admins can create offers.", viewModel.uiState.value.submitError)
        assertNull(viewModel.uiState.value.created)
    }

    // --- Payouts (SELL) -------------------------------------------------------

    private fun payoutRowJson(id: Long, amount: String, status: String, utr: String = "", reason: String = "") =
        """{"id":$id,"amount":"$amount","status":"$status","utr":"$utr","created_by":1,
           "paid_by":${if (status == "PAID") "1" else "null"},"created_at":"2026-09-26T12:00:00Z",
           "paid_at":${if (status == "PAID") "\"2026-09-26T12:30:00Z\"" else "null"},
           "provider_code":"PHONEPE","provider_name":"PhonePe","upi_id":"asha@ybl",
           "cancellation_reason":"$reason"}"""

    private fun payoutFields(remaining: String, vararg rows: String) =
        """$DETAIL_FIELDS,"remaining_to_commit":"$remaining","payouts":[${rows.joinToString(",")}]"""

    private fun payoutJson(id: Long, status: String, orderStatus: String = "PAYOUT_IN_PROGRESS") =
        """{"id":$id,"order":7,"order_reference":"HW-0000000007","order_status":"$orderStatus",
           "order_payout_committed_total":"150.00","order_payout_paid_total":"0.00","amount":"150.00",
           "status":"$status","utr":"","created_by":1,"paid_by":null,"created_at":"2026-09-26T12:00:00Z",
           "paid_at":null,"updated_at":"2026-09-26T12:00:00Z"}"""

    private fun loadedWithPayouts(
        remaining: String = "115.00",
        status: String = "PAYOUT_IN_PROGRESS",
        rows: List<String> = listOf(payoutRowJson(21, "150.00", "PENDING"))
    ): AdminOrderDetailsViewModel {
        server.reply(ADMIN_ORDER_7, 200, ok(adminOrderJson(7, status, payoutFields(remaining, *rows.toTypedArray()))))
        return AdminOrderDetailsViewModel(repository, 7, newKey).also { it.refresh() }
    }

    private fun reloadsWith(remaining: String, vararg rows: String) =
        server.reply(ADMIN_ORDER_7, 200, ok(adminOrderJson(7, "PAYOUT_IN_PROGRESS", payoutFields(remaining, *rows))))

    @Test
    fun details_loadsPayoutHistoryAndServerRemaining() {
        val viewModel = loadedWithPayouts(
            "15.00", "PAYOUT_IN_PROGRESS",
            listOf(
                payoutRowJson(20, "100.00", "PAID", utr = "012345678901"),
                payoutRowJson(21, "150.00", "PENDING"),
                payoutRowJson(22, "65.00", "CANCELLED", reason = "Wrong amount")
            )
        )

        val order = viewModel.uiState.value.order!!
        assertEquals("15.00", order.remainingToCommit)
        assertEquals(listOf(20L, 21L, 22L), order.payouts.map { it.id })
        val paid = order.payouts[0]
        assertEquals(PayoutStatus.PAID, paid.status)
        assertEquals("100.00", paid.amount)
        assertEquals("012345678901", paid.utr)
        assertEquals(1L, paid.paidBy)
        assertEquals("asha@ybl", paid.destination.upiId)
        assertEquals("PHONEPE", paid.destination.providerCode)
        assertEquals(PayoutStatus.PENDING, order.payouts[1].status)
        assertEquals("Wrong amount", order.payouts[2].cancellationReason)
    }

    @Test
    fun createPayout_offeredOnlyWhenServerSaysSomethingRemains() {
        assertTrue(loadedWithPayouts("115.00").uiState.value.canCreatePayout)

        val fullyCommitted = loadedWithPayouts("0.00")
        assertFalse(fullyCommitted.uiState.value.canCreatePayout)
        fullyCommitted.openCreatePayout()
        assertNull(fullyCommitted.uiState.value.payoutDialog)

        // Unpaid order: nothing is committed, but the order is not payable yet.
        assertFalse(loadedWithPayouts("265.00", "PAYMENT_REQUIRED").uiState.value.canCreatePayout)
    }

    @Test
    fun createPayout_sendsAmountExactlyAsTypedThenReloads() {
        val viewModel = loadedWithPayouts()
        server.reply(CREATE_PAYOUT_7, 201, ok(payoutJson(23, "PENDING")))
        reloadsWith("15.00", payoutRowJson(21, "150.00", "PENDING"), payoutRowJson(23, "100.00", "PENDING"))

        viewModel.openCreatePayout()
        viewModel.onPayoutAmountChange("100.00")
        viewModel.createPayout()

        val body = bodyOf(CREATE_PAYOUT_7)
        assertEquals(setOf("amount", "idempotency_key"), body.keySet())
        assertEquals("100.00", body.get("amount").asString)
        assertTrue(body.get("idempotency_key").asString.isNotBlank())
        val state = viewModel.uiState.value
        assertNull(state.payoutDialog)
        assertEquals("Payout created.", state.message)
        assertEquals("15.00", state.order?.remainingToCommit)
        assertEquals(2, state.order?.payouts?.size)
    }

    @Test
    fun createPayout_invalidAmountNeverCallsBackend() {
        val viewModel = loadedWithPayouts()
        viewModel.openCreatePayout()

        for (amount in listOf("", "  ", "abc", "0", "10.50", "-5", "1,000")) {
            viewModel.onPayoutAmountChange(amount)
            viewModel.createPayout()
            assertNotNull("accepted '$amount'", viewModel.uiState.value.payoutAmountError)
        }
        assertTrue(server.requestsTo(CREATE_PAYOUT_7).isEmpty())
    }

    @Test
    fun createPayout_retrySameAmountReusesKey_editStartsNewKey() {
        val viewModel = loadedWithPayouts()
        server.reply(CREATE_PAYOUT_7, 500, error("Server error.", "error"))
        server.reply(CREATE_PAYOUT_7, 500, error("Server error.", "error"))
        server.reply(CREATE_PAYOUT_7, 500, error("Server error.", "error"))

        viewModel.openCreatePayout()
        viewModel.onPayoutAmountChange("100")
        viewModel.createPayout()
        assertEquals("Server error.", viewModel.uiState.value.payoutError)
        assertEquals(PayoutDialog.Create, viewModel.uiState.value.payoutDialog)
        viewModel.createPayout()
        viewModel.onPayoutAmountChange("90")
        viewModel.createPayout()

        val keys = (0..2).map { bodyOf(CREATE_PAYOUT_7, it).get("idempotency_key").asString }
        assertEquals(keys[0], keys[1])
        assertNotEquals(keys[1], keys[2])
    }

    @Test
    fun createPayout_exceedsRemainingShownInDialogAndReloads() {
        val viewModel = loadedWithPayouts()
        server.reply(
            CREATE_PAYOUT_7, 409,
            error("This payout would exceed the amount still owed on the order.", "payout_exceeds_remaining")
        )
        reloadsWith("15.00", payoutRowJson(21, "150.00", "PENDING"))

        viewModel.openCreatePayout()
        viewModel.onPayoutAmountChange("116")
        viewModel.createPayout()

        val state = viewModel.uiState.value
        assertEquals(PayoutDialog.Create, state.payoutDialog)
        assertEquals("This payout would exceed the amount still owed on the order.", state.payoutError)
        assertEquals("15.00", state.order?.remainingToCommit)
    }

    @Test
    fun markPaid_requiresExactlyTwelveDigits() {
        val viewModel = loadedWithPayouts()
        viewModel.openMarkPaid(21)

        for (utr in listOf("", "12345678901", "1234567890123", "1234 5678 9012", "12345678901a", " 123456789012")) {
            viewModel.onPayoutUtrChange(utr)
            viewModel.markPayoutPaid()
            assertNotNull("accepted '$utr'", viewModel.uiState.value.payoutUtrError)
        }
        assertTrue(server.requestsTo(PAID_21).isEmpty())
    }

    @Test
    fun markPaid_sendsUtrAsTypedThenReloads() {
        val viewModel = loadedWithPayouts()
        server.reply(PAID_21, 200, ok(payoutJson(21, "PAID")))
        reloadsWith("115.00", payoutRowJson(21, "150.00", "PAID", utr = "012345678901"))

        viewModel.openMarkPaid(21)
        viewModel.onPayoutUtrChange("012345678901")
        viewModel.markPayoutPaid()

        val body = bodyOf(PAID_21)
        assertEquals("012345678901", body.get("utr").asString)
        assertTrue(body.get("idempotency_key").asString.isNotBlank())
        val state = viewModel.uiState.value
        assertNull(state.payoutDialog)
        assertEquals("Payout marked paid.", state.message)
        assertEquals(PayoutStatus.PAID, state.order?.payouts?.single()?.status)
    }

    @Test
    fun markPaid_alreadyPaidElsewhereClosesDialogAndReloads() {
        val viewModel = loadedWithPayouts()
        server.reply(PAID_21, 409, error("This payout was already marked paid with a different UTR.", "payout_already_paid"))
        reloadsWith("115.00", payoutRowJson(21, "150.00", "PAID", utr = "999999999999"))

        viewModel.openMarkPaid(21)
        viewModel.onPayoutUtrChange("012345678901")
        viewModel.markPayoutPaid()

        val state = viewModel.uiState.value
        assertNull(state.payoutDialog)
        assertEquals("This payout was already marked paid with a different UTR.", state.message)
        assertEquals(PayoutStatus.PAID, state.order?.payouts?.single()?.status)
    }

    @Test
    fun cancel_requiresReasonWithinLimit() {
        val viewModel = loadedWithPayouts()
        viewModel.openCancelPayout(21)

        for (reason in listOf("", "   ", "x".repeat(CANCELLATION_REASON_MAX_LENGTH + 1))) {
            viewModel.onCancellationReasonChange(reason)
            viewModel.cancelPayout()
            assertNotNull(viewModel.uiState.value.cancellationReasonError)
        }
        assertTrue(server.requestsTo(CANCEL_21).isEmpty())
    }

    @Test
    fun cancel_sendsReasonAsTypedThenReloads() {
        val viewModel = loadedWithPayouts()
        server.reply(CANCEL_21, 200, ok(payoutJson(21, "CANCELLED")))
        reloadsWith("265.00", payoutRowJson(21, "150.00", "CANCELLED", reason = "Wrong amount"))

        viewModel.openCancelPayout(21)
        viewModel.onCancellationReasonChange("Wrong amount")
        viewModel.cancelPayout()

        val body = bodyOf(CANCEL_21)
        assertEquals(setOf("cancellation_reason", "idempotency_key"), body.keySet())
        assertEquals("Wrong amount", body.get("cancellation_reason").asString)
        val state = viewModel.uiState.value
        assertNull(state.payoutDialog)
        assertEquals("Payout cancelled.", state.message)
        assertEquals("265.00", state.order?.remainingToCommit)
        assertEquals("Wrong amount", state.order?.payouts?.single()?.cancellationReason)
    }

    @Test
    fun cancel_notCancellableClosesDialogAndReloads() {
        val viewModel = loadedWithPayouts()
        server.reply(CANCEL_21, 409, error("Only a pending payout can be cancelled.", "payout_not_cancellable"))
        reloadsWith("115.00", payoutRowJson(21, "150.00", "PAID", utr = "012345678901"))

        viewModel.openCancelPayout(21)
        viewModel.onCancellationReasonChange("Wrong amount")
        viewModel.cancelPayout()

        assertNull(viewModel.uiState.value.payoutDialog)
        assertEquals("Only a pending payout can be cancelled.", viewModel.uiState.value.message)
    }

    @Test
    fun paidAndCancelledPayoutsOfferNoActions() {
        val viewModel = loadedWithPayouts(
            "0.00", "PAYOUT_IN_PROGRESS",
            listOf(
                payoutRowJson(20, "100.00", "PAID", utr = "012345678901"),
                payoutRowJson(22, "65.00", "CANCELLED", reason = "Wrong amount")
            )
        )

        for (id in listOf(20L, 22L)) {
            viewModel.openMarkPaid(id)
            assertNull(viewModel.uiState.value.payoutDialog)
            viewModel.openCancelPayout(id)
            assertNull(viewModel.uiState.value.payoutDialog)
        }
    }

    @Test
    fun qr_loadsImageBytesFromAuthorizedEndpoint() {
        val viewModel = loadedWithPayouts()
        server.reply(QR_21, 200, "PNG-BYTES")

        viewModel.openPayoutQr(21)

        val state = viewModel.uiState.value
        assertEquals(PayoutDialog.Qr(21), state.payoutDialog)
        assertFalse(state.isQrLoading)
        assertNull(state.qrError)
        assertEquals("PNG-BYTES", state.qrImage?.decodeToString())
        assertEquals(1, server.requestsTo(QR_21).size)
    }

    @Test
    fun qr_backendErrorShown() {
        val viewModel = loadedWithPayouts()
        server.reply(QR_21, 404, error("The payout QR image is not available.", "payout_qr_not_found"))

        viewModel.openPayoutQr(21)

        val state = viewModel.uiState.value
        assertNull(state.qrImage)
        assertEquals("The payout QR image is not available.", state.qrError)
        viewModel.dismissPayoutDialog()
        assertNull(viewModel.uiState.value.payoutDialog)
    }
}
