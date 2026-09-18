package org.uvo.paymentplatform.service;

import org.springframework.stereotype.Component;
import org.uvo.paymentplatform.config.PaymentAlertProperties;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.DatosPlan;
import org.uvo.paymentplatform.model.PaymentStatus;
import org.uvo.paymentplatform.support.Rut;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * The business rules that used to be model accessors: payment status, suspendability, the displayed
 * plan name, the amount to charge and the formatted RUT.
 *
 * <p>They live here rather than on the entity because they depend on configured thresholds and on
 * the active plan row, neither of which an entity should reach for. Every method that needs a
 * threshold also has an overload taking it explicitly, which is what keeps them testable without a
 * container — the same property the source project preserved by not calling the config helper
 * inside the status logic.
 *
 * <p><b>Status logic exists twice in this application</b>: here, per row, and as date ranges in
 * {@code CustomerRepository} for the list's filter and counts. Change one without the other and the
 * badge and the filter disagree.
 */
@Component
public class CustomerRules {

    private static final String PLAN_TYPE_BLANK = "Sin plan";

    /** A known data-entry typo in the production data, shown corrected. */
    private static final String PLAN_TYPE_TYPO = "menusal";
    private static final String PLAN_TYPE_TYPO_FIXED = "Mensual";

    private final PaymentAlertProperties properties;
    private final Clock clock;

    public CustomerRules(PaymentAlertProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Today, from the injected clock — never the static one, so tests can pin the date. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Days elapsed since the payment date: negative when the payment is not yet due, null when the
     * company has no payment date at all.
     */
    public Integer daysPastDue(LocalDate proximoPago, LocalDate today) {
        if (proximoPago == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(proximoPago, today);
    }

    public Integer daysPastDue(Customer customer) {
        return daysPastDue(customer.getProximoPago(), today());
    }

    /** A customer without a payment date is considered on time. */
    public PaymentStatus paymentStatus(Customer customer) {
        return paymentStatus(customer.getProximoPago(), today(), properties.dueSoonDays());
    }

    public PaymentStatus paymentStatus(LocalDate proximoPago, LocalDate today, int dueSoonDays) {
        Integer days = daysPastDue(proximoPago, today);

        if (days == null) {
            return PaymentStatus.ON_TIME;
        }
        return fromDaysPastDue(days, dueSoonDays);
    }

    /**
     * Resolve a status from the number of days past the payment date. Negative or zero means the
     * payment is not yet due.
     */
    public PaymentStatus fromDaysPastDue(int days, int dueSoonDays) {
        if (days > 0) {
            return PaymentStatus.OVERDUE;
        }
        if (days >= -dueSoonDays) {
            return PaymentStatus.DUE_SOON;
        }
        return PaymentStatus.ON_TIME;
    }

    public String formattedRut(Customer customer) {
        return Rut.format(customer.getRut());
    }

    /**
     * Human-readable subscription plan. The stored column is free text in production — 13 distinct
     * values, one known misspelling, some blanks — so an unrecognized value is shown as stored
     * rather than collapsed into a fixed set. <b>Never branch behaviour on it.</b>
     *
     * <p>It has no bounded width either: some real values are a full sentence, so no layout may
     * treat it as a short token.
     */
    public String planType(String tipoPlan) {
        String plan = tipoPlan == null ? "" : tipoPlan.trim();

        if (plan.isEmpty()) {
            return PLAN_TYPE_BLANK;
        }
        if (plan.toLowerCase(Locale.ROOT).equals(PLAN_TYPE_TYPO)) {
            return PLAN_TYPE_TYPO_FIXED;
        }
        return plan;
    }

    public String planType(Customer customer) {
        return planType(customer.getTipoPlan());
    }

    /**
     * Amount to charge: the plan price plus any hardware instalment still owed. Null when there is
     * no active plan on file, which is what makes a company with no plan unpayable rather than
     * chargeable for nothing.
     */
    public Integer chargeAmount(DatosPlan plan) {
        if (plan == null) {
            return null;
        }

        int montoPlan = plan.getMontoPlan() == null ? 0 : plan.getMontoPlan();
        int montoHardware = plan.getMontoHardware() == null ? 0 : plan.getMontoHardware();

        return montoPlan + montoHardware;
    }

    /**
     * True once staff are allowed to suspend the account: overdue by more than the configured grace
     * period, and not already suspended.
     *
     * <p><b>Suspension itself is always manual and staff-confirmed</b> — never automatic, not even
     * long past the grace period. Nothing may ever suspend on a schedule: it would cut off real
     * paying customers.
     */
    public boolean isSuspendable(Customer customer) {
        return isSuspendable(customer, today(), properties.overdueGraceDays());
    }

    public boolean isSuspendable(Customer customer, LocalDate today, int overdueGraceDays) {
        Integer days = daysPastDue(customer.getProximoPago(), today);
        return customer.isActive() && (days == null ? 0 : days) > overdueGraceDays;
    }

    /** Days left before the account becomes suspendable; only meaningful while overdue. */
    public Integer daysUntilSuspendable(Customer customer) {
        return daysUntilSuspendable(customer, today(), properties.overdueGraceDays());
    }

    public Integer daysUntilSuspendable(Customer customer, LocalDate today, int overdueGraceDays) {
        Integer days = daysPastDue(customer.getProximoPago(), today);

        if (days == null || days <= 0) {
            return null;
        }
        return Math.max(0, overdueGraceDays - days + 1);
    }
}
