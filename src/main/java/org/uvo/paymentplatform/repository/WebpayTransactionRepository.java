package org.uvo.paymentplatform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.paymentplatform.model.WebpayTransaction;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WebpayTransactionRepository extends JpaRepository<WebpayTransaction, Long> {

    /**
     * How the return leg finds the pending payment. The buy order travels in the request body,
     * because Transbank's cross-site POST carries no session cookie.
     */
    Optional<WebpayTransaction> findByBuyOrder(String buyOrder);

    /**
     * Authorizes a still-pending transaction. Returns 1 when this delivery of the return won, and 0
     * when an earlier one already settled it — so a replayed POST, a refreshed tab and two
     * concurrent requests record exactly one payment. The unique constraint on
     * {@code suscriptor_payments.external_reference} is the backstop behind this.
     *
     * <p>Native for the same reasons as the reconciliation claim: the stored status values are
     * lowercase, and a bulk JPQL update would bypass the attribute converter.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE webpay_transactions
               SET status = 'authorized',
                   token = :token,
                   response_code = :responseCode,
                   committed_at = :committedAt
             WHERE id = :id AND status = 'pending'
            """, nativeQuery = true)
    int authorizeIfPending(@Param("id") Long id,
                           @Param("token") String token,
                           @Param("responseCode") Short responseCode,
                           @Param("committedAt") LocalDateTime committedAt);

    /**
     * Moves a still-pending transaction to a terminal non-paying state — declined, aborted or
     * failed. Guarded the same way, so a transaction already settled by an earlier delivery of the
     * same return is left untouched.
     *
     * <p>{@code status} is a {@code String} because native queries do not run attribute converters:
     * callers pass {@code Status.DECLINED.value()} so the lowercase value the column expects is
     * explicit at the call site rather than accidental.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE webpay_transactions
               SET status = :status,
                   token = :token,
                   response_code = :responseCode,
                   committed_at = :committedAt
             WHERE id = :id AND status = 'pending'
            """, nativeQuery = true)
    int closeIfPending(@Param("id") Long id,
                       @Param("status") String status,
                       @Param("token") String token,
                       @Param("responseCode") Short responseCode,
                       @Param("committedAt") LocalDateTime committedAt);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE webpay_transactions SET suscriptor_payment_id = :paymentId WHERE id = :id",
            nativeQuery = true)
    int attachPayment(@Param("id") Long id, @Param("paymentId") Long paymentId);
}
