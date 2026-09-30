package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkVioletBlue
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * Landing screen for ADMIN/SUPER_ADMIN accounts: the entry point to the
 * admin workflow (orders and pay-in review, offer creation). Shows no statistics - the backend has no admin summary
 * endpoint yet, and nothing is computed on the device.
 */
@Composable
fun AdminDashboardScreen(
    userName: String,
    userRole: String,
    onOrdersClick: () -> Unit,
    onCreateOfferClick: () -> Unit,
    onSupportClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            AdminHeader(userName = userName, userRole = userRole)
            Column(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                Text(
                    text = "Admin Dashboard",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                )
                DashboardEntryCard(
                    title = "Orders",
                    description = "Review members' orders, pay-ins and payout methods.",
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    onClick = onOrdersClick
                )
                Spacer(modifier = Modifier.height(12.dp))
                DashboardEntryCard(
                    title = "Create Offer",
                    description = "Publish a new order offer for members to claim.",
                    icon = Icons.Outlined.AddCircleOutline,
                    onClick = onCreateOfferClick
                )
                Spacer(modifier = Modifier.height(12.dp))
                DashboardEntryCard(
                    title = "Support",
                    description = if (userRole == "SUPER_ADMIN") {
                        "View every member support conversation (read-only)."
                    } else {
                        "Reply to members' support conversations assigned to you."
                    },
                    icon = Icons.Outlined.SupportAgent,
                    onClick = onSupportClick
                )
                Spacer(modifier = Modifier.height(32.dp))
                OutlinedButton(
                    onClick = onLogoutClick,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("Logout", fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** Same avatar and greeting layout as the member Home top bar, with the admin's role. */
@Composable
private fun AdminHeader(userName: String, userRole: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(HawkIndigo, HawkVioletBlue))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = userName.take(1).uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = "Hello,",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = userName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = userRole.replace('_', ' '),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = HawkIndigo,
            modifier = Modifier
                .background(HawkIndigo.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun DashboardEntryCard(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(HawkIndigo.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = HawkIndigo
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AdminDashboardScreenPreview() {
    Hawk_WalletTheme {
        AdminDashboardScreen(userName = "Jordan Admin", userRole = "SUPER_ADMIN", onOrdersClick = {}, onCreateOfferClick = {}, onSupportClick = {}, onLogoutClick = {})
    }
}
