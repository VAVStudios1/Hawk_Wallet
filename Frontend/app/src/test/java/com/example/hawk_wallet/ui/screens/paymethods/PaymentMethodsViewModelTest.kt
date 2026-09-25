package com.example.hawk_wallet.ui.screens.paymethods

import com.example.hawk_wallet.data.repository.FakePayoutMethodServer
import com.example.hawk_wallet.data.repository.FakePayoutMethodServer.Companion.flatJson
import com.example.hawk_wallet.data.repository.FakePayoutMethodServer.Companion.listJson
import com.example.hawk_wallet.data.repository.FakePayoutMethodServer.Companion.methodJson
import com.example.hawk_wallet.data.repository.PayoutMethodRepository
import com.example.hawk_wallet.data.repository.QrImageReader
import com.example.hawk_wallet.data.repository.QrUpload
import com.example.hawk_wallet.ui.model.PaymentProviderOption
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

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentMethodsViewModelTest {

    private val server = FakePayoutMethodServer()
    private val reader = QrImageReader { QrUpload("qr.png", "image/png", byteArrayOf(1)) }
    private lateinit var viewModel: PaymentMethodsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = PaymentMethodsViewModel(PayoutMethodRepository(server.api, reader))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = viewModel.uiState.value

    private fun fillForm(provider: PaymentProviderOption = PaymentProviderOption.PHONEPE, upi: String = "lucky@ybl") {
        viewModel.startNewMethod()
        viewModel.selectProvider(provider)
        viewModel.onUpiIdChange(upi)
        viewModel.onQrSelected("content://qr")
    }

    @Test
    fun creatingTheViewModelCallsNothing() {
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun refresh_mapsBackendMethods() {
        server.reply(
            200,
            listJson(
                methodJson(2, "GOOGLE_PAY", "Google Pay", "lucky@okaxis", default = true),
                methodJson(1, "PHONEPE", "PhonePe", "lucky@ybl", default = false, active = false)
            )
        )

        viewModel.refresh()

        assertFalse(state.isLoading)
        assertNull(state.loadError)
        val gpay = state.methods.first()
        assertEquals("GOOGLE_PAY", gpay.providerCode)
        assertEquals("Google Pay", gpay.providerName)
        assertTrue(gpay.isDefault && gpay.isActive)
        assertFalse(state.methods[1].isActive)
        assertNull(gpay.qrImageUri)
    }

    @Test
    fun refresh_401ShowsSessionExpired() {
        server.reply(401, """{"success":false,"message":"Given token not valid for any token type","errors":{"code":"token_not_valid"}}""")

        viewModel.refresh()

        assertEquals("Your session has expired. Please log in again.", state.loadError)
    }

    @Test
    fun save_requiresUpiAndQrBeforeCallingTheApi() {
        viewModel.startNewMethod()
        viewModel.selectProvider(PaymentProviderOption.BHIM)
        viewModel.onUpiIdChange("   ")

        viewModel.save()

        assertEquals("Enter your UPI ID.", state.form.upiError)
        assertEquals("Upload your QR code.", state.form.qrError)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun save_postsProviderCodeThenReloads() {
        fillForm(PaymentProviderOption.MOBIKWIK, upi = "  lucky@ikwik ")
        server.reply(201, flatJson(9, "MOBIKWIK", default = true))
            .reply(200, listJson(methodJson(9, "MOBIKWIK", "MobiKwik", "lucky@ikwik", default = true)))

        viewModel.save()

        assertTrue(state.form.saved)
        assertFalse(state.form.isSaving)
        assertEquals("Payment method added successfully", state.message)
        assertEquals(listOf("POST", "GET"), server.requests.map { it.method })
        assertEquals("MOBIKWIK", state.methods.single().providerCode)

        viewModel.saveHandled()
        assertFalse(state.form.saved)
    }

    @Test
    fun save_showsBackendFieldErrorsUnderFields() {
        fillForm(upi = "bad")
        server.reply(
            400,
            """{"success":false,"message":"Payout method details are invalid.","errors":{"code":"invalid","upi_id":["Enter a valid UPI ID (e.g. name@bank)."],"qr_image":["Upload a valid PNG, JPEG or WEBP image."]}}"""
        )

        viewModel.save()

        assertEquals("Enter a valid UPI ID (e.g. name@bank).", state.form.upiError)
        assertEquals("Upload a valid PNG, JPEG or WEBP image.", state.form.qrError)
        assertNull(state.form.generalError)
        assertFalse(state.form.saved)
    }

    @Test
    fun save_duplicateUpiShowsUnderUpiField() {
        fillForm()
        server.reply(
            409,
            """{"success":false,"message":"You already have an active payout method with this UPI ID.","errors":{"code":"duplicate_upi_id"}}"""
        )

        viewModel.save()

        assertEquals("You already have an active payout method with this UPI ID.", state.form.upiError)
        assertNull(state.form.generalError)
    }

    @Test
    fun save_providerErrorShowsAsGeneralError() {
        fillForm()
        server.reply(
            400,
            """{"success":false,"message":"Payout method details are invalid.","errors":{"code":"invalid","provider":["Choose an available payout provider."]}}"""
        )

        viewModel.save()

        assertEquals("Choose an available payout provider.", state.form.generalError)
    }

    @Test
    fun makeDefault_postsThenReloadsFromBackend() {
        server.reply(200, flatJson(2, "BHIM", default = true))
            .reply(
                200,
                listJson(
                    methodJson(2, "BHIM", "BHIM", "lucky@upi", default = true),
                    methodJson(1, "PHONEPE", "PhonePe", "lucky@ybl", default = false)
                )
            )

        viewModel.makeDefault(2)

        assertEquals(listOf("/api/payout-methods/2/default/", "/api/payout-methods/"), server.requests.map { it.url.encodedPath })
        assertEquals("Default payment method updated", state.message)
        assertTrue(state.methods.first { it.id == 2L }.isDefault)
        assertNull(state.busyMethodId)
    }

    @Test
    fun deactivate_backendChoosesTheNewDefault() {
        server.reply(200, flatJson(2, "BHIM", default = false, active = false))
            .reply(
                200,
                listJson(
                    methodJson(3, "GOOGLE_PAY", "Google Pay", "lucky@okaxis", default = true),
                    methodJson(2, "BHIM", "BHIM", "lucky@upi", default = false, active = false)
                )
            )

        viewModel.deactivate(2)

        assertEquals("/api/payout-methods/2/deactivate/", server.requests.first().url.encodedPath)
        assertEquals("Payment method deactivated", state.message)
        assertFalse(state.methods.first { it.id == 2L }.isActive)
        assertTrue(state.methods.first { it.id == 3L }.isDefault)
    }

    @Test
    fun mutationError_isShownAndListReloaded() {
        server.reply(404, """{"success":false,"message":"Payout method not found.","errors":{"code":"not_found"}}""")
            .reply(200, listJson())

        viewModel.deactivate(99)

        assertEquals("Payout method not found.", state.message)
        assertEquals(2, server.requests.size)
    }
}
