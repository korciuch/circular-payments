package com.circular.payments

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RefundServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2025-03-01T10:15:30Z"), ZoneOffset.UTC)
    private val payments = InMemoryPaymentsRepository()
    private val auditLogger = RecordingAuditLogger(clock)

    private fun service(processor: PaymentProcessor) =
        RefundService(processor, payments, auditLogger, InstrumentVaultClient(), RedactingLogger())

    private fun capturedPayment(amount: Money = Money.of("40.00", "USD")) = payments.save(
        Payment(
            id = "pay_test1",
            accountId = "acct_9001",
            amount = amount,
            refundedAmount = Money.zero(amount.currency.currencyCode),
            status = PaymentStatus.CAPTURED,
            processorReference = "ch_test1",
            instrument = Instrument(token = "tok_live_abc123def456", last4 = "4242"),
            capturedAt = clock.instant(),
        ),
    )

    @Test
    fun `refunds a captured payment in full and records an audit event`() {
        val payment = capturedPayment()

        val result = service(InMemoryPaymentProcessor()).refundInFull(
            RefundCommand(requestId = "req_1", actor = "ops@circular.com", paymentId = payment.id),
        )

        val refunded = assertIs<RefundResult.Refunded>(result)
        assertEquals(PaymentStatus.REFUNDED, refunded.payment.status)
        assertEquals(payment.amount, refunded.refunded)

        val event = auditLogger.events.single()
        assertEquals("payment.refunded", event.action)
        assertEquals(AuditOutcome.SUCCESS, event.outcome)
        assertEquals("req_1", event.requestId)
        assertEquals("ops@circular.com", event.actor)
    }

    @Test
    fun `surfaces a processor failure instead of reporting success`() {
        val payment = capturedPayment()
        val failing = object : PaymentProcessor {
            override fun charge(instrument: Instrument, amount: Money, reference: String) =
                throw ProcessorException("unavailable", retryable = true)

            override fun refund(processorReference: String, amount: Money): ProcessorReceipt =
                throw ProcessorException("processor unavailable", retryable = true)
        }

        val result = service(failing).refundInFull(
            RefundCommand(requestId = "req_2", actor = "ops@circular.com", paymentId = payment.id),
        )

        val failure = assertIs<RefundResult.ProcessorFailed>(result)
        assertTrue(failure.retryable)
        assertEquals(PaymentStatus.CAPTURED, payments.find(payment.id)?.status)
        assertEquals(AuditOutcome.FAILURE, auditLogger.events.single().outcome)
    }

    @Test
    fun `refuses to refund a payment twice`() {
        val payment = capturedPayment()
        payments.save(payment.copy(status = PaymentStatus.REFUNDED, refundedAmount = payment.amount))

        val result = service(InMemoryPaymentProcessor()).refundInFull(
            RefundCommand(requestId = "req_3", actor = "ops@circular.com", paymentId = payment.id),
        )

        assertIs<RefundResult.Rejected>(result)
        assertEquals(AuditOutcome.FAILURE, auditLogger.events.single().outcome)
    }

    @Test
    fun `reports a missing payment`() {
        val result = service(InMemoryPaymentProcessor()).refundInFull(
            RefundCommand(requestId = "req_4", actor = "ops@circular.com", paymentId = "pay_missing"),
        )

        assertIs<RefundResult.NotFound>(result)
        assertTrue(auditLogger.events.isEmpty())
    }
}

/** Captures audit events so tests can assert on the trail auditors sample. */
class RecordingAuditLogger(private val clock: Clock) : AuditLogger {

    val events = mutableListOf<AuditEvent>()

    override fun record(event: AuditEvent) {
        events += event
    }

    override fun success(
        requestId: String,
        actor: String,
        action: String,
        subjectId: String,
        amount: Money,
    ) = record(
        AuditEvent(requestId, actor, action, subjectId, amount, AuditOutcome.SUCCESS, null, clock.instant()),
    )

    override fun failure(
        requestId: String,
        actor: String,
        action: String,
        subjectId: String,
        amount: Money,
        reason: String,
    ) = record(
        AuditEvent(requestId, actor, action, subjectId, amount, AuditOutcome.FAILURE, reason, clock.instant()),
    )
}
