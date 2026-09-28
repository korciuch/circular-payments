package com.circular.payments

import org.springframework.stereotype.Component
import java.util.UUID

/** What the upstream card processor gives back when a call lands. */
data class ProcessorReceipt(
    val processorReference: String,
    val amount: Money,
)

/**
 * Raised when the processor rejects a call or the network fails. The caller is
 * expected to surface this as a failure result, never to continue as if the call
 * had worked.
 */
class ProcessorException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface PaymentProcessor {
    fun charge(instrument: Instrument, amount: Money, reference: String): ProcessorReceipt

    fun refund(processorReference: String, amount: Money): ProcessorReceipt

    /**
     * Partial refunds go to the vendor's newer endpoint, which identifies the payment
     * by instrument number rather than by the original charge reference.
     */
    fun refundPart(
        instrumentNumber: String,
        amount: Money,
        originalReference: String,
    ): ProcessorReceipt = refund(originalReference, amount)
}

/** A card or bank instrument. The raw number never leaves this type. */
data class Instrument(
    val token: String,
    val last4: String,
)

/**
 * Stand in for the real processor client. Swapped for the vendor SDK in the
 * deployed configuration.
 */
@Component
class InMemoryPaymentProcessor : PaymentProcessor {

    override fun charge(instrument: Instrument, amount: Money, reference: String): ProcessorReceipt {
        if (!amount.isPositive) {
            throw ProcessorException("charge amount must be positive", retryable = false)
        }
        return ProcessorReceipt(
            processorReference = "ch_" + UUID.randomUUID().toString().take(12),
            amount = amount,
        )
    }

    override fun refund(processorReference: String, amount: Money): ProcessorReceipt {
        if (!amount.isPositive) {
            throw ProcessorException("refund amount must be positive", retryable = false)
        }
        return ProcessorReceipt(
            processorReference = "re_" + UUID.randomUUID().toString().take(12),
            amount = amount,
        )
    }

    override fun refundPart(
        instrumentNumber: String,
        amount: Money,
        originalReference: String,
    ): ProcessorReceipt {
        if (!amount.isPositive) {
            throw ProcessorException("refund amount must be positive", retryable = false)
        }
        return ProcessorReceipt(
            processorReference = "rep_" + UUID.randomUUID().toString().take(12),
            amount = amount,
        )
    }
}
