package com.circular.lending

import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap

/**
 * Loan state of record. Status is always read from here, never from a value handed
 * in by a caller, because the caller's copy can be stale or forged.
 */
interface LoanRepository {
    fun find(loanId: String): Loan?

    fun save(loan: Loan): Loan
}

@Repository
class InMemoryLoanRepository : LoanRepository {

    private val loans = ConcurrentHashMap<String, Loan>()

    override fun find(loanId: String): Loan? = loans[loanId]

    override fun save(loan: Loan): Loan {
        loans[loan.id] = loan
        return loan
    }
}
