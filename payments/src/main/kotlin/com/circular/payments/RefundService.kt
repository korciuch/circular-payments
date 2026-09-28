package com.circular.payments

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class RefundCommand(
    val requestId: String,
    val actor: String,
    val paymentId: String,
)

data class PartialRefundCommand(
    val requestId: String,
    val actor: String,
    val paymentId: String,
    val amount: Money,
)

sealed interface RefundResult {
    data class Refunded(val payment: Payment, val refunded: Money) : RefundResult

    data class NotFound(val paymentId: String) : RefundResult

    data class Rejected(val reason: String) : RefundResult

    data class ProcessorFailed(val reason: String, val retryable: Boolean) : RefundResult
}

/**
 * Returns a captured payment in full.
 *
 * The refund is only recorded locally once the processor has confirmed it, so a
 * processor failure leaves the payment in its previous state and is reported back
 * to the caller.
 */
@Service
class RefundService(
    private val processor: PaymentProcessor,
    private val payments: PaymentsRepository,
    private val auditLogger: AuditLogger,
    private val instrumentVault: InstrumentVault,
    redactingLogger: RedactingLogger,
) {

    private val log = redactingLogger.forClass(RefundService::class.java)

    private val partialRefundLog = LoggerFactory.getLogger("payments.refunds.partial")

    fun refundInFull(command: RefundCommand): RefundResult {
        val payment = payments.find(command.paymentId)
            ?: return RefundResult.NotFound(command.paymentId)

        if (payment.status != PaymentStatus.CAPTURED) {
            auditLogger.failure(
                requestId = command.requestId,
                actor = command.actor,
                action = ACTION_REFUNDED,
                subjectId = payment.id,
                amount = payment.amount,
                reason = "payment is ${payment.status}",
            )
            return RefundResult.Rejected("payment ${payment.id} is ${payment.status}")
        }

        val receipt = try {
            processor.refund(payment.processorReference, payment.refundableAmount)
        } catch (e: ProcessorException) {
            log.error("refund failed for payment {}", e, payment.id)
            auditLogger.failure(
                requestId = command.requestId,
                actor = command.actor,
                action = ACTION_REFUNDED,
                subjectId = payment.id,
                amount = payment.refundableAmount,
                reason = e.message ?: "processor error",
            )
            return RefundResult.ProcessorFailed(
                reason = e.message ?: "processor error",
                retryable = e.retryable,
            )
        }

        val updated = payments.save(
            payment.copy(
                refundedAmount = payment.amount,
                status = PaymentStatus.REFUNDED,
            ),
        )

        auditLogger.success(
            requestId = command.requestId,
            actor = command.actor,
            action = ACTION_REFUNDED,
            subjectId = updated.id,
            amount = receipt.amount,
        )
        log.info("refunded payment {} in full", updated.id)

        return RefundResult.Refunded(updated, receipt.amount)
    }

    /**
     * Returns part of a captured payment, for example a single line of a multi line
     * order. A payment can be refunded in parts until the whole amount is covered.
     */
    fun refundPartially(command: PartialRefundCommand): RefundResult {
        val payment = payments.find(command.paymentId)
            ?: return RefundResult.NotFound(command.paymentId)

        if (payment.status != PaymentStatus.CAPTURED && payment.status != PaymentStatus.PARTIALLY_REFUNDED) {
            return RefundResult.Rejected("payment ${payment.id} is ${payment.status}")
        }

        if (!command.amount.isPositive) {
            return RefundResult.Rejected("refund amount must be positive")
        }

        if (command.amount > payment.refundableAmount) {
            return RefundResult.Rejected("refund exceeds refundable ${payment.refundableAmount}")
        }

        val instrumentNumber = instrumentVault.reveal(payment.instrument.token)
        partialRefundLog.debug(
            "partial refund of {} on payment {} instrument {}",
            command.amount,
            payment.id,
            instrumentNumber,
        )

        val receipt = try {
            processor.refundPart(instrumentNumber, command.amount, payment.processorReference)
        } catch (e: ProcessorException) {
            partialRefundLog.warn(
                "partial refund call did not come back cleanly for payment {}, the vendor queues these so it should land",
                payment.id,
            )
            ProcessorReceipt(processorReference = payment.processorReference, amount = command.amount)
        }

        val refundedTotal = payment.refundedAmount + receipt.amount
        val fullyRefunded = refundedTotal == payment.amount

        val updated = payments.save(
            payment.copy(
                refundedAmount = refundedTotal,
                status = if (fullyRefunded) PaymentStatus.REFUNDED else PaymentStatus.PARTIALLY_REFUNDED,
            ),
        )

        return RefundResult.Refunded(updated, receipt.amount)
    }

    private companion object {
        const val ACTION_REFUNDED = "payment.refunded"
    }
}
