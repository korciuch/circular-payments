package com.circular.payments

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * One row of the audit trail. Auditors sample these, so every field is required
 * except [failureReason], which is set when [outcome] is [AuditOutcome.FAILURE].
 */
data class AuditEvent(
    val requestId: String,
    val actor: String,
    val action: String,
    val subjectId: String,
    val amount: Money,
    val outcome: AuditOutcome,
    val failureReason: String? = null,
    val recordedAt: Instant,
)

enum class AuditOutcome { SUCCESS, FAILURE }

/**
 * Writes structured audit events for money movement and account state changes.
 *
 * See docs/sox-controls.md, CTRL-1. Application logging is not a substitute: an
 * operation that moves money and does not reach this interface is an audit
 * exception.
 */
interface AuditLogger {
    fun record(event: AuditEvent)

    fun success(requestId: String, actor: String, action: String, subjectId: String, amount: Money)

    fun failure(
        requestId: String,
        actor: String,
        action: String,
        subjectId: String,
        amount: Money,
        reason: String,
    )
}

@Component
class StructuredAuditLogger(
    private val clock: Clock,
) : AuditLogger {

    private val log = LoggerFactory.getLogger("circular.audit")

    override fun record(event: AuditEvent) {
        log.info(
            "audit requestId={} actor={} action={} subject={} amount={} currency={} outcome={} reason={}",
            event.requestId,
            event.actor,
            event.action,
            event.subjectId,
            event.amount.amount,
            event.amount.currency.currencyCode,
            event.outcome,
            event.failureReason ?: "-",
        )
    }

    override fun success(
        requestId: String,
        actor: String,
        action: String,
        subjectId: String,
        amount: Money,
    ) = record(
        AuditEvent(
            requestId = requestId,
            actor = actor,
            action = action,
            subjectId = subjectId,
            amount = amount,
            outcome = AuditOutcome.SUCCESS,
            recordedAt = clock.instant(),
        ),
    )

    override fun failure(
        requestId: String,
        actor: String,
        action: String,
        subjectId: String,
        amount: Money,
        reason: String,
    ) = record(
        AuditEvent(
            requestId = requestId,
            actor = actor,
            action = action,
            subjectId = subjectId,
            amount = amount,
            outcome = AuditOutcome.FAILURE,
            failureReason = reason,
            recordedAt = clock.instant(),
        ),
    )
}
