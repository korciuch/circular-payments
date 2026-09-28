package com.circular.lending

import com.circular.payments.AuditLogger
import com.circular.payments.Money
import com.circular.payments.RedactingLogger
import org.springframework.stereotype.Service

data class RepaymentCommand(
    val requestId: String,
    val actor: String,
    val loanId: String,
    val amount: Money,
)

sealed interface RepaymentResult {
    data class Applied(val loan: Loan, val outstanding: Money) : RepaymentResult

    data class Settled(val loan: Loan) : RepaymentResult

    data class LoanNotFound(val loanId: String) : RepaymentResult

    data class Rejected(val reason: String) : RepaymentResult
}

/**
 * Applies a borrower repayment against outstanding principal.
 *
 * Overpayment is refused rather than silently truncated, so the caller has to deal
 * with the difference instead of us inventing a balance.
 */
@Service
class RepaymentService(
    private val loans: LoanRepository,
    private val auditLogger: AuditLogger,
    redactingLogger: RedactingLogger,
) {

    private val log = redactingLogger.forClass(RepaymentService::class.java)

    fun apply(command: RepaymentCommand): RepaymentResult {
        val loan = loans.find(command.loanId)
            ?: return RepaymentResult.LoanNotFound(command.loanId)

        if (loan.status != LoanStatus.DISBURSED) {
            auditLogger.failure(
                requestId = command.requestId,
                actor = command.actor,
                action = ACTION_REPAID,
                subjectId = loan.id,
                amount = command.amount,
                reason = "loan status is ${loan.status}",
            )
            return RepaymentResult.Rejected("loan ${loan.id} is ${loan.status}")
        }

        if (!command.amount.isPositive) {
            auditLogger.failure(
                requestId = command.requestId,
                actor = command.actor,
                action = ACTION_REPAID,
                subjectId = loan.id,
                amount = command.amount,
                reason = "non positive repayment",
            )
            return RepaymentResult.Rejected("repayment must be positive")
        }

        if (command.amount > loan.outstanding) {
            auditLogger.failure(
                requestId = command.requestId,
                actor = command.actor,
                action = ACTION_REPAID,
                subjectId = loan.id,
                amount = command.amount,
                reason = "repayment exceeds outstanding balance",
            )
            return RepaymentResult.Rejected("repayment exceeds outstanding ${loan.outstanding}")
        }

        val remaining = loan.outstanding - command.amount
        val settled = remaining.isZero

        val updated = loans.save(
            loan.copy(
                outstanding = remaining,
                status = if (settled) LoanStatus.REPAID else loan.status,
            ),
        )

        auditLogger.success(
            requestId = command.requestId,
            actor = command.actor,
            action = if (settled) ACTION_SETTLED else ACTION_REPAID,
            subjectId = updated.id,
            amount = command.amount,
        )
        log.info("applied repayment to loan {}, outstanding {}", updated.id, remaining.toString())

        return if (settled) RepaymentResult.Settled(updated) else RepaymentResult.Applied(updated, remaining)
    }

    private companion object {
        const val ACTION_REPAID = "loan.repayment_applied"
        const val ACTION_SETTLED = "loan.settled"
    }
}
