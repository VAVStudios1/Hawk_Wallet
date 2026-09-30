package com.example.hawk_wallet.data.repository

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

// Mirrors the backend's attachment rules (support/attachments.py). The
// backend re-validates and re-encodes everything; these checks only stop
// an upload that would certainly be refused.
private const val MAX_BYTES = 10 * 1024 * 1024
private const val MAX_IMAGE_SIDE = 8192
private val IMAGE_EXTENSION_FOR_MIME = mapOf(
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/webp" to "webp"
)
private val PDF_HEADER = "%PDF-".toByteArray()
private const val PDF_HEADER_WINDOW = 1024

/** JPEG end-of-image marker; a JPEG without it is truncated (Pillow refuses it, Android's decoder does not). */
private val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
/** PNG's final chunk type; its absence means the PNG is truncated. */
private val PNG_IEND = "IEND".toByteArray()
/** How far from the end the end marker may sit (encoders may pad after it). */
private const val END_MARKER_WINDOW = 1024

private const val COPY_BUFFER_BYTES = 64 * 1024

private const val UNREADABLE_IMAGE = "We couldn't read that image. Please choose another one."
private const val UNREADABLE_FILE = "We couldn't read that file. Please choose another one."
private const val NOT_PDF = "Only PDF files can be attached."
private const val TOO_LARGE = "Attachments must be 10 MB or smaller."

/** Folder under the app's private cache that the FileProvider shares (res/xml/support_file_paths.xml). */
private const val VIEW_DIR = "support_files"

/** Private cache folder for picked attachments waiting to be sent; never shared. */
private const val UPLOAD_DIR = "support_uploads"

enum class SupportAttachmentKind(val apiType: String) { IMAGE("IMAGE"), FILE("FILE") }

/**
 * A picked attachment ready to upload as the multipart `file` part: a
 * complete, closed copy in the app's private cache, so the request body
 * is the whole file and its length is the file's real size.
 */
class SupportUpload(
    val kind: SupportAttachmentKind,
    val fileName: String,
    val mimeType: String,
    val file: File
) {
    val size: Long get() = file.length()
}

/** Thrown when a picked file cannot become an acceptable support attachment. */
class SupportAttachmentException(message: String) : Exception(message)

/**
 * Device-side file handling for support chat: copying a picked content://
 * URI into a private cache file for upload, and writing a downloaded
 * attachment into the cache so another app can open it through the
 * FileProvider.
 */
