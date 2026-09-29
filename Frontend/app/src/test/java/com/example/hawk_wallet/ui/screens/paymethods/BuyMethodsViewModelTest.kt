package com.example.hawk_wallet.ui.screens.paymethods

import com.example.hawk_wallet.data.repository.BuyMethodRepository
import com.example.hawk_wallet.data.repository.FakeOrderServer
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.buyFlatJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.buyListJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.buyMethodJson
import com.example.hawk_wallet.data.repository.FakeOrderServer.Companion.error
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val LIST = "GET /api/buy-methods/"
private const val ADD = "POST /api/buy-methods/"

@OptIn(ExperimentalCoroutinesApi::class)
class BuyMethodsViewModelTest {

    private val server = FakeOrderServer()
    private lateinit var viewModel: BuyMethodsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = BuyMethodsViewModel(BuyMethodRepository(server.buyMethodApi))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = viewModel.uiState.value

    @Test
    fun creatingTheViewModelCallsNothing() {
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun refresh_mapsBackendMethodsFromBuyEndpointOnly() {
        server.reply(
            LIST, 200,
            buyListJson(
                buyMethodJson(2, "GOOGLE_PAY", "Google Pay", "me@okaxis", default = true),
                buyMethodJson(1, "PHONEPE", "PhonePe", "me@ybl", default = false, active = false)
            )
        )

        viewModel.refresh()

        assertEquals(listOf(2L, 1L), state.methods.map { it.id })
        assertTrue(state.methods[0].isDefault)
        assertFalse(state.methods[1].isActive)
        assertEquals(listOf(LIST), server.requests.map { "${it.method} ${it.url.encodedPath}" })
    }

    @Test
    fun selectProvider_acceptsPersonalProvidersOnly() {
        viewModel.startNewMethod()

        viewModel.selectProvider(PaymentProviderOption.PHONEPE_BUSINESS)
        assertNull(state.form.provider)

        viewModel.selectProvider(PaymentProviderOption.PHONEPE)
        assertEquals(PaymentProviderOption.PHONEPE, state.form.provider)
    }

    @Test
    fun save_requiresProviderAndUpiBeforeCallingTheApi() {
        viewModel.startNewMethod()
        viewModel.onUpiIdChange("   ")

        viewModel.save()

        assertEquals("Enter your UPI ID.", state.form.upiError)
        assertEquals("Choose your UPI app.", state.form.generalError)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun save_postsJsonWithoutQrThenReloads() {
        viewModel.startNewMethod()
        viewModel.selectProvider(PaymentProviderOption.BHIM)
        viewModel.onUpiIdChange("  me@upi ")
        viewModel.onLabelChange(" Savings ")
        server.reply(ADD, 201, buyFlatJson(9, "BHIM", default = true))
            .reply(LIST, 200, buyListJson(buyMethodJson(9, "BHIM", "BHIM", "me@upi", default = true)))

        viewModel.save()

        val body = JsonParser.parseString(server.bodyOf(server.requestsTo(ADD).single())).asJsonObject
        assertEquals(setOf("provider_code", "upi_id", "label"), body.keySet())
        assertEquals("BHIM", body["provider_code"].asString)
        assertEquals("me@upi", body["upi_id"].asString)
        assertEquals("Savings", body["label"].asString)
        assertTrue(state.form.saved)
        assertEquals("BUY account added successfully", state.message)
        assertEquals(9L, state.methods.single().id)
    }

    @Test
    fun save_duplicateUpiShownUnderField() {
        viewModel.startNewMethod()
        viewModel.selectProvider(PaymentProviderOption.GOOGLE_PAY)
        viewModel.onUpiIdChange("me@okaxis")
        server.reply(ADD, 409, error("You already have an active buy method with this UPI ID.", "duplicate_upi_id"))

        viewModel.save()

        assertEquals("You already have an active buy method with this UPI ID.", state.form.upiError)
        assertFalse(state.form.saved)
        assertFalse(state.form.isSaving)
    }

    @Test
    fun deactivate_callsBuyEndpointThenReloads() {
        server.reply("POST /api/buy-methods/4/deactivate/", 200, buyFlatJson(4, "PHONEPE", default = false, active = false))
            .reply(LIST, 200, buyListJson())

        viewModel.deactivate(4)

        assertEquals("BUY account deactivated", state.message)
        assertNull(state.busyMethodId)
        assertTrue(state.methods.isEmpty())
    }
}
