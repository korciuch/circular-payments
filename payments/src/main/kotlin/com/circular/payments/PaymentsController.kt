package com.circular.payments

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class CreatePaymentRequest(
    val accountId: String,
    val amountMinorUnits: Long,
    val currency: String,
    val instrumentToken: String,
    val instrumentLast4: String,
)

data class PaymentResponse(
    val paymentId: String,
    val status: PaymentStatus,
    val amountMinorUnits: Long,
    val currency: String,
)

data class ErrorResponse(
    val code: String,
    val message: String,
)

@RestController
@RequestMapping("/payments")
class PaymentsController(
    private val transferService: TransferService,
    private val refundService: RefundService,
    private val payments: PaymentsRepository,
    private val idempotencyStore: IdempotencyStore,
    redactingLogger: RedactingLogger,
) {

    private val log = redactingLogger.forClass(PaymentsController::class.java)

    /**
     * Captures a payment.
     *
     * The `Idempotency-Key` header is required. Clients generate one key per logical
     * payment attempt and send the same key on every retry, which lets us return the
     * original outcome instead of charging the customer a second time. A request
     * without the header cannot be retried safely, so it is rejected outright rather
     * than processed once and hoped about.
     */
    @PostMapping
    fun createPayment(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @RequestHeader(REQUEST_ID_HEADER) requestId: String,
        @RequestHeader(ACTOR_HEADER) actor: String,
        @RequestBody request: CreatePaymentRequest,
    ): ResponseEntity<Any> {
        if (idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().body(
                ErrorResponse("idempotency_key_required", "$IDEMPOTENCY_KEY_HEADER must not be blank"),
            )
        }

        idempotencyStore.findPaymentId(idempotencyKey)?.let { existingId ->
            val existing = payments.find(existingId)
            if (existing != null) {
                log.info("replaying payment {} for idempotency key", existing.id)
                return ResponseEntity.ok(existing.toResponse())
            }
        }

        val command = TransferCommand(
            requestId = requestId,
            actor = actor,
            accountId = request.accountId,
            instrument = Instrument(request.instrumentToken, request.instrumentLast4),
            amount = Money.ofMinor(request.amountMinorUnits, request.currency),
        )

        return when (val result = transferService.transfer(command)) {
            is TransferResult.Captured -> {
                idempotencyStore.remember(idempotencyKey, result.payment.id)
                ResponseEntity.status(HttpStatus.CREATED).body(result.payment.toResponse())
            }

            is TransferResult.Rejected ->
                ResponseEntity.badRequest().body(ErrorResponse("payment_rejected", result.reason))

            is TransferResult.ProcessorFailed -> {
                val status = if (result.retryable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY
                ResponseEntity.status(status).body(ErrorResponse("processor_failed", result.reason))
            }
        }
    }

    @PostMapping("/{paymentId}/refunds")
    fun refundPayment(
        @PathVariable paymentId: String,
        @RequestHeader(REQUEST_ID_HEADER) requestId: String,
        @RequestHeader(ACTOR_HEADER) actor: String,
    ): ResponseEntity<Any> {
        val command = RefundCommand(requestId = requestId, actor = actor, paymentId = paymentId)

        return when (val result = refundService.refundInFull(command)) {
            is RefundResult.Refunded -> ResponseEntity.ok(result.payment.toResponse())

            is RefundResult.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse("payment_not_found", "no payment ${result.paymentId}"))

            is RefundResult.Rejected ->
                ResponseEntity.badRequest().body(ErrorResponse("refund_rejected", result.reason))

            is RefundResult.ProcessorFailed -> {
                val status = if (result.retryable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY
                ResponseEntity.status(status).body(ErrorResponse("processor_failed", result.reason))
            }
        }
    }

    private fun Payment.toResponse() = PaymentResponse(
        paymentId = id,
        status = status,
        amountMinorUnits = amount.amount.movePointRight(amount.currency.defaultFractionDigits).toLong(),
        currency = amount.currency.currencyCode,
    )

    companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val ACTOR_HEADER = "X-Actor"
    }
}
