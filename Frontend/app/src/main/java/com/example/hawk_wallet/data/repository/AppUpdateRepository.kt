package com.example.hawk_wallet.data.repository

import android.content.Intent
import com.example.hawk_wallet.data.remote.api.AppUpdateApi
import com.example.hawk_wallet.data.remote.model.UpdateCheckDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** A newer release offered by the backend, with everything needed to fetch and verify it. */
data class AppUpdate(
    val latestVersion: String,
    val minimumSupportedVersion: String,
    val forceUpdate: Boolean,
    val apkUrl: String,
    val apkSha256: String,
    val apkSize: Long?,
    val releaseNotes: List<String>
)

/** What the update check found for the installed version. */
sealed interface UpdateCheckResult {
    /** Nothing to do: up to date, newer than latest, or no usable release. */
    data object UpToDate : UpdateCheckResult
    data class Available(val update: AppUpdate) : UpdateCheckResult
    /** The backend says an update is required but sent no usable download. */
    data object RequiredButUnavailable : UpdateCheckResult
}

sealed interface ApkResult {
    data class Verified(val file: File) : ApkResult
    data class Failed(val message: String) : ApkResult
}

/**
 * In-app APK updates. Only the backend decides whether an update is needed;
 * this class never compares versions to decide that. Downloads are streamed
 * to private storage (never held in memory) and an APK is only handed to
 * the installer after its size, SHA-256 and package identity all match.
 */
