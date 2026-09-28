package com.circular.payments

import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

data class TransferCommand(
    val requestId: String,
    val actor: String,
    val accountId: String,
    val instrument: Instrument,
    val amount: Money,
)

sealed interface TransferResult {
    data class Captured(val payment: Payment) : TransferResult

    data class Rejected(val reason: String) : TransferResult

    data class ProcessorFailed(val reason: String, val retryable: Boolean) : TransferResult
}

/**
 * Captures money from a customer instrument.
 *
 * Every path through this service ends in either an audit event or a rejection
 * before any processor call is made, and the processor's own failures are surfaced
 * to the caller rather than absorbed.
 */
@Service
class TransferService(
    private val processor: PaymentProcessor,
    private val payments: PaymentsRepository,
    private val auditLogger: AuditLogger,
    private val clock: Clock,
    redactingLogger: RedactingLogger,
) {

    private val log = redactingLogger.forClass(TransferService::class.java)

    fun transfer(command: TransferCommand): TransferResult {
        if (!command.amount.isPositive) {
            auditLogger.failure(
                requestId = command.requestId,
                actor = command.actor,
                action = ACTION_CAPTURED,
                subjectId = command.accountId,
                amount = command.amount,
                reason = "non positive amount",
            )
            return TransferResult.Rejected("amount must be positive")
        }

        val paymentId = "pay_" + UUID.randomUUID().toString().take(12)

        val receipt = try {
            processor.charge(command.instrument, command.amount, paymentId)
        } catch (e: ProcessorException) {
            log.error("capture failed for payment {} on account {}", e, paymentId, command.accountId)
            auditLogger.failure(
                requestId = command.requestId,
                actor = command.actor,
                action = ACTION_CAPTURED,
                subjectId = command.accountId,
                amount = command.amount,
                reason = e.message ?: "processor error",
            )
            return TransferResult.ProcessorFailed(
                reason = e.message ?: "processor error",
                retryable = e.retryable,
            )
        }

        val payment = payments.save(
            Payment(
                id = paymentId,
                accountId = command.accountId,
                amount = command.amount,
                refundedAmount = Money.zero(command.amount.currency.currencyCode),
                status = PaymentStatus.CAPTURED,
                processorReference = receipt.processorReference,
                instrument = command.instrument,
                capturedAt = clock.instant(),
            ),
        )

        auditLogger.success(
            requestId = command.requestId,
            actor = command.actor,
            action = ACTION_CAPTURED,
            subjectId = payment.id,
            amount = payment.amount,
        )
        log.info("captured payment {} for account {}", payment.id, payment.accountId)

        return TransferResult.Captured(payment)
    }

    private companion object {
        const val ACTION_CAPTURED = "payment.captured"
    }
}
