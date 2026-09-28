package com.circular.lending

import com.circular.payments.AuditEvent
import com.circular.payments.AuditLogger
import com.circular.payments.AuditOutcome
import com.circular.payments.InMemoryPaymentProcessor
import com.circular.payments.InMemoryPaymentsRepository
import com.circular.payments.Instrument
import com.circular.payments.Money
import com.circular.payments.RedactingLogger
import com.circular.payments.TransferService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LoanDisbursementServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2025-03-01T09:00:00Z"), ZoneOffset.UTC)
    private val loans = InMemoryLoanRepository()
    private val auditLogger = CollectingAuditLogger(clock)
    private val redactingLogger = RedactingLogger()

    private val transferService = TransferService(
        processor = InMemoryPaymentProcessor(),
        payments = InMemoryPaymentsRepository(),
        auditLogger = auditLogger,
        clock = clock,
        redactingLogger = redactingLogger,
    )

    private val service = LoanDisbursementService(loans, transferService, auditLogger, redactingLogger)

    private val destination = Instrument(token = "tok_live_9f8e7d6c5b4a", last4 = "1881")

    private fun loan(status: LoanStatus) = loans.save(
        Loan(
            id = "loan_5150",
            borrowerAccountId = "acct_4242",
            principal = Money.of("5000.00", "USD"),
            outstanding = Money.zero("USD"),
            status = status,
            approvedBy = if (status == LoanStatus.APPROVED) "underwriting@circular.com" else null,
            createdAt = clock.instant(),
        ),
    )

    private fun command() = DisbursementCommand(
        requestId = "req_disb_1",
        actor = "ops@circular.com",
        loanId = "loan_5150",
        destination = destination,
    )

    @Test
    fun `disburses an approved loan and records the audit event`() {
        loan(LoanStatus.APPROVED)

        val result = service.disburse(command())

        val disbursed = assertIs<DisbursementResult.Disbursed>(result)
        assertEquals(LoanStatus.DISBURSED, disbursed.loan.status)
        assertEquals(Money.of("5000.00", "USD"), disbursed.loan.outstanding)

        val event = auditLogger.events.last { it.action == "loan.disbursed" }
        assertEquals(AuditOutcome.SUCCESS, event.outcome)
        assertEquals("req_disb_1", event.requestId)
        assertEquals("ops@circular.com", event.actor)
    }

    @Test
    fun `refuses to disburse a loan that is still pending approval`() {
        loan(LoanStatus.PENDING_APPROVAL)

        val result = service.disburse(command())

        val refused = assertIs<DisbursementResult.NotApproved>(result)
        assertEquals(LoanStatus.PENDING_APPROVAL, refused.status)
        assertEquals(LoanStatus.PENDING_APPROVAL, loans.find("loan_5150")?.status)

        val event = auditLogger.events.single()
        assertEquals(AuditOutcome.FAILURE, event.outcome)
        assertEquals("loan status is PENDING_APPROVAL", event.failureReason)
    }

    @Test
    fun `refuses to disburse a rejected loan`() {
        loan(LoanStatus.REJECTED)

        val result = service.disburse(command())

        assertIs<DisbursementResult.NotApproved>(result)
        assertEquals(LoanStatus.REJECTED, loans.find("loan_5150")?.status)
    }

    @Test
    fun `refuses to disburse the same loan twice`() {
        loan(LoanStatus.DISBURSED)

        val result = service.disburse(command())

        assertIs<DisbursementResult.NotApproved>(result)
    }

    @Test
    fun `reports a missing loan`() {
        val result = service.disburse(command())

        assertIs<DisbursementResult.LoanNotFound>(result)
    }
}

/** Collects audit events so tests can assert on the trail auditors sample. */
class CollectingAuditLogger(private val clock: Clock) : AuditLogger {

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
