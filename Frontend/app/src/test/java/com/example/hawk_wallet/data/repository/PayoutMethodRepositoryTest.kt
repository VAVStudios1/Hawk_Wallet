package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.repository.FakePayoutMethodServer.Companion.flatJson
import com.example.hawk_wallet.data.repository.FakePayoutMethodServer.Companion.listJson
import com.example.hawk_wallet.data.repository.FakePayoutMethodServer.Companion.methodJson
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayoutMethodRepositoryTest {

    private val qrBytes = byteArrayOf(1, 2, 3, 4)
    private val reader = QrImageReader { QrUpload("qr.png", "image/png", qrBytes) }

    @Test
    fun list_getsPayoutMethods() = runTest {
        val server = FakePayoutMethodServer()
            .reply(200, listJson(methodJson(1, "PHONEPE", "PhonePe", "lucky@ybl", default = true)))

        val result = PayoutMethodRepository(server.api, reader).list()

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/api/payout-methods/", request.url.encodedPath)
        val methods = (result as ApiResult.Success).data
        assertEquals("PHONEPE", methods.single().provider.code)
        assertEquals("lucky@ybl", methods.single().upi_id)
    }

    @Test
    fun add_postsMultipartWithBackendFieldNames() = runTest {
        val server = FakePayoutMethodServer().reply(201, flatJson(7, "PHONEPE", default = true))

        val result = PayoutMethodRepository(server.api, reader)
            .add(providerCode = "PHONEPE", upiId = "lucky@ybl", label = "", qrImageUri = "content://qr")

        assertTrue(result is ApiResult.Success)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/payout-methods/", request.url.encodedPath)
        val body = request.body as MultipartBody
        assertEquals("multipart", body.type.type)
        assertEquals("form-data", body.type.subtype)

        val parts = body.parts.associateBy { part ->
            Regex("name=\"([^\"]+)\"").find(part.headers!!["Content-Disposition"]!!)!!.groupValues[1]
        }
        assertEquals(setOf("provider_code", "upi_id", "label", "qr_image"), parts.keys)
        assertEquals("PHONEPE", parts.getValue("provider_code").text())
        assertEquals("lucky@ybl", parts.getValue("upi_id").text())
        assertEquals("", parts.getValue("label").text())

        val qr = parts.getValue("qr_image")
        assertTrue(qr.headers!!["Content-Disposition"]!!.contains("filename=\"qr.png\""))
        assertEquals("image/png", qr.body.contentType().toString())
        assertArrayEquals(qrBytes, Buffer().also { qr.body.writeTo(it) }.readByteArray())
    }

    @Test
    fun add_unreadableQrNeverCallsTheApi() = runTest {
        val server = FakePayoutMethodServer()
        val failing = QrImageReader { throw QrImageException("We couldn't read that image.") }

        val result = PayoutMethodRepository(server.api, failing)
            .add("PHONEPE", "lucky@ybl", "", "content://bad")

        assertTrue(server.requests.isEmpty())
        assertEquals(listOf("We couldn't read that image."), (result as ApiResult.Error).fieldErrors["qr_image"])
    }

    @Test
    fun add_surfacesBackendFieldErrors() = runTest {
        val server = FakePayoutMethodServer().reply(
            400,
            """{"success":false,"message":"Payout method details are invalid.","errors":{"code":"invalid","upi_id":["Enter a valid UPI ID (e.g. name@bank)."]}}"""
        )

        val result = PayoutMethodRepository(server.api, reader).add("PHONEPE", "bad", "", "content://qr")

        val error = result as ApiResult.Error
        assertEquals(400, error.statusCode)
        assertEquals(listOf("Enter a valid UPI ID (e.g. name@bank)."), error.fieldErrors["upi_id"])
    }

    @Test
    fun defaultAndDeactivate_postToTheMethodPaths() = runTest {
        val server = FakePayoutMethodServer()
            .reply(200, flatJson(5, "BHIM", default = true))
            .reply(200, flatJson(5, "BHIM", default = false, active = false))
        val repository = PayoutMethodRepository(server.api, reader)

        assertTrue(repository.setDefault(5) is ApiResult.Success)
        assertTrue(repository.deactivate(5) is ApiResult.Success)

        assertEquals(listOf("POST", "POST"), server.requests.map { it.method })
        assertEquals(
            listOf("/api/payout-methods/5/default/", "/api/payout-methods/5/deactivate/"),
            server.requests.map { it.url.encodedPath }
        )
    }

    private fun MultipartBody.Part.text(): String = Buffer().also { body.writeTo(it) }.readUtf8()
}
