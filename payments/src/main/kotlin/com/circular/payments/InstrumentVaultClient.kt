package com.circular.payments

import org.springframework.stereotype.Component

/**
 * Reads a stored instrument back out of the instrument vault.
 *
 * Tokens are enough for almost everything we do. This exists for the few vendor
 * endpoints that will not accept a token and want the instrument number itself.
 */
interface InstrumentVault {
    fun reveal(token: String): String
}

@Component
class InstrumentVaultClient : InstrumentVault {

    /**
     * Resolves a token through the vault service. Backed by a fixture until the
     * service credentials are provisioned for the payments namespace.
     */
    override fun reveal(token: String): String =
        FIXTURES[token] ?: throw IllegalArgumentException("no instrument stored for token")

    private companion object {
        val FIXTURES = mapOf(
            "tok_demo_checkout" to "4242424242424242",
            "tok_live_9f8e7d6c5b4a" to "5555555555554444",
        )
    }
}
