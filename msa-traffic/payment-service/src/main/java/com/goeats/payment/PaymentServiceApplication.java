package com.goeats.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.goeats.payment",
        "com.goeats.common.exception",
        "com.goeats.common.outbox",
        "com.goeats.common.resilience"
})
@EnableScheduling  // ★ Required for Outbox Relay @Scheduled polling
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
