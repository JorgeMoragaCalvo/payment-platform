package org.uvo.paymentplatform.model;

/**
 * Payment status derived from how many days a customer is past their payment date.
 *
 * <p>Derived data, not a stored column: there is no table and no converter. It is computed from
 * {@code empresas.proximoPago} against today, with the {@code payment-alert.due-soon-days}
 * threshold, so the threshold logic lives in the service layer (see the plan's {@code CustomerRules}),
 * not here.
 *
 * <p>The wire values are kept lowercase and snake_case because the frontend switches on them
 * ({@code pa-bar--on_time}, the status filter, the badge classes), exactly as the PHP constants did.
 * The Spanish labels are NOT here: they are view copy, and the two views share wording but not
 * markup, so they belong in the frontend's single copy module.
 */
public enum PaymentStatus {

    ON_TIME("on_time"),
    DUE_SOON("due_soon"),
    OVERDUE("overdue");

    private final String value;

    PaymentStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PaymentStatus fromValue(String value) {
        for (PaymentStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown payment status: " + value);
    }
}
