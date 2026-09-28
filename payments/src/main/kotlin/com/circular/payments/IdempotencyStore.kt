package com.circular.payments

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the outcome of a payment attempt against the caller's
 * `Idempotency-Key`.
 *
 * This is what makes a retry safe. A client that retries with the same key gets
 * the stored payment back instead of a second charge, so the key has to be present
 * on every attempt for the guarantee to hold.
 */
interface IdempotencyStore {
    fun findPaymentId(key: String): String?

    fun remember(key: String, paymentId: String)
}

@Component
class InMemoryIdempotencyStore : IdempotencyStore {

    private val keys = ConcurrentHashMap<String, String>()

    override fun findPaymentId(key: String): String? = keys[key]

    override fun remember(key: String, paymentId: String) {
        keys.putIfAbsent(key, paymentId)
    }
}
