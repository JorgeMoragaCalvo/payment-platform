package org.uvo.paymentplatform.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.paymentplatform.model.BankMovement;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Queries over {@code bank_movements}.
 *
 * <p>Note how the two "what is outstanding" questions are deliberately different, and both are
 * needed:
 *
 * <ul>
 *   <li>{@link #countPending} — {@code unmatched + suggested}, the to-do count behind the tab badge.
 *   <li>{@link #findQueue} — pending <b>or</b> auto-confirmed, which is what the review list shows.
 *       A payment made with nobody watching has to be visible where staff already look.
 * </ul>
 *
 * Staff-confirmed rows and Transbank payout tags stay out of both: reviewed already, or settlement
 * business. The default list must not drift into being full history.
 */
public interface BankMovementRepository extends JpaRepository<BankMovement, Long> {

    boolean existsByRowHash(String rowHash);

    @Query("SELECT COUNT(m) FROM BankMovement m WHERE m.status IN :statuses")
    long countPending(@Param("statuses") Collection<BankMovement.Status> statuses);

    @Query("""
            SELECT m FROM BankMovement m
             WHERE m.status IN :pending OR m.autoConfirmed = true
             ORDER BY m.postedAt DESC, m.id DESC
            """)
    Page<BankMovement> findQueue(@Param("pending") Collection<BankMovement.Status> pending, Pageable pageable);

    /**
     * The status filters are disjoint on purpose: "auto-confirmed" and "reconciled" both describe
     * {@code status = matched}, so the latter has to exclude the former or the counts stop adding up.
     */
    @Query("""
            SELECT m FROM BankMovement m
             WHERE m.status = :status AND m.autoConfirmed = false
             ORDER BY m.postedAt DESC, m.id DESC
            """)
    Page<BankMovement> findStaffMatched(@Param("status") BankMovement.Status status, Pageable pageable);

    Page<BankMovement> findByAutoConfirmedTrueOrderByPostedAtDescIdDesc(Pageable pageable);

    Page<BankMovement> findByStatusOrderByPostedAtDescIdDesc(BankMovement.Status status, Pageable pageable);

    long countByStatus(BankMovement.Status status);

    long countByAutoConfirmedTrue();

    @Query("SELECT COUNT(m) FROM BankMovement m WHERE m.status = :status AND m.autoConfirmed = false")
    long countStaffMatched(@Param("status") BankMovement.Status status);

    /**
     * Transbank payouts, newest first. Tagged at import so they leave the customer queue and show up
     * only in the settlement view — read-only there, since those charges are already recorded and
     * booking payments here would double-count them.
     */
    @Query("""
            SELECT m FROM BankMovement m
             WHERE m.direction = :direction AND m.matchReason = :matchReason
             ORDER BY m.postedAt DESC, m.id DESC
            """)
    List<BankMovement> findTransbankPayouts(@Param("direction") BankMovement.Direction direction,
                                            @Param("matchReason") String matchReason,
                                            Pageable pageable);

    /**
     * Claims a movement for confirmation. Returns 1 when this caller won the race and 0 when the
     * movement was already resolved — two tabs, two staff members or a replayed request therefore
     * record exactly one payment. The caller must act on the returned count, never on the status of
     * the entity it already had in memory.
     *
     * <p>Native SQL, for two reasons that both matter here. The status values are stored lowercase,
     * and a bulk JPQL update writes enum literals without going through the attribute converter, so
     * it would compare against {@code 'MATCHED'} and silently match nothing. And {@code empresa_id}
     * cannot be assigned through an association path in JPQL at all.
     *
     * <p>{@code flushAutomatically} pushes pending changes before the update so it sees them;
     * {@code clearAutomatically} is deliberately NOT set — clearing would detach the entities the
     * calling service still holds, and a later mutation of a detached customer would be lost
     * without any error.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE bank_movements
               SET status = 'matched',
                   empresa_id = :customerId,
                   reconciled_by = :reconciledBy,
                   reconciled_at = :reconciledAt,
                   auto_confirmed = :auto
             WHERE id = :movementId
               AND status NOT IN ('matched', 'ignored')
            """, nativeQuery = true)
    int claimForConfirmation(@Param("movementId") Long movementId,
                             @Param("customerId") Long customerId,
                             @Param("reconciledBy") Long reconciledBy,
                             @Param("reconciledAt") LocalDateTime reconciledAt,
                             @Param("auto") boolean auto);

    /** Links the recorded payment back to the movement, once the claim above has succeeded. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE bank_movements SET suscriptor_payment_id = :paymentId WHERE id = :movementId",
            nativeQuery = true)
    int attachPayment(@Param("movementId") Long movementId, @Param("paymentId") Long paymentId);
}