class SupportAttachmentFiles(
    private val resolver: ContentResolver,
    private val cacheDir: File
) {

    @Throws(SupportAttachmentException::class)
    suspend fun read(uri: String, kind: SupportAttachmentKind): SupportUpload = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val displayName = displayName(parsed)
        val mime = resolver.getType(parsed)
        val unreadable = if (kind == SupportAttachmentKind.IMAGE) UNREADABLE_IMAGE else UNREADABLE_FILE

        // Picker URIs have no filesystem path: copy the whole stream first.
        val copy = copyToCache(parsed) ?: throw SupportAttachmentException(unreadable)
        try {
            when (kind) {
                SupportAttachmentKind.IMAGE -> image(copy, mime, displayName)
                SupportAttachmentKind.FILE -> pdf(copy, mime, displayName)
            }
        } catch (e: Throwable) {
            copy.delete()
            throw e
        }
    }

    /** Deletes a picked attachment's cache copy once it is sent or removed. */
    suspend fun discard(upload: SupportUpload) = withContext(Dispatchers.IO) {
        upload.file.delete()
    }

    /**
     * Writes a downloaded attachment for viewing and returns the file. Only
     * the latest one is kept: earlier copies are deleted first.
     */
    suspend fun saveForViewing(bytes: ByteArray, fileName: String): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, VIEW_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, safeFileName(fileName)).apply { writeBytes(bytes) }
    }

    /**
     * Copies [uri] into a new, uniquely named cache file, reading until EOF
     * and closing (flushing) the output before returning. The copy is
     * accepted only if the file on disk holds every byte read; anything
     * else (error, empty, over the limit, short write) is deleted and null
     * returned, so a partial file is never uploaded or reused.
     */
    private fun copyToCache(uri: Uri): File? {
        val dir = File(cacheDir, UPLOAD_DIR).apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.tmp")
        return try {
            val input = resolver.openInputStream(uri) ?: return null
            var copied = 0L
            input.use { source ->
                FileOutputStream(target).use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        copied += read
                        if (copied > MAX_BYTES) throw SupportAttachmentException(TOO_LARGE)
                        sink.write(buffer, 0, read)
                    }
                    sink.flush()
                    sink.fd.sync()
                }
            }
            if (copied == 0L || target.length() != copied) {
                target.delete()
                null
            } else {
                target
            }
        } catch (e: SupportAttachmentException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    private fun image(copy: File, mime: String?, displayName: String?): SupportUpload {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(copy.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw SupportAttachmentException(UNREADABLE_IMAGE)

        val extension = IMAGE_EXTENSION_FOR_MIME[mime]
        val withinLimits = copy.length() <= MAX_BYTES &&
            bounds.outWidth <= MAX_IMAGE_SIDE &&
            bounds.outHeight <= MAX_IMAGE_SIDE
        if (extension != null && withinLimits && isComplete(copy, mime!!)) {
            return SupportUpload(SupportAttachmentKind.IMAGE, nameWithExtension(displayName, "image", extension), mime, copy)
        }

        // Re-encode from the decoded pixels when the file cannot be sent as
        // is: HEIC / GIF / oversized, or a JPEG / PNG that ends before its
        // end marker (Android's decoder tolerates that; the backend rightly
        // does not). PNG stays PNG; everything else becomes JPEG. The
        // extension always matches the bytes, as the backend requires.
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_SIDE || bounds.outHeight / sample > MAX_IMAGE_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(copy.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw SupportAttachmentException(UNREADABLE_IMAGE)
        val asPng = mime == "image/png"
        val format = if (asPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val encoded = File(copy.parentFile, "${UUID.randomUUID()}.tmp")
        try {
            val written = FileOutputStream(encoded).use { out ->
                val ok = bitmap.compress(format, if (asPng) 100 else 95, out)
                out.flush()
                out.fd.sync()
                ok
            }
            if (!written || encoded.length() == 0L) throw SupportAttachmentException(UNREADABLE_IMAGE)
            if (encoded.length() > MAX_BYTES) throw SupportAttachmentException(TOO_LARGE)
        } catch (e: Throwable) {
            encoded.delete()
            throw if (e is SupportAttachmentException) e else SupportAttachmentException(UNREADABLE_IMAGE)
        } finally {
            bitmap.recycle()
        }
        copy.delete()
        return if (asPng) {
            SupportUpload(SupportAttachmentKind.IMAGE, nameWithExtension(displayName, "image", "png"), "image/png", encoded)
        } else {
            SupportUpload(SupportAttachmentKind.IMAGE, nameWithExtension(displayName, "image", "jpg"), "image/jpeg", encoded)
        }
    }

    /** True if a JPEG / PNG copy ends with its end marker (WEBP is length-prefixed and decoded strictly anyway). */
    private fun isComplete(file: File, mime: String): Boolean = when (mime) {
        "image/jpeg" -> tailContains(file, JPEG_EOI)
        "image/png" -> tailContains(file, PNG_IEND)
        else -> true
    }

    private fun tailContains(file: File, marker: ByteArray): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val window = minOf(raf.length(), END_MARKER_WINDOW.toLong()).toInt()
            val tail = ByteArray(window)
            raf.seek(raf.length() - window)
            raf.readFully(tail)
            indexOf(tail, marker, window) >= 0
        }
    } catch (e: IOException) {
        false
    }

    private fun pdf(copy: File, mime: String?, displayName: String?): SupportUpload {
        val namedPdf = displayName?.lowercase()?.endsWith(".pdf") == true
        if (mime != "application/pdf" && !namedPdf) throw SupportAttachmentException(NOT_PDF)
        if (!startsWithPdfHeader(copy)) throw SupportAttachmentException(NOT_PDF)
        if (copy.length() > MAX_BYTES) throw SupportAttachmentException(TOO_LARGE)
        return SupportUpload(SupportAttachmentKind.FILE, nameWithExtension(displayName, "document", "pdf"), "application/pdf", copy)
    }

    private fun startsWithPdfHeader(file: File): Boolean {
        val head = ByteArray(minOf(file.length(), PDF_HEADER_WINDOW.toLong()).toInt())
        file.inputStream().use { input ->
            var filled = 0
            while (filled < head.size) {
                val read = input.read(head, filled, head.size - filled)
                if (read == -1) break
                filled += read
            }
        }
        return indexOf(head, PDF_HEADER, head.size) >= 0
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, length: Int): Int {
        for (start in 0..(length - needle.size)) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        }
        return -1
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** The picked name's stem (shown in the chat) with the extension that matches the bytes. */
    private fun nameWithExtension(displayName: String?, fallbackStem: String, extension: String): String {
        val stem = displayName?.substringBeforeLast('.')?.let { safeStem(it) }.orEmpty()
        return "${stem.ifEmpty { fallbackStem }}.$extension"
    }

    private fun safeFileName(name: String): String {
        val stem = safeStem(name.substringBeforeLast('.'))
        val extension = name.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(8)
        return "${stem.ifEmpty { "attachment" }}${if (extension.isEmpty()) "" else ".$extension"}"
    }

    private fun safeStem(stem: String): String =
        stem.filter { it.isLetterOrDigit() || it in " -_()" }.trim().take(80)
}
