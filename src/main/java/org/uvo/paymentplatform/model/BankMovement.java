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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * A line from an imported cartola and what was decided about it. Owned by this module.
 *
 * <p>The lifecycle is one-way:
 *
 * <pre>
 *   unmatched ─┬─> suggested ──> matched   (a company was confirmed)
 *              └─────────────── ignored    (not a subscription payment)
 * </pre>
 *
 * <p>Only {@code matched} has a financial effect. Almost always that takes a staff click; the one
 * exception is a deposit carrying the payer's validated RUT and the exact plan amount with no rival
 * candidate, which the importer confirms itself and flags {@code autoConfirmed}. Once
 * {@code payment} is set there is no way back: reversing a recorded payment is a financial decision
 * and deliberately has no UI.
 *
 * <p>{@code rowHash} is the fingerprint of the parsed line and is <b>unique</b>. Cartola exports
 * routinely overlap on dates, so the same deposit arrives in two different files; the importer's
 * own check is a read, so two simultaneous imports would both pass it and the constraint is the
 * real guard.
 */
@Entity
@Table(name = "bank_movements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BankMovement {

    /**
     * Money in or out. Only credits can ever settle a subscription.
     *
     * <p>The stored values are lowercase because that is what the column holds and what the
     * frontend reads. {@code EnumType.STRING} would write {@code CREDIT}, which would not match the
     * schema — hence the explicit converter.
     */
    public enum Direction {
        CREDIT("credit"),
        DEBIT("debit");

        private final String value;

        Direction(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Direction fromValue(String value) {
            for (Direction direction : values()) {
                if (direction.value.equals(value)) {
                    return direction;
                }
            }
            throw new IllegalArgumentException("Unknown bank movement direction: " + value);
        }
    }

    /** Lowercase on the wire, same reason as {@link Direction}; {@code unmatched} is the default. */
    public enum Status {
        UNMATCHED("unmatched"),
        SUGGESTED("suggested"),
        MATCHED("matched"),
        IGNORED("ignored");

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
            throw new IllegalArgumentException("Unknown bank movement status: " + value);
        }
    }

    @Converter
    public static class DirectionConverter implements AttributeConverter<Direction, String> {
        @Override
        public String convertToDatabaseColumn(Direction attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public Direction convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Direction.fromValue(dbData);
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

    /**
     * How a card-processor payout is tagged at import, and how the settlement view finds it again.
     * Shared by the importer that writes it and the report that reads it, so the two cannot drift.
     */
    public static final String MATCH_REASON_TRANSBANK_PAYOUT = "Liquidación Transbank";

    /** Decided already, by a staff click, by auto-confirmation, or by the Transbank payout tag. */
    private static final Set<Status> RESOLVED = EnumSet.of(Status.MATCHED, Status.IGNORED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_statement_id", nullable = false)
    private BankStatement statement;

    @Column(name = "posted_at", nullable = false)
    private LocalDate postedAt;

    /** The bank's free-text glosa. Truncated to the column width by the reader, not here. */
    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 100)
    private String reference;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Convert(converter = DirectionConverter.class)
    @Column(nullable = false, length = 10)
    private Direction direction;

    /** Payer RUT pulled out of the glosa, normalized, and only when its check digit validated. */
    @Column(name = "counterparty_rut", length = 20)
    private String counterpartyRut;

    @Column(name = "row_hash", nullable = false, unique = true, length = 64)
    private String rowHash;

    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.UNMATCHED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empresa_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suscriptor_payment_id")
    private SuscriptorPayment payment;

    /** {@code min(100, score)} over a 105-point maximum — a display figure, not a probability. */
    @Column(name = "match_confidence", columnDefinition = "tinyint")
    private Integer matchConfidence;

    @Column(name = "match_reason")
    private String matchReason;

    /** Null for an auto-confirmation (nobody decided it) and for a Transbank payout tag. */
    @Column(name = "reconciled_by")
    private Long reconciledBy;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    /**
     * Set when the importer reconciled this on its own. {@code reconciledBy} is null for those rows,
     * but it is also null for a Transbank payout tag, so it cannot tell the two apart — staff need
     * to see which payments nobody reviewed.
     */
    @Column(name = "auto_confirmed", nullable = false)
    @Builder.Default
    private Boolean autoConfirmed = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isResolved() {
        return status != null && RESOLVED.contains(status);
    }

    /** Whole pesos. The column is decimal for schema parity; CLP has no subunit in practice. */
    public int getAmountClp() {
        return amount == null ? 0 : amount.setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
