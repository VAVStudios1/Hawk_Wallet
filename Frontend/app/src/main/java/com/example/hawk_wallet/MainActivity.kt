package com.example.hawk_wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.remote.AppCompatibility
import com.example.hawk_wallet.data.remote.NetworkModule
import com.example.hawk_wallet.data.repository.AdminDashboardRepository
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.AdminUserRepository
import com.example.hawk_wallet.data.repository.AppPackageInfo
import com.example.hawk_wallet.data.repository.AppUpdateRepository
import com.example.hawk_wallet.data.repository.AuthRepository
import com.example.hawk_wallet.data.repository.BuyMethodRepository
import com.example.hawk_wallet.data.repository.ContentResolverQrImageReader
import com.example.hawk_wallet.data.repository.HomeRepository
import com.example.hawk_wallet.data.repository.OrderRepository
import com.example.hawk_wallet.data.repository.PayoutMethodRepository
import com.example.hawk_wallet.data.repository.ProfileRepository
import com.example.hawk_wallet.data.repository.SupportAttachmentFiles
import com.example.hawk_wallet.data.repository.SupportChatRepository
import com.example.hawk_wallet.data.repository.TeamRepository
import com.example.hawk_wallet.ui.navigation.HawkApp
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The installed versionName goes out as X-App-Version on every
        // request, so it is set before any API client is used.
        val appPackageInfo = AppPackageInfo(applicationContext)
        AppCompatibility.setInstalledVersionName(appPackageInfo.installedVersionName)

        sessionManager = SessionManager(applicationContext)
        val sessionRefresher = NetworkModule.provideSessionRefresher(sessionManager)
        val authApi = NetworkModule.provideAuthApi(sessionManager)
        val authRepository = AuthRepository(authApi)
        val homeRepository = HomeRepository(NetworkModule.provideHomeApi(sessionManager))
        val qrImageReader = ContentResolverQrImageReader(applicationContext.contentResolver)
        val payoutMethodRepository = PayoutMethodRepository(
            NetworkModule.providePayoutMethodApi(sessionManager),
            qrImageReader
        )
        val buyMethodRepository = BuyMethodRepository(NetworkModule.provideBuyMethodApi(sessionManager))
        val orderRepository = OrderRepository(NetworkModule.provideOrderApi(sessionManager))
        val adminOrderRepository = AdminOrderRepository(
            NetworkModule.provideAdminOrderApi(sessionManager),
            qrImageReader
        )
        val adminDashboardRepository = AdminDashboardRepository(NetworkModule.provideAdminDashboardApi(sessionManager))
        val adminUserRepository = AdminUserRepository(NetworkModule.provideAdminUserApi(sessionManager))
        val adminManagementRepository = AdminManagementRepository(NetworkModule.provideAdminManagementApi(sessionManager))
        val teamRepository = TeamRepository(NetworkModule.provideTeamApi(sessionManager))
        val profileRepository = ProfileRepository(NetworkModule.provideProfileApi(sessionManager))
        val supportChatRepository = SupportChatRepository(
            NetworkModule.provideSupportChatApi(sessionManager),
            SupportAttachmentFiles(applicationContext.contentResolver, applicationContext.cacheDir)
        )

        val appUpdateRepository = AppUpdateRepository(
            NetworkModule.provideAppUpdateApi(),
            NetworkModule.provideApkDownloadClient(),
            appPackageInfo,
            File(applicationContext.filesDir, "app_updates")
        )

        setContent {
            Hawk_WalletTheme {
                HawkApp(
                    authRepository = authRepository,
                    homeRepository = homeRepository,
                    payoutMethodRepository = payoutMethodRepository,
                    buyMethodRepository = buyMethodRepository,
                    orderRepository = orderRepository,
                    adminOrderRepository = adminOrderRepository,
                    adminDashboardRepository = adminDashboardRepository,
                    adminUserRepository = adminUserRepository,
                    adminManagementRepository = adminManagementRepository,
                    teamRepository = teamRepository,
                    profileRepository = profileRepository,
                    supportChatRepository = supportChatRepository,
                    appUpdateRepository = appUpdateRepository,
                    sessionManager = sessionManager,
                    sessionRefresher = sessionRefresher
                )
            }
        }
    }

    /**
     * Every touch or key event the user sends to the app - the only thing
     * that counts as activity for the 12-hour idle timeout. Polling,
     * refreshes and timers never pass through here.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionManager.recordUserActivity()
    }

    override fun onStop() {
        super.onStop()
        sessionManager.flushUserActivity()
    }
}
