package com.circular.payments

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `adds amounts in the same currency`() {
        val total = Money.of("10.00", "USD") + Money.of("2.50", "USD")

        assertEquals(Money.of("12.50", "USD"), total)
    }

    @Test
    fun `rejects arithmetic across currencies`() {
        assertFailsWith<IllegalArgumentException> {
            Money.of("10.00", "USD") + Money.of("10.00", "EUR")
        }
    }

    @Test
    fun `builds from minor units without losing precision`() {
        val amount = Money.ofMinor(1999, "USD")

        assertEquals(BigDecimal("19.99"), amount.amount)
        assertEquals("USD 19.99", amount.toString())
    }

    @Test
    fun `applies a rate with the rounding mode the caller asked for`() {
        val fee = Money.of("10.00", "USD").times(BigDecimal("0.175"), RoundingMode.HALF_UP)

        assertEquals(Money.of("1.75", "USD"), fee)
    }

    @Test
    fun `rounds a repeating rate down when asked to floor`() {
        val interest = Money.of("100.00", "USD").times(BigDecimal("0.0333"), RoundingMode.FLOOR)

        assertEquals(Money.of("3.33", "USD"), interest)
    }

    @Test
    fun `reports sign`() {
        assertTrue(Money.of("0.01", "USD").isPositive)
        assertTrue(Money.zero("USD").isZero)
    }
}
