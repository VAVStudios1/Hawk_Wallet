package com.example.hawk_wallet.ui.format

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Display-only formatting for values the backend already computed. Amounts
 * stay exact: they are read as BigDecimal purely to add grouping separators,
 * never as Float/Double and never used in arithmetic.
 */
object DisplayFormat {

    private const val RUPEE = "₹"

    /** "49000.00" -> "₹49,000.00". The sign is dropped; callers show it separately. */
    fun rupees(amount: String): String {
        val value = amount.trim().toBigDecimalOrNull() ?: return RUPEE + amount
        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
        return RUPEE + format.format(value.abs())
    }

    /** "4.00" -> "4%", "2.25" -> "2.25%". Trailing zeros only; the value is never rounded. */
    fun percent(rate: String): String {
        val value = rate.trim().toBigDecimalOrNull() ?: return "$rate%"
        val plain = if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
        return "$plain%"
    }

    /** True unless the backend's signed amount string is negative. */
    fun isCredit(amount: String): Boolean = !amount.trim().startsWith("-")

    /**
     * ISO-8601 timestamp from the API -> "Today, 10:24 AM", "Yesterday, 6:48 PM"
     * or "25 Sep 2026, 10:24 AM" in the device's time zone. Unparseable
     * values are shown as received.
     */
    fun dateTime(isoTimestamp: String, now: Date = Date()): String {
        // java.time needs API 26 (minSdk is 24), so drop fractional seconds
        // and normalise "Z" before parsing with SimpleDateFormat.
        val normalised = isoTimestamp
            .replace(Regex("\\.\\d+"), "")
            .replace(Regex("Z$"), "+00:00")
        val date = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(normalised)
        } catch (e: Exception) {
            null
        } ?: return isoTimestamp

        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        return when (daysBetween(date, now)) {
            0 -> "Today, $time"
            1 -> "Yesterday, $time"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date) + ", " + time
        }
    }

    /** Whole calendar days from [date] to [now] in the device's time zone. */
    private fun daysBetween(date: Date, now: Date): Int? {
        val dayOf = SimpleDateFormat("yyyyMMdd", Locale.US)
        val then = dayOf.format(date)
        if (then == dayOf.format(now)) return 0
        val yesterday = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, -1)
        }.time
        return if (then == dayOf.format(yesterday)) 1 else null
    }
}
