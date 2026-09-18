package org.uvo.paymentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Payment status thresholds, in days past the payment date.
 *
 * <pre>
 *   on time  : days past due &lt;= -(dueSoonDays + 1)
 *   due soon : -dueSoonDays .. 0 (within dueSoonDays of the due date, up to and including it)
 *   overdue  : &gt; 0, indefinitely
 * </pre>
 *
 * <p>{@code overdueGraceDays} is the suspension grace period on top of overdue: once a company is
 * overdue, staff still cannot suspend it until it has been overdue for more than this many days.
 * Suspension itself is always a manual, staff-confirmed action — this threshold only controls when
 * the button becomes available, and nothing may ever suspend automatically.
 */
@ConfigurationProperties(prefix = "payment-alert")
public record PaymentAlertProperties(
        @DefaultValue("3") int dueSoonDays,
        @DefaultValue("3") int overdueGraceDays) {
}
