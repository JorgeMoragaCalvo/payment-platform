package org.uvo.paymentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * The customer's plan pricing and duration. Only the columns this feature touches are mapped; the
 * production table is much wider (branch/warehouse/register counts, contact details, sales notes).
 *
 * <p>"The active plan" is the most recent row with {@code estado = 1} — that selection is query
 * logic for the repository, not something the entity encodes.
 *
 * <p><b>Two things here follow production rather than convention.</b> The primary key is an
 * {@code int}, not a {@code bigint}, so it is mapped as {@link Integer}. And {@code empresa_id} is
 * also an {@code int} while {@code empresas.id} is a {@code bigint unsigned}: the two sides of that
 * relationship have different widths, so it is mapped as a plain column instead of a
 * {@code @ManyToOne} — an association would make schema validation reject the mapping, and the
 * mismatch is the existing schema's, not something to paper over silently.
 *
 * <p>{@code periodoDays} is the renewal length and is nullable, with junk in it in production
 * ({@code 0} and {@code 99999999}). The sanitising rule — outside 1–1095 is bad data, replaced with
 * 30 — lives in the single payment write path, so both payment channels get it identically.
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
    private Integer id;

    @Column(name = "empresa_id")
    private Integer empresaId;

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

    @Column
    @Builder.Default
    private Integer estado = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
