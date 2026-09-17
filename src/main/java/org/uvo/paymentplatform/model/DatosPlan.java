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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The customer's plan pricing and duration. Local mirror of the production {@code datos_plan}
 * table, limited to the columns this feature touches.
 *
 * <p>"The active plan" is the most recent row with {@code estado = 1} — that selection is query
 * logic for the repository, not something the entity encodes.
 *
 * <p>{@code periodoDays} is the renewal length, and production holds junk in it ({@code 0} and
 * {@code 99999999}). The sanitising rule (outside 1–1095 is bad data, replaced with 30) lives in
 * the single payment write path, not here, so that both channels get it identically.
 */
@Entity
@Table(name = "datos_plan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class DatosPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable in the schema, so no {@code nullable = false} here. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empresa_id")
    private Customer customer;

    @Column(name = "plan_id")
    private Integer planId;

    @Column(name = "monto_plan")
    private Integer montoPlan;

    @Column(name = "monto_hardware")
    private Integer montoHardware;

    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;

    @Column(name = "periodo_plan", length = 45)
    private String periodoPlan;

    @Column(name = "periodo_days")
    @Builder.Default
    private Integer periodoDays = 30;

    @Column(nullable = false)
    @Builder.Default
    private Integer estado = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
