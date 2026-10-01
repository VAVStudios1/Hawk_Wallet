package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AdminBottomNavigation
import com.example.hawk_wallet.ui.components.AdminNavDestination
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.profile.ProfileOptionRow
import com.example.hawk_wallet.ui.screens.profile.ProfileSection
import com.example.hawk_wallet.ui.screens.profile.RowDivider
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

enum class AdminMoreSection(val title: String) {
    OPERATIONS("Operations"),
    ACCOUNT("Account"),
    SUPER_ADMIN("Super Admin")
}

/** Every More destination; the SUPER_ADMIN section is shown to SUPER_ADMIN only (and gated on entry). */
enum class AdminMoreItem(
    val title: String,
    val icon: ImageVector,
    val section: AdminMoreSection
) {
    PAYMENTS("Payments", Icons.Outlined.Payments, AdminMoreSection.OPERATIONS),
    PAYOUTS("Payouts", Icons.Outlined.AccountBalanceWallet, AdminMoreSection.OPERATIONS),
    REPORTS("Reports", Icons.Outlined.Assessment, AdminMoreSection.OPERATIONS),
    PROFILE("Profile", Icons.Outlined.Person, AdminMoreSection.ACCOUNT),
    ADMINS("Admins", Icons.Outlined.AdminPanelSettings, AdminMoreSection.SUPER_ADMIN),
    AUDIT_LOG("Audit Log", Icons.Outlined.History, AdminMoreSection.SUPER_ADMIN),
    SYSTEM_REPORTS("System Reports", Icons.Outlined.Insights, AdminMoreSection.SUPER_ADMIN);

    val superAdminOnly: Boolean
        get() = section == AdminMoreSection.SUPER_ADMIN
}

/** The items [role] may see. SUPER_ADMIN-only items are left out for everyone else. */
internal fun adminMoreItems(role: String?): List<AdminMoreItem> =
    AdminMoreItem.entries.filter { !it.superAdminOnly || isSuperAdmin(role) }

/** The admin More tab: secondary destinations, grouped; Super Admin tools only for SUPER_ADMIN. */
@Composable
fun AdminMoreScreen(
    role: String?,
    onItemClick: (AdminMoreItem) -> Unit,
    onBottomNavSelect: (AdminNavDestination) -> Unit
) {
    val items = adminMoreItems(role)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "More", onBackClick = null) },
        bottomBar = { AdminBottomNavigation(selected = AdminNavDestination.MORE, onSelect = onBottomNavSelect) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AdminMoreSection.entries.forEach { section ->
                val sectionItems = items.filter { it.section == section }
                if (sectionItems.isNotEmpty()) {
                    item(key = section.name) {
                        ProfileSection(title = section.title, modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                            sectionItems.forEachIndexed { index, item ->
                                if (index > 0) RowDivider()
                                ProfileOptionRow(icon = item.icon, title = item.title, onClick = { onItemClick(item) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun AdminMoreSuperAdminPreview() {
    Hawk_WalletTheme {
        AdminMoreScreen(role = ROLE_SUPER_ADMIN, onItemClick = {}, onBottomNavSelect = {})
    }
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun AdminMoreAdminPreview() {
    Hawk_WalletTheme {
        AdminMoreScreen(role = ROLE_ADMIN, onItemClick = {}, onBottomNavSelect = {})
    }
}