class AppUpdateRepository(
    private val api: AppUpdateApi,
    private val downloadClient: OkHttpClient,
    private val packageInfo: AppPackageInfo,
    private val updatesDir: File
) {
    val installedVersionName: String
        get() = packageInfo.installedVersionName

    suspend fun checkForUpdate(): ApiResult<UpdateCheckResult> =
        when (val result = safeApiCall { api.checkForUpdate(installedVersionName) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.toCheckResult(), result.message)
            is ApiResult.Error -> result
        }

    /**
     * Downloads [update]'s APK over HTTPS into private storage and verifies
     * it. Any failed or mismatching file is deleted. [onProgress] gets 0-100,
     * or null while the total size is unknown.
     */
    suspend fun downloadApk(update: AppUpdate, onProgress: (Int?) -> Unit): ApkResult =
        withContext(Dispatchers.IO) {
            val partial = File(updatesDir, PARTIAL_FILE_NAME)
            val target = File(updatesDir, "hawk-wallet-${update.latestVersion}.apk")
            var result: ApkResult = ApkResult.Failed(DOWNLOAD_FAILED)
            try {
                clearDownloads()
                updatesDir.mkdirs()
                result = download(update, partial, onProgress)
                if (result is ApkResult.Verified) {
                    result = verify(partial, update)
                }
                if (result is ApkResult.Verified) {
                    result = if (partial.renameTo(target)) ApkResult.Verified(target) else ApkResult.Failed(DOWNLOAD_FAILED)
                }
            } catch (e: IOException) {
                result = ApkResult.Failed("Download interrupted. Check your connection and try again.")
            } finally {
                partial.delete()
                if (result !is ApkResult.Verified) target.delete()
            }
            result
        }

    /**
     * Re-checks a previously downloaded APK right before installing it.
     * A file that no longer matches is deleted.
     */
    suspend fun verifyDownloaded(file: File, update: AppUpdate): ApkResult =
        withContext(Dispatchers.IO) {
            val result = try {
                verify(file, update)
            } catch (e: IOException) {
                ApkResult.Failed(VERIFY_FAILED)
            }
            if (result !is ApkResult.Verified) file.delete()
            result
        }

    /** Removes any downloaded or partial APK (e.g. left over from an installed update). */
    suspend fun clearDownloads() = withContext(Dispatchers.IO) {
        updatesDir.listFiles()?.forEach { it.delete() }
    }

    fun canRequestPackageInstalls(): Boolean = packageInfo.canRequestPackageInstalls()

    fun installPermissionSettingsIntent(): Intent = packageInfo.installPermissionSettingsIntent()

    fun installerIntent(apk: File): Intent = packageInfo.installerIntent(apk)

    private suspend fun download(update: AppUpdate, partial: File, onProgress: (Int?) -> Unit): ApkResult {
        val url = update.apkUrl.toHttpUrlOrNull()
        if (url == null || !url.isHttps) return ApkResult.Failed(INSECURE_LINK)

        val request = Request.Builder().url(url).get().build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ApkResult.Failed(DOWNLOAD_FAILED)
            // Redirects may have moved the request; it must still be HTTPS.
            if (!response.request.url.isHttps) return ApkResult.Failed(INSECURE_LINK)
            val body = response.body ?: return ApkResult.Failed(DOWNLOAD_FAILED)
            val limit = update.apkSize ?: MAX_APK_BYTES
            val total = update.apkSize ?: body.contentLength().takeIf { it > 0 }

            var written = 0L
            var lastPercent = -1
            onProgress(if (total == null) null else 0)
            body.byteStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > limit) return ApkResult.Failed(VERIFY_FAILED)
                        output.write(buffer, 0, read)
                        if (total != null) {
                            val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }
        return ApkResult.Verified(partial)
    }

    /**
     * The file must match the backend's size and SHA-256 exactly, and be an
     * APK of this app with the announced version and a higher versionCode
     * than the installed one (no downgrades). Signature continuity is
     * enforced by Android's Package Installer.
     */
    private fun verify(file: File, update: AppUpdate): ApkResult {
        if (!file.isFile) return ApkResult.Failed(VERIFY_FAILED)
        val expectedSize = update.apkSize
        if (expectedSize != null && file.length() != expectedSize) return ApkResult.Failed(VERIFY_FAILED)

        val expectedSha = update.apkSha256.lowercase()
        if (!SHA256_REGEX.matches(expectedSha)) return ApkResult.Failed(VERIFY_FAILED)
        val actualSha = sha256Hex(file)
        if (!MessageDigest.isEqual(actualSha.toByteArray(), expectedSha.toByteArray())) {
            return ApkResult.Failed(VERIFY_FAILED)
        }

        val archive = packageInfo.readArchive(file) ?: return ApkResult.Failed(VERIFY_FAILED)
        if (archive.packageName != packageInfo.packageName) return ApkResult.Failed(VERIFY_FAILED)
        if (archive.versionName != update.latestVersion) return ApkResult.Failed(VERIFY_FAILED)
        if (archive.versionCode <= packageInfo.installedVersionCode) return ApkResult.Failed(VERIFY_FAILED)
        return ApkResult.Verified(file)
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun UpdateCheckDto.toCheckResult(): UpdateCheckResult {
        if (!update_available && !force_update) return UpdateCheckResult.UpToDate
        val latest = latest_version
        val minimum = minimum_supported_version
        val url = apk_url
        val sha = apk_sha256
        if (latest.isNullOrBlank() || minimum.isNullOrBlank() || url.isNullOrBlank() || sha.isNullOrBlank()) {
            return if (force_update) UpdateCheckResult.RequiredButUnavailable else UpdateCheckResult.UpToDate
        }
        return UpdateCheckResult.Available(
            AppUpdate(
                latestVersion = latest,
                minimumSupportedVersion = minimum,
                forceUpdate = force_update,
                apkUrl = url,
                apkSha256 = sha,
                apkSize = apk_size?.takeIf { it > 0 },
                releaseNotes = release_notes.orEmpty()
            )
        )
    }

    private companion object {
        const val PARTIAL_FILE_NAME = "download.part"
        const val BUFFER_SIZE = 64 * 1024
        // Only used when the backend sends no size: never fill the disk.
        const val MAX_APK_BYTES = 300L * 1024 * 1024
        val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

        const val DOWNLOAD_FAILED = "The update could not be downloaded. Please try again."
        const val INSECURE_LINK = "The update link is not secure, so it was not downloaded."
        const val VERIFY_FAILED = "The downloaded update failed verification and was deleted. Please try again."
    }
}
