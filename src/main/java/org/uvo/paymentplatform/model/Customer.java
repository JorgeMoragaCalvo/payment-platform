package org.uvo.paymentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * View over the production {@code empresas} table. The subscription due date lives in
 * {@code proximoPago}; RUTs are stored as "77353398-9" (dash, no dots), so lookups compare against
 * a SQL-stripped copy of the column. Mostly read-only, except the Webpay/suspend flow, which
 * writes {@code proximoPago} and {@code estado}.
 *
 * <p>Only the columns this feature touches are mapped — {@code empresas} in {@code db2026} is much
 * wider, and it also carries legacy {@code fechaVencimiento}/{@code pagos}/{@code plan} columns
 * that this module deliberately does not write.
 *
 * <p>Business rules that depend on config ({@code payment_status}, {@code is_suspendable},
 * {@code days_until_suspendable}, {@code plan_type}, {@code charge_amount}, {@code formatted_rut})
 * are NOT accessors here: they live in the service layer, since they need
 * {@code due-soon-days}/{@code overdue-grace-days} and the active plan row.
 */
@Entity
@Table(name = "empresas")
@SQLDelete(sql = "UPDATE empresas SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Customer {

    /** Active. The column is a string in production, so this is compared literally, never cast. */
    public static final String ESTADO_ACTIVE = "1";

    /** Suspended. */
    public static final String ESTADO_SUSPENDED = "0";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String rut;

    @Column(name = "RazonSocial")
    private String razonSocial;

    @Column(name = "nombre_fantasia", length = 250)
    private String nombreFantasia;

    /** The subscription due date. Null means the customer is treated as on time. */
    @Column(name = "proximoPago")
    private LocalDate proximoPago;

    /**
     * Free text in production (13 distinct values, one known typo, some blanks). Never branch
     * behaviour on it, and never assume a two-value split — it is display-only.
     */
    @Column(name = "tipoPlan", length = 45)
    private String tipoPlan;

    /** Nullable in production, so {@code isActive()} has to treat null as "not active". */
    @Column(length = 45)
    @Builder.Default
    private String estado = ESTADO_ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * There is deliberately NO association to the plan table here. In production
     * {@code datos_plan.empresa_id} is an {@code int} while this table's {@code id} is
     * {@code bigint unsigned}, so the two sides cannot be joined type-safely and schema validation
     * rejects the mapping. The active plan is therefore looked up by id through its repository.
     */
    @OneToMany(mappedBy = "customer")
    private List<SuscriptorPayment> payments;

    /** Display name: the trade name when there is one, otherwise the legal name. */
    public String getName() {
        return nombreFantasia != null && !nombreFantasia.isBlank() ? nombreFantasia : razonSocial;
    }

    public boolean isActive() {
        return ESTADO_ACTIVE.equals(estado);
    }
}
