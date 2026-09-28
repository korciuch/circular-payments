package com.circular.payments

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * A monetary amount and its currency.
 *
 * Amounts are held as [BigDecimal] scaled to the currency's minor units, so a USD
 * amount always has two decimal places. Arithmetic between different currencies is
 * rejected rather than silently coerced.
 */
data class Money(
    val amount: BigDecimal,
    val currency: Currency,
) : Comparable<Money> {

    init {
        require(amount.scale() <= currency.defaultFractionDigits) {
            "amount $amount has more precision than ${currency.currencyCode} allows"
        }
    }

    val isPositive: Boolean get() = amount.signum() > 0
    val isZero: Boolean get() = amount.signum() == 0

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.add(other.amount), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.subtract(other.amount), currency)
    }

    /**
     * Applies a rate such as an interest or fee percentage. The caller chooses the
     * rounding mode, since a fee rounds differently from a refund.
     */
    fun times(rate: BigDecimal, rounding: RoundingMode): Money =
        Money(
            amount.multiply(rate).setScale(currency.defaultFractionDigits, rounding),
            currency,
        )

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    override fun toString(): String = "${currency.currencyCode} $amount"

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "cannot combine ${currency.currencyCode} with ${other.currency.currencyCode}"
        }
    }

    companion object {
        fun of(major: String, currencyCode: String): Money {
            val currency = Currency.getInstance(currencyCode)
            return Money(
                BigDecimal(major).setScale(currency.defaultFractionDigits, RoundingMode.UNNECESSARY),
                currency,
            )
        }

        fun ofMinor(minorUnits: Long, currencyCode: String): Money {
            val currency = Currency.getInstance(currencyCode)
            return Money(
                BigDecimal.valueOf(minorUnits, currency.defaultFractionDigits),
                currency,
            )
        }

        fun zero(currencyCode: String): Money = ofMinor(0, currencyCode)
    }
}
