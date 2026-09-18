package org.uvo.paymentplatform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The one source of "now" for everything that reasons about dates: payment status, suspendability,
 * the due date a payment buys, the timestamps written on money-moving rows.
 *
 * <p>Injected rather than read from the static clock so a test can pin it. The source project did
 * the same with its date library's test hook, and every assertion of the form "a payment on the 10th
 * against a due date of the 12th lands on 11 April" depends on it. A test that needs a fixed date
 * registers its own {@code Clock} bean; this one steps aside.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
