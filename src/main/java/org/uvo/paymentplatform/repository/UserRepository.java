package org.uvo.paymentplatform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.paymentplatform.model.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Login lookup. Logically deleted rows are excluded by the entity's restriction, which is what
     * stops a terminated employee from authenticating.
     */
    Optional<User> findByEmail(String email);

    /**
     * Enables or disables the login of a company's users, alongside suspending or reactivating the
     * company itself.
     *
     * <p><b>This is narrower than the statement it replaces, on purpose.</b> A company can have up
     * to 57 users in production, and an unqualified update had two failure modes: it disabled staff
     * who happen to carry an {@code empresa_id}, and reactivating re-enabled people who had been
     * disabled for reasons unrelated to payment. Hence three conditions:
     *
     * <ul>
     *   <li>{@code role IS NULL} and {@code isUvo = 0} — never touch staff accounts. Production
     *       carries both flags: {@code isUvo} predates this module, {@code role} is this module's
     *       own, and until user administration is unified a staff member may be marked with either.
     *   <li>{@code deleted_at IS NULL} — spelled out because the entity's restriction does not apply
     *       to bulk or native statements.
     *   <li>{@code status <> :status} — makes the call idempotent, and makes the returned count mean
     *       "rows actually changed".
     * </ul>
     *
     * <p>Still open for the host system: production also carries {@code isUvo}/{@code nivelUvo} staff
     * flags that this mirror does not have. If the host keeps using them, this needs
     * {@code isUvo = 0} as well — and the cleaner option on the table is to stop touching
     * {@code users.status} altogether and gate service on the company's own state.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE users
               SET status = :status
             WHERE empresa_id = :customerId
               AND role IS NULL
               AND (isUvo IS NULL OR isUvo = 0)
               AND deleted_at IS NULL
               AND (status IS NULL OR status <> :status)
            """, nativeQuery = true)
    int updateStatusByCustomerId(@Param("customerId") Long customerId, @Param("status") Integer status);
}
