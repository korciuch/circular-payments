package com.circular.payments

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication(scanBasePackages = ["com.circular"])
class PaymentsApplication {

    /** Injected rather than read statically, so tests can pin the clock. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<PaymentsApplication>(*args)
}
