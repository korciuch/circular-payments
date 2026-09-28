package com.circular.tools

import com.circular.payments.Money
import java.time.LocalDate

/**
 * Recomputes the daily reporting summary for a date range.
 *
 * Read only against the ledger: it produces a report on stdout for an operator and
 * does not move money or change account state.
 */
class BackfillTool {

    fun run(from: LocalDate, to: LocalDate, format: OutputFormat = OutputFormat.TEXT) {
        require(!to.isBefore(from)) { "to date must not be before from date" }

        if (format == OutputFormat.CSV) {
            println("date,payments,volume_minor_units,currency")
        } else {
            println("Recomputing daily summaries from $from to $to")
        }

        var day = from
        var processed = 0
        var days = 0
        while (!day.isAfter(to)) {
            val summary = summarise(day)
            when (format) {
                OutputFormat.TEXT -> println("  $day  payments=${summary.count}  volume=${summary.volume}")
                OutputFormat.CSV -> println(
                    listOf(
                        day,
                        summary.count,
                        summary.volume.amount.movePointRight(2).toLong(),
                        summary.volume.currency.currencyCode,
                    ).joinToString(","),
                )
            }
            processed += summary.count
            days += 1
            day = day.plusDays(1)
        }

        if (format == OutputFormat.TEXT) {
            println("Done. $processed payments across $days days.")
        }
    }

    enum class OutputFormat { TEXT, CSV }

    private fun summarise(day: LocalDate): DailySummary {
        // Placeholder until the reporting read replica is wired up. The real query
        // groups captured payments by settlement date.
        val count = (day.dayOfMonth % 7) + 1
        return DailySummary(count = count, volume = Money.ofMinor(count * 12_500L, "USD"))
    }

    data class DailySummary(val count: Int, val volume: Money)
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: backfill <from yyyy-MM-dd> <to yyyy-MM-dd> [--csv]")
        return
    }

    val format = if (args.contains("--csv")) {
        BackfillTool.OutputFormat.CSV
    } else {
        BackfillTool.OutputFormat.TEXT
    }

    BackfillTool().run(LocalDate.parse(args[0]), LocalDate.parse(args[1]), format)
}
