package org.uvo.paymentplatform.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A Webpay Plus checkout, from the moment before the browser leaves for Transbank until the return
 * lands. Owned by this module.
 *
 * <p>The lifecycle is one-way and terminal:
 *
 * <pre>
 *   pending ─┬─> authorized  (committed and approved — money moved)
 *            ├─> declined    (committed, card refused)
 *            ├─> aborted     (customer backed out, or the form timed out)
 *            └─> failed      (we could not reach Transbank to commit)
 * </pre>
 *
 * <p>Only {@code authorized} has a financial effect, and there is no UI to reverse one. The move out
 * of {@code pending} is made with a conditional UPDATE, so a replayed return POST, a refreshed tab
 * or two concurrent requests record exactly one payment.
 *
 * <p><b>This table exists because the return leg cannot rely on the session.</b> Transbank sends the
 * customer back with a cross-site POST, and a {@code SameSite=Lax} cookie is not sent on one — so
 * the pending payment is looked up by {@code buyOrder}, which travels in the request body. Do not
 * move this state back into the session, and do not weaken the cookie policy to avoid it.
 */
@Entity
@Table(name = "webpay_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class WebpayTransaction {

    /** Lowercase on the wire to match the column's default and the frontend's expectations. */
    public enum Status {
        PENDING("pending"),
        AUTHORIZED("authorized"),
        DECLINED("declined"),
        ABORTED("aborted"),
        FAILED("failed");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Status fromValue(String value) {
            for (Status status : values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown webpay transaction status: " + value);
        }
    }

    @Converter
    public static class StatusConverter implements AttributeConverter<Status, String> {
        @Override
        public String convertToDatabaseColumn(Status attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public Status convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Status.fromValue(dbData);
        }
    }

    /** Where a finished payment sends the browser back to. */
    public static final String RETURN_TO_PAYMENT_ALERT = "payment-alert";

    public static final String RETURN_TO_MY_ACCOUNT = "mi-cuenta";

    /** Webpay Plus caps the buy order at 26 characters. */
    private static final int BUY_ORDER_MAX_LENGTH = 26;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Sent to Transbank at create() and echoed back on every return, including aborted ones. */
    @Column(name = "buy_order", nullable = false, unique = true, length = BUY_ORDER_MAX_LENGTH)
    private String buyOrder;

    @Column(name = "session_id", nullable = false, length = 61)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Customer customer;

    /**
     * Who started the payment: staff on the lookup page, or the customer on their own portal.
     * Carried here because {@code suscriptor_payments.user_id} is NOT NULL in production and the
     * Transbank return lands unauthenticated.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** What we asked Transbank to charge, checked against what the commit says was charged. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Enough to rebuild the page the payment started from. */
    @Column(nullable = false, length = 100)
    @Builder.Default
    private String search = "";

    @Column(name = "return_to", nullable = false, length = 20)
    @Builder.Default
    private String returnTo = RETURN_TO_PAYMENT_ALERT;

    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(length = 100)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suscriptor_payment_id")
    private SuscriptorPayment payment;

    @Column(name = "response_code")
    private Short responseCode;

    @Column(name = "committed_at")
    private LocalDateTime committedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isPending() {
        return status == Status.PENDING;
    }

    /** Whole pesos. The column is decimal for schema parity. */
    public int getAmountClp() {
        return amount == null ? 0 : amount.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * The buy order sent to Transbank: unique per transaction and at most 26 characters. The random
     * suffix is not decoration — a plain {@code PA{id}-{timestamp}} repeats when the same customer
     * is charged twice within one second, which Transbank rejects.
     */
    public static String makeBuyOrder(long empresaId) {
        byte[] suffix = new byte[2];
        RANDOM.nextBytes(suffix);

        String raw = SuscriptorPayment.WEBPAY_REFERENCE_PREFIX + empresaId
                + "-" + Instant.now().getEpochSecond()
                + "-" + String.format("%02X%02X", suffix[0], suffix[1]);

        return raw.length() > BUY_ORDER_MAX_LENGTH ? raw.substring(0, BUY_ORDER_MAX_LENGTH) : raw;
    }
}
