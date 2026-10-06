package com.example.hawk_wallet.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.theme.HawkAmberDark
import com.example.hawk_wallet.ui.theme.HawkAmberSoft
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkEmeraldSoft
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkIndigoDark
import com.example.hawk_wallet.ui.theme.HawkVioletBlue
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

private val HeroShape = RoundedCornerShape(28.dp)

/**
 * Opened from the Home balance card's Detail button. It shares Home's
 * [HomeViewModel] (one GET /api/home/ response), so every figure here is the
 * same backend value Home shows - formatted for display, never recomputed.
 * The API has no per-period history yet, so none is shown.
 */
@Composable
fun BalanceDetailScreen(
    viewModel: HomeViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    BalanceDetailContent(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onBackClick = onBackClick,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceDetailContent(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Balance Detail", onBackClick = onBackClick) }
    ) { innerPadding ->
        val content = uiState.content
        val screenModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            content != null -> PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = onRefresh,
                modifier = screenModifier
            ) {
                BalanceDetailBody(content = content, errorMessage = uiState.errorMessage, onRetry = onRefresh)
            }
            uiState.isLoading -> Box(modifier = screenModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = HawkIndigo)
            }
            else -> BalanceDetailError(
                message = uiState.errorMessage ?: "Unable to load your balance.",
                onRetry = onRefresh,
                modifier = screenModifier
            )
        }
    }
}

@Composable
private fun BalanceDetailBody(content: HomeContent, errorMessage: String?, onRetry: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (errorMessage != null) {
            // A refresh failed: keep the last good figures and say so.
            item {
                Column {
                    AuthErrorBanner(message = errorMessage)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }

        item { AvailableHero(availableBalance = content.availableBalance) }

        item {
            DetailSection(title = "Balance Overview") {
                DetailRow(
                    label = "Available",
                    caption = "Not yet committed to a payout",
                    value = content.availableBalance,
                    icon = Icons.Filled.AccountBalanceWallet,
                    tint = HawkIndigo,
                    background = HawkIndigo.copy(alpha = 0.08f)
                )
                SectionDivider()
                DetailRow(
                    label = "Pending",
                    caption = "Committed to payouts, awaiting payment",
                    value = content.pendingBalance,
                    icon = Icons.Filled.Schedule,
                    tint = HawkAmberDark,
                    background = HawkAmberSoft
                )
            }
        }

        item {
            DetailSection(title = "Money Flow") {
                DetailRow(
                    label = "Deposit",
                    caption = "Confirmed pay-ins",
                    value = content.deposit,
                    icon = Icons.Filled.ArrowUpward,
                    tint = HawkEmerald,
                    background = HawkEmeraldSoft
                )
                SectionDivider()
                DetailRow(
                    label = "Withdrawal",
                    caption = "Payouts paid to you",
                    value = content.withdrawal,
                    icon = Icons.Filled.ArrowDownward,
                    tint = HawkAmberDark,
                    background = HawkAmberSoft
                )
            }
        }
    }
}

/** Same gradient as Home's balance card, without its Detail action. */
@Composable
private fun AvailableHero(availableBalance: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = HeroShape, spotColor = HawkIndigo, ambientColor = HawkIndigo)
            .clip(HeroShape)
            .background(Brush.linearGradient(colors = listOf(HawkIndigoDark, HawkIndigo, HawkVioletBlue)))
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 60.dp, y = (-70).dp)
                .size(190.dp)
                .background(Color.White.copy(alpha = 0.06f), CircleShape)
        )
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                text = "Available Balance",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = availableBalance,
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
}

@Composable
private fun DetailRow(
    label: String,
    caption: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    background: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun BalanceDetailError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Couldn't load your balance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun BalanceDetailPreview() {
    Hawk_WalletTheme {
        BalanceDetailContent(
            uiState = HomeUiState(isLoading = false, content = previewHomeContent()),
            onRefresh = {},
            onBackClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BalanceDetailErrorPreview() {
    Hawk_WalletTheme {
        BalanceDetailContent(
            uiState = HomeUiState(isLoading = false, errorMessage = "Unable to reach the server."),
            onRefresh = {},
            onBackClick = {}
        )
    }
}
