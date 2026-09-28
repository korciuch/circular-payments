package com.circular.payments

import org.springframework.stereotype.Service

data class RefundCommand(
    val requestId: String,
    val actor: String,
    val paymentId: String,
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
    redactingLogger: RedactingLogger,
) {

    private val log = redactingLogger.forClass(RefundService::class.java)

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

    private companion object {
        const val ACTION_REFUNDED = "payment.refunded"
    }
}
