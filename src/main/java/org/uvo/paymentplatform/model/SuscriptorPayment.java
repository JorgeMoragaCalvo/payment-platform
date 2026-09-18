package org.uvo.paymentplatform.model;

import jakarta.persistence.Column;
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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The existing manual-payment audit trail. Webpay payments and reconciled bank transfers both
 * write into this same table rather than inventing a parallel payment history — and only ever
 * through the single payment write path, never directly.
 *
 * <p>Two columns differ from today's production schema and need the coordinated DDL change on
 * {@code db2026} before this ships:
 *
 * <ul>
 *   <li>{@code amount} is {@code decimal(12,2)}, not {@code decimal(8,2)}. Production's 8,2 caps at
 *       999.999,99 while 28 active plans charge more (up to 6.910.380 CLP), so those payments throw
 *       on insert.
 *   <li>{@code external_reference} is new: the Webpay buy order, or the bank reference for a
 *       reconciled transfer. It is <b>unique</b>, not merely indexed — the callers claim their own
 *       row first, but that check is a read, so against two concurrent gateway returns the
 *       constraint is the only thing that survives.
 * </ul>
 *
 * <p>{@code userId} is NOT NULL to match production: a null here must fail before the customer is
 * charged, not after.
 */
@Entity
@Table(name = "suscriptor_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SuscriptorPayment {

    /** Prefix of every Webpay Plus buy order created by this module. */
    public static final String WEBPAY_REFERENCE_PREFIX = "PA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * In production this column means "the staff member who recorded the payment" (only 5 distinct
     * values). This module writes the authenticated user, which on the customer portal is the
     * customer — a silent change of meaning that is still an open decision in the rollout runbook.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Customer customer;

    @Column(name = "plan_id")
    private Integer planId;

    @Column(name = "periodo_plan", length = 50)
    private String periodoPlan;

    private String notes;

    @Column(name = "external_reference", unique = true, length = 100)
    private String externalReference;

    @Column(name = "factura_id")
    private Long facturaId;

    @Column(name = "fecha_pago")
    private LocalDateTime fechaPago;

    /** The due date this payment replaced, kept so a payment can be audited after the fact. */
    @Column(name = "fecha_vencimiento_original")
    private LocalDate fechaVencimientoOriginal;

    private Integer responsable;

    @Column(name = "comprobante_path", length = 500)
    private String comprobantePath;

    /** An ENUM in production, not a varchar — mapped as String, with the definition spelled out. */
    @Column(name = "comprobante_tipo", columnDefinition = "enum('jpg','jpeg','png','pdf')")
    private String comprobanteTipo;

    @Column(name = "comprobante_nombre_original")
    private String comprobanteNombreOriginal;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
