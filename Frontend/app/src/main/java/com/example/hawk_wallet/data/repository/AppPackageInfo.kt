package com.example.hawk_wallet.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/**
 * The installed app's identity and the Android package-installer hand-off.
 * Installing always goes through the system Package Installer, which checks
 * that the update is signed with the same key as the installed app.
 */
class AppPackageInfo(private val context: Context) {

    val installedVersionName: String
        get() = installedPackage().versionName.orEmpty()

    val installedVersionCode: Long
        get() = PackageInfoCompat.getLongVersionCode(installedPackage())

    val packageName: String
        get() = context.packageName

    /** Package name / versionName / versionCode read from an APK file, or null if unreadable. */
    fun readArchive(file: File): ArchiveInfo? {
        val info = getArchiveInfo(file) ?: return null
        return ArchiveInfo(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info)
        )
    }

    /** Whether the user lets this app install packages ("Install unknown apps"). */
    fun canRequestPackageInstalls(): Boolean =
        // Below Android 8 the system installer itself asks about unknown sources.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** This app's "Install unknown apps" settings page (Android 8+). */
    fun installPermissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Hands [apk] to the system Package Installer through a read-only content URI. */
    fun installerIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$APK_PROVIDER_SUFFIX", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun installedPackage(): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

    private fun getArchiveInfo(file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.path, 0)
        }

    data class ArchiveInfo(val packageName: String, val versionName: String?, val versionCode: Long)

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        /** Authority suffix of ApkFileProvider in AndroidManifest.xml. */
        const val APK_PROVIDER_SUFFIX = ".apkprovider"
    }
}
