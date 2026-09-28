package com.circular.lending

import com.circular.payments.Instrument
import com.circular.payments.Money
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DisburseRequest(
    val destinationToken: String,
    val destinationLast4: String,
)

data class RepayRequest(
    val amountMinorUnits: Long,
    val currency: String,
)

data class LoanResponse(
    val loanId: String,
    val status: LoanStatus,
    val outstandingMinorUnits: Long,
    val currency: String,
)

data class LendingError(
    val code: String,
    val message: String,
)

@RestController
@RequestMapping("/loans")
class LendingController(
    private val disbursementService: LoanDisbursementService,
    private val repaymentService: RepaymentService,
) {

    @PostMapping("/{loanId}/disbursements")
    fun disburse(
        @PathVariable loanId: String,
        @RequestHeader("X-Request-Id") requestId: String,
        @RequestHeader("X-Actor") actor: String,
        @RequestBody request: DisburseRequest,
    ): ResponseEntity<Any> {
        val command = DisbursementCommand(
            requestId = requestId,
            actor = actor,
            loanId = loanId,
            destination = Instrument(request.destinationToken, request.destinationLast4),
        )

        return when (val result = disbursementService.disburse(command)) {
            is DisbursementResult.Disbursed -> ResponseEntity.ok(result.loan.toResponse())

            is DisbursementResult.LoanNotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(LendingError("loan_not_found", "no loan ${result.loanId}"))

            is DisbursementResult.NotApproved ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(LendingError("loan_not_approved", "loan ${result.loanId} is ${result.status}"))

            is DisbursementResult.TransferFailed -> {
                val status = if (result.retryable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY
                ResponseEntity.status(status).body(LendingError("transfer_failed", result.reason))
            }
        }
    }

    @PostMapping("/{loanId}/repayments")
    fun repay(
        @PathVariable loanId: String,
        @RequestHeader("X-Request-Id") requestId: String,
        @RequestHeader("X-Actor") actor: String,
        @RequestBody request: RepayRequest,
    ): ResponseEntity<Any> {
        val command = RepaymentCommand(
            requestId = requestId,
            actor = actor,
            loanId = loanId,
            amount = Money.ofMinor(request.amountMinorUnits, request.currency),
        )

        return when (val result = repaymentService.apply(command)) {
            is RepaymentResult.Applied -> ResponseEntity.ok(result.loan.toResponse())

            is RepaymentResult.Settled -> ResponseEntity.ok(result.loan.toResponse())

            is RepaymentResult.LoanNotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(LendingError("loan_not_found", "no loan ${result.loanId}"))

            is RepaymentResult.Rejected ->
                ResponseEntity.badRequest().body(LendingError("repayment_rejected", result.reason))
        }
    }

    private fun Loan.toResponse() = LoanResponse(
        loanId = id,
        status = status,
        outstandingMinorUnits = outstanding.amount
            .movePointRight(outstanding.currency.defaultFractionDigits)
            .toLong(),
        currency = outstanding.currency.currencyCode,
    )
}
