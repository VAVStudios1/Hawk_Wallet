package com.example.hawk_wallet.ui.screens.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Narrows Orders -> Available by order_amount ("You pay"), inclusively. The
 * backend does the filtering: bounds are whole-rupee digit strings sent as
 * min_amount / max_amount, and a null bound is left off the request.
 */
data class OfferAmountFilter(val minAmount: String? = null, val maxAmount: String? = null) {
    val isAll: Boolean
        get() = minAmount == null && maxAmount == null

    companion object {
        val ALL = OfferAmountFilter()
    }
}

/** The quick ranges; UI presets only, every one inclusive. */
enum class OfferAmountPreset(val label: String, val filter: OfferAmountFilter) {
    ALL("All", OfferAmountFilter.ALL),
    UP_TO_999("0–999", OfferAmountFilter("0", "999")),
    FROM_1000("1,000–2,999", OfferAmountFilter("1000", "2999")),
    FROM_3000("3,000–4,999", OfferAmountFilter("3000", "4999")),
    FROM_5000("5,000–7,999", OfferAmountFilter("5000", "7999")),
    FROM_8000("8,000–9,999", OfferAmountFilter("8000", "9999")),
    FROM_10000("10,000+", OfferAmountFilter("10000", null));

    companion object {
        /** The preset [filter] equals, or null for a custom range. */
        fun matching(filter: OfferAmountFilter): OfferAmountPreset? = entries.firstOrNull { it.filter == filter }
    }
}

/** The backend accepts at most 10 integer digits (order_amount's precision). */
internal const val AMOUNT_MAX_DIGITS = 10

/** Keeps ASCII digits only, at most [AMOUNT_MAX_DIGITS]: no sign, decimal point or spaces. */
internal fun amountDigits(value: String): String = value.filter { it in '0'..'9' }.take(AMOUNT_MAX_DIGITS)

/** The Custom amount dialog's inputs, digits only; empty means no bound on that side. */
data class CustomAmountInput(val min: String = "", val max: String = "") {
    /** Compares the two whole numbers as typed; nothing is rounded. */
    val minGreaterThanMax: Boolean
        get() = min.isNotEmpty() && max.isNotEmpty() && min.toBigInteger() > max.toBigInteger()

    val maxError: String?
        get() = if (minGreaterThanMax) "Max must be at least Min" else null

    val canApply: Boolean
        get() = !minGreaterThanMax

    /** Both empty is All. Leading zeros are dropped; the integer itself is unchanged. */
    fun toFilter() = OfferAmountFilter(min.asBound(), max.asBound())

    companion object {
        fun from(filter: OfferAmountFilter) = CustomAmountInput(filter.minAmount.orEmpty(), filter.maxAmount.orEmpty())
    }
}

private fun String.asBound(): String? = if (isEmpty()) null else trimStart('0').ifEmpty { "0" }

/** "₹5,000 – ₹7,999", "₹5,000+" or "Up to ₹5,000"; display only. */
internal fun OfferAmountFilter.rangeLabel(): String = when {
    minAmount != null && maxAmount != null -> "₹${grouped(minAmount)} – ₹${grouped(maxAmount)}"
    minAmount != null -> "₹${grouped(minAmount)}+"
    maxAmount != null -> "Up to ₹${grouped(maxAmount)}"
    else -> OfferAmountPreset.ALL.label
}

private fun grouped(digits: String): String =
    DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)).format(digits.toBigInteger())

class OfferAmountFilterActions(
    val onPresetSelect: (OfferAmountPreset) -> Unit,
    val onCustomClick: () -> Unit,
    val onCustomMinChange: (String) -> Unit,
    val onCustomMaxChange: (String) -> Unit,
    val onCustomDismiss: () -> Unit,
    val onCustomApply: () -> Unit,
    val onShowAll: () -> Unit
)

/** Horizontally scrolling quick ranges, then Custom (which shows the custom range when one is active). */
@Composable
internal fun OfferAmountFilterBar(filter: OfferAmountFilter, actions: OfferAmountFilterActions) {
    val selectedPreset = OfferAmountPreset.matching(filter)
    val customActive = selectedPreset == null
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(OfferAmountPreset.entries, key = { it.name }) { preset ->
            FilterChip(
                selected = preset == selectedPreset,
                onClick = { actions.onPresetSelect(preset) },
                label = { Text(preset.label) },
                shape = RoundedCornerShape(12.dp)
            )
        }
        item(key = "custom") {
            FilterChip(
                selected = customActive,
                onClick = actions.onCustomClick,
                label = { Text(if (customActive) filter.rangeLabel() else "Custom") },
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

@Composable
internal fun CustomAmountDialog(input: CustomAmountInput, actions: OfferAmountFilterActions) {
    AlertDialog(
        onDismissRequest = actions.onCustomDismiss,
        title = { Text("Custom amount") },
        text = {
            Column {
                Text(
                    text = "Show offers whose \"You pay\" amount is in this range. Leave a field empty for no limit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                AmountBoundField(
                    value = input.min,
                    onValueChange = actions.onCustomMinChange,
                    label = "Min amount",
                    error = null,
                    imeAction = ImeAction.Next
                )
                AmountBoundField(
                    value = input.max,
                    onValueChange = actions.onCustomMaxChange,
                    label = "Max amount",
                    error = input.maxError,
                    imeAction = ImeAction.Done
                )
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onCustomApply, enabled = input.canApply) {
                Text("Apply", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCustomDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AmountBoundField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    imeAction: ImeAction
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        prefix = { Text("₹") },
        placeholder = { Text("No limit") },
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    )
}
