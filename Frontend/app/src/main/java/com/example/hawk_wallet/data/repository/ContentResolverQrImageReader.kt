package com.example.hawk_wallet.data.repository

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// Mirrors the backend's QR rules (paymethods/validators.py).
private const val MAX_BYTES = 5 * 1024 * 1024
private const val MAX_SIDE = 4096
private val EXTENSION_FOR_MIME = mapOf(
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/webp" to "webp"
)
private const val UNREADABLE = "We couldn't read that image. Please choose a clear photo of your QR code."

/**
 * Reads a content:// image picked by the member. PNG/JPEG/WEBP within the
 * limits are uploaded unchanged; anything else (HEIC, GIF, oversized) is
 * decoded and re-encoded as PNG (or JPEG if PNG would exceed 5 MB). The
 * filename extension always matches the bytes, as the backend checks both.
 */
class ContentResolverQrImageReader(private val resolver: ContentResolver) : QrImageReader {

    override suspend fun read(uri: String): QrUpload = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val mime = resolver.getType(parsed)
        val bytes = try {
            resolver.openInputStream(parsed)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: throw QrImageException(UNREADABLE)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw QrImageException(UNREADABLE)

        val extension = EXTENSION_FOR_MIME[mime]
        val fitsAsIs = extension != null &&
            bytes.size <= MAX_BYTES &&
            bounds.outWidth <= MAX_SIDE &&
            bounds.outHeight <= MAX_SIDE
        if (fitsAsIs) {
            QrUpload("qr.$extension", mime!!, bytes)
        } else {
            reencode(bytes, bounds.outWidth, bounds.outHeight)
        }
    }

    private fun reencode(bytes: ByteArray, width: Int, height: Int): QrUpload {
        var sample = 1
        while (width / sample > MAX_SIDE || height / sample > MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw QrImageException(UNREADABLE)
        try {
            val png = compress(bitmap, Bitmap.CompressFormat.PNG, 100)
            if (png.size <= MAX_BYTES) return QrUpload("qr.png", "image/png", png)
            val jpeg = compress(bitmap, Bitmap.CompressFormat.JPEG, 90)
            if (jpeg.size <= MAX_BYTES) return QrUpload("qr.jpg", "image/jpeg", jpeg)
            throw QrImageException("That image is too large. Please choose a smaller photo of your QR code.")
        } finally {
            bitmap.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(format, quality, out)
            out.toByteArray()
        }
}
