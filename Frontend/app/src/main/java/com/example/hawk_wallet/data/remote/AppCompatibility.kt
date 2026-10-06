package com.example.hawk_wallet.data.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor

/**
 * App-version compatibility with the backend.
 *
 * Every request carries X-App-Version (the installed versionName). When the
 * backend answers 426 `app_update_required`, [updateRequired] fires so the
 * app can switch to the Force Update screen from whichever screen made the
 * call.
 */
object AppCompatibility {
    const val APP_VERSION_HEADER = "X-App-Version"
    private const val HTTP_UPGRADE_REQUIRED = 426
    private const val UPDATE_REQUIRED_CODE = "app_update_required"

    /** Set once from MainActivity before any request is made. */
    @Volatile
    var installedVersionName: String = ""
        private set

    private val _updateRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updateRequired: SharedFlow<Unit> = _updateRequired.asSharedFlow()

    fun setInstalledVersionName(versionName: String) {
        installedVersionName = versionName
    }

    val interceptor = Interceptor { chain ->
        val version = installedVersionName
        val request = if (version.isBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder().header(APP_VERSION_HEADER, version).build()
        }
        val response = chain.proceed(request)
        if (response.code == HTTP_UPGRADE_REQUIRED &&
            response.peekBody(4096).string().contains(UPDATE_REQUIRED_CODE)
        ) {
            _updateRequired.tryEmit(Unit)
        }
        response
    }
}
