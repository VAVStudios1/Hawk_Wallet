package com.example.hawk_wallet.data.local

import androidx.core.content.FileProvider

/**
 * Shares only verified update APKs (files/app_updates/) with the system
 * Package Installer. A separate provider so the support-chat provider's
 * paths stay unchanged.
 */
class ApkFileProvider : FileProvider()
