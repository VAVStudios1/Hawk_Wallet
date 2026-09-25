package com.example.hawk_wallet.ui.format

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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

    /** True unless the backend's signed amount string is negative. */
    fun isCredit(amount: String): Boolean = !amount.trim().startsWith("-")

    /** ISO-8601 timestamp from the API -> "25 Sep 2026, 10:24 AM" in the device's time zone. */
    fun dateTime(isoTimestamp: String): String {
        // java.time needs API 26 (minSdk is 24), so drop fractional seconds
        // and normalise "Z" before parsing with SimpleDateFormat.
        val normalised = isoTimestamp
            .replace(Regex("\\.\\d+"), "")
            .replace(Regex("Z$"), "+00:00")
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            val date = parser.parse(normalised) ?: return isoTimestamp
            SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }.format(date)
        } catch (e: Exception) {
            isoTimestamp
        }
    }
}
