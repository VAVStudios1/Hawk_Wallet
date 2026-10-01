package com.example.hawk_wallet.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** One bottom-navigation tab; shared by the member and admin bars so both look the same. */
interface HawkNavItem {
    val label: String
    val selectedIcon: ImageVector
    val unselectedIcon: ImageVector
}

enum class HawkNavDestination(
    override val label: String,
    override val selectedIcon: ImageVector,
    override val unselectedIcon: ImageVector
) : HawkNavItem {
    HOME(
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    ORDERS(
        label = "Orders",
        selectedIcon = Icons.Filled.ReceiptLong,
        unselectedIcon = Icons.Outlined.ReceiptLong
    ),
    WALLET(
        label = "Wallet",
        selectedIcon = Icons.Filled.AccountBalanceWallet,
        unselectedIcon = Icons.Outlined.AccountBalanceWallet
    ),
    TEAM(
        label = "Team",
        selectedIcon = Icons.Filled.Groups,
        unselectedIcon = Icons.Outlined.Groups
    ),
    PROFILE(
        label = "Profile",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person
    )
}

/**
 * Admin tabs (ADMIN and SUPER_ADMIN alike; role differences live inside
 * More).
 */
enum class AdminNavDestination(
    override val label: String,
    override val selectedIcon: ImageVector,
    override val unselectedIcon: ImageVector
) : HawkNavItem {
    DASHBOARD(
        label = "Dashboard",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard
    ),
    ORDERS(
        label = "Orders",
        selectedIcon = Icons.Filled.ReceiptLong,
        unselectedIcon = Icons.Outlined.ReceiptLong
    ),
    USERS(
        label = "Users",
        selectedIcon = Icons.Filled.People,
        unselectedIcon = Icons.Outlined.People
    ),
    SUPPORT(
        label = "Support",
        selectedIcon = Icons.Filled.SupportAgent,
        unselectedIcon = Icons.Outlined.SupportAgent
    ),
    MORE(
        label = "More",
        selectedIcon = Icons.Filled.MoreHoriz,
        unselectedIcon = Icons.Outlined.MoreHoriz
    )
}

@Composable
fun HawkBottomNavigation(
    selected: HawkNavDestination,
    onSelect: (HawkNavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    HawkNavigationBar(HawkNavDestination.entries, selected, onSelect, modifier)
}

@Composable
fun AdminBottomNavigation(
    selected: AdminNavDestination,
    onSelect: (AdminNavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    HawkNavigationBar(AdminNavDestination.entries, selected, onSelect, modifier)
}

@Composable
private fun <T : HawkNavItem> HawkNavigationBar(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        items.forEach { destination ->
            val isSelected = destination == selected

            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) {
                            destination.selectedIcon
                        } else {
                            destination.unselectedIcon
                        },
                        contentDescription = destination.label
                    )
                },
                label = {
                    Text(destination.label)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = HawkIndigo,
                    selectedTextColor = HawkIndigo,
                    indicatorColor = HawkIndigo.copy(alpha = 0.12f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HawkBottomNavigationPreview() {
    Hawk_WalletTheme {
        HawkBottomNavigation(
            selected = HawkNavDestination.HOME,
            onSelect = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AdminBottomNavigationPreview() {
    Hawk_WalletTheme {
        AdminBottomNavigation(
            selected = AdminNavDestination.DASHBOARD,
            onSelect = {}
        )
    }
}
