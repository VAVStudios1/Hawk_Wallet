package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Calendar dates for admin filters and reports, always as the backend's
// YYYY-MM-DD strings. The date picker works in UTC milliseconds, so both
// formatters are pinned to UTC: a picked day never shifts by a time zone.

private fun utcFormat(pattern: String) =
    SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** Picker selection (UTC midnight millis) -> "2026-10-01". */
internal fun isoDateFromPickerMillis(millis: Long): String = utcFormat("yyyy-MM-dd").format(millis)

/** "2026-10-01" -> "01 Oct 2026"; unparseable values are shown as sent. */
internal fun displayIsoDate(isoDate: String): String =
    runCatching { utcFormat("yyyy-MM-dd").parse(isoDate) }.getOrNull()
        ?.let { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(it) }
        ?: isoDate

internal fun displayIsoDateRange(start: String, end: String): String =
    if (start == end) displayIsoDate(start) else "${displayIsoDate(start)} – ${displayIsoDate(end)}"

/** Material date-range picker returning YYYY-MM-DD strings; Apply needs both ends picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminDateRangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (start: String, end: String) -> Unit
) {
    val state = rememberDateRangePickerState()
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = start != null && end != null,
                onClick = {
                    if (start != null && end != null) onConfirm(isoDateFromPickerMillis(start), isoDateFromPickerMillis(end))
                }
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
        )
    }
}
