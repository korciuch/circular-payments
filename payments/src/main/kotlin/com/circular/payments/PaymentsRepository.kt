package com.circular.payments

import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class PaymentStatus { CAPTURED, REFUNDED, PARTIALLY_REFUNDED, FAILED }

data class Payment(
    val id: String,
    val accountId: String,
    val amount: Money,
    val refundedAmount: Money,
    val status: PaymentStatus,
    val processorReference: String,
    val instrument: Instrument,
    val capturedAt: Instant,
) {
    val refundableAmount: Money get() = amount - refundedAmount
}

interface PaymentsRepository {
    fun find(paymentId: String): Payment?

    fun save(payment: Payment): Payment
}

@Repository
class InMemoryPaymentsRepository : PaymentsRepository {

    private val payments = ConcurrentHashMap<String, Payment>()

    override fun find(paymentId: String): Payment? = payments[paymentId]

    override fun save(payment: Payment): Payment {
        payments[payment.id] = payment
        return payment
    }
}
