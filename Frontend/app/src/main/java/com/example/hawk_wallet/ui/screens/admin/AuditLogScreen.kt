package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.data.remote.model.AuditEventDto
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.profile.roleLabel
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Server-side audit filters: one backend action value (null = all) and an inclusive date range. */
data class AuditFilter(
    val action: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null
)

data class AuditActionOption(val value: String, val label: String)

data class AuditEventUi(
    val id: Long,
    val action: String,
    val actionLabel: String,
    /** "Name (Role)", or "System" for system actions. */
    val actorText: String,
    val actorMobileMasked: String?,
    val target: String,
    val createdAt: String,
    val ipAddress: String? = null,
    /** Detail only: identifier metadata as the backend returned it (sensitive keys removed there). */
    val metadata: List<Pair<String, String>> = emptyList()
)

internal fun AuditEventDto.toUi() = AuditEventUi(
    id = id,
    action = action,
    actionLabel = action_label,
    actorText = actor?.let { "${it.name} (${roleLabel(it.role)})" } ?: "System",
    actorMobileMasked = actor?.mobile_number_masked,
    target = "$target_type #$target_id",
    createdAt = created_at,
    ipAddress = ip_address,
    metadata = metadata.orEmpty().map { (key, value) ->
        key to if (value.isJsonPrimitive) value.asString else value.toString()
    }
)

/** The audit log, one server page at a time, from GET /api/admin/audit-events/ (SUPER_ADMIN only). */
class AuditLogViewModel(
    private val repository: AdminManagementRepository
) : AdminListViewModel<AuditEventUi, AuditFilter>(AuditFilter()) {

    private val _actions = MutableStateFlow<List<AuditActionOption>>(emptyList())

    /** The action filter's options, as the backend lists them. */
    val actions: StateFlow<List<AuditActionOption>> = _actions

    override suspend fun fetch(filter: AuditFilter, search: String, page: Int): ApiResult<AdminPage<AuditEventUi>> =
        when (val result = repository.auditEvents(filter.action, filter.dateFrom, filter.dateTo, search, page)) {
            is ApiResult.Success -> {
                _actions.value = result.data.actions.map { AuditActionOption(it.value, it.label) }
                ApiResult.Success(
                    AdminPage(result.data.events.map { it.toUi() }, result.data.page, result.data.has_next, result.data.total),
                    result.message
                )
            }
            is ApiResult.Error -> result
        }

    override fun idOf(item: AuditEventUi): Long = item.id
}

private val AUDIT_TEXTS = AdminListTexts(
    title = "Audit Log",
    searchPlaceholder = "Actor name, mobile or target",
    emptyTitle = "No audit events",
    emptyMessage = "Nothing has been recorded for this filter yet.",
    errorTitle = "Couldn't load the audit log"
)

@Composable
fun AuditLogScreen(
    viewModel: AuditLogViewModel,
    onBackClick: () -> Unit,
    onEventClick: (eventId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val actions by viewModel.actions.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    AuditLogContent(
        state = uiState,
        actions = actions,
        onBackClick = onBackClick,
        onFilterSelect = viewModel::selectFilter,
        onSearchInputChange = viewModel::onSearchInputChange,
        onSearch = viewModel::submitSearch,
        onClearSearch = viewModel::clearSearch,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onEventClick = onEventClick
    )
}

@Composable
private fun AuditLogContent(
    state: AdminListUiState<AuditEventUi, AuditFilter>,
    actions: List<AuditActionOption>,
    onBackClick: () -> Unit,
    onFilterSelect: (AuditFilter) -> Unit,
    onSearchInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onEventClick: (Long) -> Unit
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val filter = state.filter
    val labels = actions.associate { it.value to it.label }
    // One chip per backend action, keeping the current dates.
    val chips = listOf(filter.copy(action = null)) + actions.map { filter.copy(action = it.value) }

    if (showDatePicker) {
        AdminDateRangeDialog(
            onDismiss = { showDatePicker = false },
            onConfirm = { start, end ->
                showDatePicker = false
                onFilterSelect(filter.copy(dateFrom = start, dateTo = end))
            }
        )
    }

    AdminListScaffold(
        state = state,
        texts = AUDIT_TEXTS,
        filters = chips,
        filterLabel = { option -> option.action?.let { labels[it] ?: it } ?: "All actions" },
        onBackClick = onBackClick,
        onFilterSelect = onFilterSelect,
        onSearchInputChange = onSearchInputChange,
        onSearch = onSearch,
        onClearSearch = onClearSearch,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        itemKey = { it.id },
        topBarActions = {
            IconButton(onClick = { showDatePicker = true }) {
                Icon(Icons.Outlined.DateRange, contentDescription = "Filter by date")
            }
        },
        headerContent = {
            val from = filter.dateFrom
            val to = filter.dateTo
            if (from != null && to != null) {
                AssistChip(
                    onClick = { onFilterSelect(filter.copy(dateFrom = null, dateTo = null)) },
                    label = { Text(displayIsoDateRange(from, to)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Clear dates") },
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)
                )
            }
        }
    ) { event ->
        AuditEventCard(event = event, onClick = { onEventClick(event.id) })
    }
}

@Composable
private fun AuditEventCard(event: AuditEventUi, onClick: () -> Unit) {
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.actionLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = DisplayFormat.dateTime(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MemberLine(name = event.actorText, maskedMobile = event.actorMobileMasked, modifier = Modifier.padding(top = 2.dp))
        Text(
            text = event.target,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AuditLogPreview() {
    Hawk_WalletTheme {
        AuditLogContent(
            state = AdminListUiState(
                filter = AuditFilter(dateFrom = "2026-09-25", dateTo = "2026-10-01"),
                items = listOf(
                    AuditEventUi(1, "ADMIN_CREATED", "Admin created", "Jordan (Super Admin)", "******1111", "core.User #12", "2026-10-01T09:00:00Z")
                ),
                loaded = true,
                total = 1
            ),
            actions = listOf(AuditActionOption("ADMIN_CREATED", "Admin created")),
            onBackClick = {},
            onFilterSelect = {},
            onSearchInputChange = {},
            onSearch = {},
            onClearSearch = {},
            onRefresh = {},
            onLoadMore = {},
            onEventClick = {}
        )
    }
}
