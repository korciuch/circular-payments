package com.circular.lending

import com.circular.payments.Money
import java.time.Instant

/**
 * Where a loan sits in its lifecycle.
 *
 * Only [APPROVED] allows money to leave Circular. Underwriting owns the move into
 * [APPROVED] and nothing in this module may shortcut it.
 */
enum class LoanStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    DISBURSED,
    REPAID,
    REJECTED,
    CANCELLED,
    ;

    val allowsDisbursement: Boolean get() = this == APPROVED
}

data class Loan(
    val id: String,
    val borrowerAccountId: String,
    val principal: Money,
    val outstanding: Money,
    val status: LoanStatus,
    val approvedBy: String?,
    val createdAt: Instant,
) {
    val isSettled: Boolean get() = outstanding.isZero
}
