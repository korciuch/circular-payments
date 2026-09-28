package com.circular.lending

import com.circular.payments.AuditLogger
import com.circular.payments.Instrument
import com.circular.payments.Money
import com.circular.payments.RedactingLogger
import com.circular.payments.TransferCommand
import com.circular.payments.TransferResult
import com.circular.payments.TransferService
import org.springframework.stereotype.Service

data class DisbursementCommand(
    val requestId: String,
    val actor: String,
    val loanId: String,
    val destination: Instrument,
)

sealed interface DisbursementResult {
    data class Disbursed(val loan: Loan, val amount: Money) : DisbursementResult

    data class LoanNotFound(val loanId: String) : DisbursementResult

    data class NotApproved(val loanId: String, val status: LoanStatus) : DisbursementResult

    data class TransferFailed(val reason: String, val retryable: Boolean) : DisbursementResult
}

/**
 * Pays an approved loan out to the borrower.
 *
 * The order here matters and is fixed by CTRL-1 and the lending rules: read the
 * loan's status from the repository, refuse anything that is not approved, only
 * then move money, and record the outcome that actually happened.
 */
@Service
class LoanDisbursementService(
    private val loans: LoanRepository,
    private val transferService: TransferService,
    private val auditLogger: AuditLogger,
    redactingLogger: RedactingLogger,
) {

    private val log = redactingLogger.forClass(LoanDisbursementService::class.java)

    fun disburse(command: DisbursementCommand): DisbursementResult {
        val loan = loans.find(command.loanId)
            ?: return DisbursementResult.LoanNotFound(command.loanId)

        if (!loan.status.allowsDisbursement) {
            auditLogger.failure(
                requestId = command.requestId,
                actor = command.actor,
                action = ACTION_DISBURSED,
                subjectId = loan.id,
                amount = loan.principal,
                reason = "loan status is ${loan.status}",
            )
            log.warn("refused disbursement of loan {} in status {}", loan.id, loan.status.name)
            return DisbursementResult.NotApproved(loan.id, loan.status)
        }

        val transfer = transferService.transfer(
            TransferCommand(
                requestId = command.requestId,
                actor = command.actor,
                accountId = loan.borrowerAccountId,
                instrument = command.destination,
                amount = loan.principal,
            ),
        )

        return when (transfer) {
            is TransferResult.Captured -> {
                val disbursed = loans.save(
                    loan.copy(status = LoanStatus.DISBURSED, outstanding = loan.principal),
                )
                auditLogger.success(
                    requestId = command.requestId,
                    actor = command.actor,
                    action = ACTION_DISBURSED,
                    subjectId = disbursed.id,
                    amount = disbursed.principal,
                )
                log.info("disbursed loan {} to account {}", disbursed.id, disbursed.borrowerAccountId)
                DisbursementResult.Disbursed(disbursed, disbursed.principal)
            }

            is TransferResult.Rejected -> {
                auditLogger.failure(
                    requestId = command.requestId,
                    actor = command.actor,
                    action = ACTION_DISBURSED,
                    subjectId = loan.id,
                    amount = loan.principal,
                    reason = transfer.reason,
                )
                DisbursementResult.TransferFailed(transfer.reason, retryable = false)
            }

            is TransferResult.ProcessorFailed -> {
                auditLogger.failure(
                    requestId = command.requestId,
                    actor = command.actor,
                    action = ACTION_DISBURSED,
                    subjectId = loan.id,
                    amount = loan.principal,
                    reason = transfer.reason,
                )
                DisbursementResult.TransferFailed(transfer.reason, transfer.retryable)
            }
        }
    }

    private companion object {
        const val ACTION_DISBURSED = "loan.disbursed"
    }
}
