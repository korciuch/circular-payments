package com.circular.payments

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The logger used everywhere in payments and lending.
 *
 * Masks card numbers, bank account numbers and bearer style tokens before the
 * message reaches the log sink. See docs/sox-controls.md, CTRL-3. Debug output is
 * held to the same standard as production logging, because retention is the same.
 */
@Component
class RedactingLogger {

    fun forClass(type: Class<*>): ScopedLogger = ScopedLogger(LoggerFactory.getLogger(type))

    class ScopedLogger(private val delegate: Logger) {

        fun debug(message: String, vararg args: Any?) =
            delegate.debug(redact(message), *redactAll(args))

        fun info(message: String, vararg args: Any?) =
            delegate.info(redact(message), *redactAll(args))

        fun warn(message: String, vararg args: Any?) =
            delegate.warn(redact(message), *redactAll(args))

        fun error(message: String, throwable: Throwable, vararg args: Any?) =
            delegate.error(redact(message), *redactAll(args), throwable)

        private fun redactAll(args: Array<out Any?>): Array<Any?> =
            args.map { if (it is String) redact(it) else it }.toTypedArray()
    }

    companion object {
        private val CARD_NUMBER = Regex("""\b(?:\d[ -]?){12,18}\d\b""")
        private val ACCOUNT_NUMBER = Regex("""\b\d{8,17}\b""")
        private val TOKEN = Regex("""\b(?:tok|pk|sk|Bearer)[_ ][A-Za-z0-9\-_]{8,}\b""")

        /**
         * Keeps the last four digits of an instrument so support can still match a
         * customer's statement, and drops everything else.
         */
        fun redact(value: String): String =
            value
                .replace(TOKEN, "[redacted-token]")
                .replace(CARD_NUMBER) { match -> mask(match.value) }
                .replace(ACCOUNT_NUMBER) { match -> mask(match.value) }

        private fun mask(raw: String): String {
            val digits = raw.filter { it.isDigit() }
            return if (digits.length <= 4) "****" else "****" + digits.takeLast(4)
        }
    }
}
