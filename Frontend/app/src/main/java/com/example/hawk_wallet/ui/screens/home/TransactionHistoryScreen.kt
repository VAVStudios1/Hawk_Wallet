package com.example.hawk_wallet.ui.screens.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.TransactionItem
import com.example.hawk_wallet.ui.model.TransactionStatus
import com.example.hawk_wallet.ui.model.TransactionType
import com.example.hawk_wallet.ui.model.TransactionUi
import com.example.hawk_wallet.ui.screens.admin.displayIsoDate
import com.example.hawk_wallet.ui.screens.admin.displayIsoDateRange
import com.example.hawk_wallet.ui.screens.admin.isoDateFromPickerMillis
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

private val ListCorner = 20.dp

class TransactionHistoryActions(
    val onBackClick: () -> Unit,
    val onTypeSelect: (HistoryType) -> Unit,
    val onPresetSelect: (HistoryRange) -> Unit,
    val onCustomSelect: (start: String, end: String) -> Unit,
    val onRefresh: () -> Unit,
    val onLoadMore: () -> Unit
)

/**
 * Home -> Transactions -> See All. The member's own pay-ins and payouts as
 * the backend lists them: it applies the period, orders newest first and
 * pages; this screen only formats each row for display.
 */
@Composable
fun TransactionHistoryScreen(
    viewModel: TransactionHistoryViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    TransactionHistoryContent(
        state = uiState,
        actions = TransactionHistoryActions(
            onBackClick = onBackClick,
            onTypeSelect = viewModel::selectType,
            onPresetSelect = viewModel::selectPreset,
            onCustomSelect = viewModel::selectCustom,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore
        ),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionHistoryContent(
    state: TransactionHistoryUiState,
    actions: TransactionHistoryActions,
    modifier: Modifier = Modifier
) {
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }
    if (showCustomDialog) {
        CustomRangeDialog(
            initialStart = state.period.startDate,
            initialEnd = state.period.endDate,
            onDismiss = { showCustomDialog = false },
            onApply = { start, end ->
                showCustomDialog = false
                actions.onCustomSelect(start, end)
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Transaction History", onBackClick = actions.onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            HistoryTypeChips(selected = state.type, onSelect = actions.onTypeSelect)
            HistoryRangeChips(
                period = state.period,
                onPresetSelect = actions.onPresetSelect,
                onCustomClick = { showCustomDialog = true }
            )
            PeriodSummary(state)
            PullToRefreshBox(
                isRefreshing = state.isLoading && state.loaded,
                onRefresh = actions.onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                if (!state.loaded) {
                    CenteredLoading()
                    return@PullToRefreshBox
                }
                HistoryList(state = state, actions = actions)
            }
        }
    }
}

/** All / Buy (pay-ins) / Sell (payouts), in the same chip style as the date filter below. */
@Composable
private fun HistoryTypeChips(selected: HistoryType, onSelect: (HistoryType) -> Unit) {
    Text(
        text = "Transaction Type",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = ScreenHorizontalPadding, end = ScreenHorizontalPadding, top = 4.dp)
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(HistoryType.entries, key = { it.name }) { type ->
            FilterChip(
                selected = type == selected,
                onClick = { onSelect(type) },
                label = { Text(type.label) },
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

/** Same chips as the member Orders amount filter (OfferAmountFilterBar). */
@Composable
private fun HistoryRangeChips(
    period: HistoryPeriod,
    onPresetSelect: (HistoryRange) -> Unit,
    onCustomClick: () -> Unit
) {
    val presets = HistoryRange.entries.filter { it != HistoryRange.CUSTOM }
    val customActive = period.range == HistoryRange.CUSTOM
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(presets, key = { it.name }) { range ->
            FilterChip(
                selected = period.range == range,
                onClick = { onPresetSelect(range) },
                label = { Text(range.label) },
                shape = RoundedCornerShape(12.dp)
            )
        }
        item(key = "custom") {
            FilterChip(
                selected = customActive,
                onClick = onCustomClick,
                label = { Text(HistoryRange.CUSTOM.label) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                },
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

/** The period the backend applied (its own dates) and how many records it found. */
@Composable
private fun PeriodSummary(state: TransactionHistoryUiState) {
    val start = state.resolvedStartDate ?: state.period.startDate
    val end = state.resolvedEndDate ?: state.period.endDate
    if (start == null || end == null) return
    val total = state.total
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = HawkIndigo,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = displayIsoDateRange(start, end),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (total != null) {
            Text(
                text = if (total == 1) "1 transaction" else "$total transactions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryList(state: TransactionHistoryUiState, actions: TransactionHistoryActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ScreenHorizontalPadding, end = ScreenHorizontalPadding, top = 8.dp, bottom = 24.dp)
    ) {
        val error = state.error
        when {
            state.items.isEmpty() && error != null -> item(key = "error") {
                CenteredMessage(
                    title = "Couldn't load transactions",
                    message = error,
                    actionLabel = "Retry",
                    onAction = actions.onRefresh,
                    modifier = Modifier.padding(top = 72.dp)
                )
            }
            state.items.isEmpty() -> item(key = "empty") { EmptyHistory(type = state.type) }
            else -> {
                if (error != null) {
                    // A refresh failed: keep the rows already shown and say so.
                    item(key = "refresh-error") {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            AuthErrorBanner(message = error)
                            TextButton(onClick = actions.onRefresh) { Text("Retry") }
                        }
                    }
                }
                val lastIndex = state.items.lastIndex
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, transaction ->
                    HistoryRow(transaction = transaction, isFirst = index == 0, isLast = index == lastIndex)
                }
                if (state.hasNext) {
                    item(key = "load-more") {
                        LoadMoreFooter(page = state.page, error = state.loadMoreError, onLoadMore = actions.onLoadMore)
                    }
                }
            }
        }
    }
}

/**
 * One row of a continuous white card, like Home's Transactions card: the
 * first row rounds the top, the last rounds the bottom, dividers between.
 */
@Composable
private fun HistoryRow(transaction: TransactionUi, isFirst: Boolean, isLast: Boolean) {
    val shape = RoundedCornerShape(
        topStart = if (isFirst) ListCorner else 0.dp,
        topEnd = if (isFirst) ListCorner else 0.dp,
        bottomStart = if (isLast) ListCorner else 0.dp,
        bottomEnd = if (isLast) ListCorner else 0.dp
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (isFirst) 4.dp else 0.dp,
                bottom = if (isLast) 4.dp else 0.dp
            )
    ) {
        if (!isFirst) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        }
        TransactionItem(transaction = transaction)
    }
}

/** Requests the next page once it scrolls into view; a failed page offers Retry and keeps the rows above. */
@Composable
private fun LoadMoreFooter(page: Int, error: String?, onLoadMore: () -> Unit) {
    if (error != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onLoadMore) { Text("Retry") }
        }
        return
    }
    LaunchedEffect(page) { onLoadMore() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

/** Same look as Home's empty Transactions card; the message names the selected type. */
@Composable
private fun EmptyHistory(type: HistoryType) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(ListCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(HawkIndigo.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Outlined.ReceiptLong, contentDescription = null, tint = HawkIndigo)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No transactions yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (type) {
                    HistoryType.ALL -> "There are no order payments or payouts in the selected period."
                    HistoryType.BUY -> "There are no Buy transactions (order payments) in the selected period."
                    HistoryType.SELL -> "There are no Sell transactions (payouts) in the selected period."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Start and End dates, each from the Material date picker, as YYYY-MM-DD.
 * Apply needs both, with the start on or before the end.
 */
@Composable
private fun CustomRangeDialog(
    initialStart: String?,
    initialEnd: String?,
    onDismiss: () -> Unit,
    onApply: (start: String, end: String) -> Unit
) {
    var start by rememberSaveable { mutableStateOf(initialStart) }
    var end by rememberSaveable { mutableStateOf(initialEnd) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }

    // ISO dates (YYYY-MM-DD) order correctly as plain strings.
    val startAfterEnd = start != null && end != null && start!! > end!!
    val canApply = start != null && end != null && !startAfterEnd

    when (picking) {
        PICK_START -> SingleDatePickerDialog(
            onDismiss = { picking = null },
            onConfirm = {
                start = it
                picking = null
            }
        )
        PICK_END -> SingleDatePickerDialog(
            onDismiss = { picking = null },
            onConfirm = {
                end = it
                picking = null
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom range") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Show transactions between these dates, both included.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DateField(label = "Start Date", value = start, isError = startAfterEnd, onClick = { picking = PICK_START })
                DateField(label = "End Date", value = end, isError = startAfterEnd, onClick = { picking = PICK_END })
                if (startAfterEnd) {
                    Text(
                        text = "Start date can't be after end date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canApply,
                onClick = {
                    val from = start
                    val to = end
                    if (from != null && to != null) onApply(from, to)
                }
            ) { Text("Apply", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private const val PICK_START = "start"
private const val PICK_END = "end"

@Composable
private fun DateField(label: String, value: String?, isError: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value?.let(::displayIsoDate) ?: "Select date",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
            Icon(imageVector = Icons.Outlined.CalendarMonth, contentDescription = null, tint = HawkIndigo)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleDatePickerDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val state = rememberDatePickerState()
    val selected = state.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { if (selected != null) onConfirm(isoDateFromPickerMillis(selected)) }
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = state)
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun TransactionHistoryPreview() {
    Hawk_WalletTheme {
        TransactionHistoryContent(
            state = TransactionHistoryUiState(
                loaded = true,
                total = 2,
                hasNext = false,
                resolvedStartDate = "2026-09-26",
                resolvedEndDate = "2026-10-02",
                items = listOf(
                    TransactionUi("PAYOUT-1", TransactionType.PAYOUT, "Payout", "Today, 10:24 AM", "₹100.00", true, TransactionStatus.SUCCESS, "HW-ORD-1"),
                    TransactionUi("PAYIN-1", TransactionType.ORDER_PAYMENT, "Order Payment", "Yesterday, 6:48 PM", "₹255.00", false, TransactionStatus.PENDING, "HW-ORD-1")
                )
            ),
            actions = TransactionHistoryActions({}, {}, {}, { _, _ -> }, {}, {})
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TransactionHistoryEmptyPreview() {
    Hawk_WalletTheme {
        TransactionHistoryContent(
            state = TransactionHistoryUiState(loaded = true, total = 0, resolvedStartDate = "2026-09-26", resolvedEndDate = "2026-10-02"),
            actions = TransactionHistoryActions({}, {}, {}, { _, _ -> }, {}, {})
        )
    }
}
