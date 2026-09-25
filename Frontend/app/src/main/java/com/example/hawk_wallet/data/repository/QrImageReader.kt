package com.example.hawk_wallet.data.repository

/** A QR image ready to upload as the qr_image multipart file. */
class QrUpload(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

/** Thrown when a picked file cannot be turned into an acceptable QR upload. */
class QrImageException(message: String) : Exception(message)

/** Turns a picked image URI into bytes the backend accepts (PNG/JPEG/WEBP, <= 5 MB, <= 4096px). */
fun interface QrImageReader {
    @Throws(QrImageException::class)
    suspend fun read(uri: String): QrUpload
}
