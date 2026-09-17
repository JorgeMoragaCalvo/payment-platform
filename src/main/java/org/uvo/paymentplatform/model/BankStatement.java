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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One uploaded bank statement (cartola). Unlike {@code empresas}/{@code datos_plan}/
 * {@code suscriptor_payments}, this table is owned outright by this module and does not exist in
 * {@code db2026} yet, so the mapping is schema-complete.
 *
 * <p>{@code fileHash} is the SHA-256 of the uploaded bytes and is <b>unique</b>: re-uploading the
 * same export is the most likely staff mistake, and it would otherwise duplicate every movement in
 * it.
 *
 * <p>How many movements an import auto-confirmed is deliberately not a column — it is per-request
 * state that belongs on the import response, not on the row.
 */
@Entity
@Table(name = "bank_statements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BankStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Config key of the bank layout the file was read with, e.g. {@code chile}. */
    @Column(nullable = false, length = 50)
    private String bank;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "stored_path", nullable = false, length = 500)
    private String storedPath;

    @Column(name = "file_hash", nullable = false, unique = true, length = 64)
    private String fileHash;

    @Column(name = "imported_by")
    private Long importedBy;

    /** New movements actually stored, which is lower than the file's line count on a re-import. */
    @Column(name = "movement_count", nullable = false)
    @Builder.Default
    private Integer movementCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "statement")
    private List<BankMovement> movements;
}
