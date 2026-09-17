package org.uvo.paymentplatform.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.paymentplatform.model.Customer;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Queries over {@code empresas}.
 *
 * <p><b>The status ranges below exist twice in this application</b> — once as date arithmetic here
 * (for the list's filter and counts, which cannot load every row) and once as per-row logic in the
 * service layer. {@code payment_status} is derived, not a column, so it cannot be queried. Change
 * one without the other and the badge and the filter disagree:
 *
 * <pre>
 *   overdue  : days &gt; 0        -&gt; proximoPago &lt; today
 *   due_soon : -D &lt;= days &lt;= 0 -&gt; today &lt;= proximoPago &lt;= today + D
 *   on_time  : days &lt;= -(D+1)  -&gt; proximoPago &gt; today + D, or NULL
 * </pre>
 *
 * <p>A customer with no due date is on time, which is why every {@code on_time} query has to spell
 * out {@code IS NULL} — SQL comparisons would drop those rows silently.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Look a customer up by RUT in any format. {@code empresas.rut} stores "77353398-9" (dash, no
     * dots), so the column is stripped in SQL and compared against an already-normalized term.
     *
     * <p>Native, deliberately: this is the same statement the previous implementation ran, and it is
     * the lookup that both customer search and manual movement assignment depend on. The cost is
     * that {@code @SQLRestriction} is NOT applied to native queries, so the soft-delete condition is
     * spelled out by hand — without it, a deleted company would be payable.
     */
    @Query(value = """
            SELECT * FROM empresas
             WHERE UPPER(REPLACE(REPLACE(REPLACE(rut, '.', ''), '-', ''), ' ', '')) = :normalizedRut
               AND deleted_at IS NULL
            """, nativeQuery = true)
    Optional<Customer> findByNormalizedRut(@Param("normalizedRut") String normalizedRut);

    @Query("SELECT c FROM Customer c WHERE c.proximoPago < :today ORDER BY c.proximoPago")
    Page<Customer> findOverdue(@Param("today") LocalDate today, Pageable pageable);

    @Query("""
            SELECT c FROM Customer c
             WHERE c.proximoPago >= :today AND c.proximoPago <= :dueSoonEnd
             ORDER BY c.proximoPago
            """)
    Page<Customer> findDueSoon(@Param("today") LocalDate today,
                               @Param("dueSoonEnd") LocalDate dueSoonEnd,
                               Pageable pageable);

    @Query("""
            SELECT c FROM Customer c
             WHERE c.proximoPago > :dueSoonEnd OR c.proximoPago IS NULL
             ORDER BY c.proximoPago
            """)
    Page<Customer> findOnTime(@Param("dueSoonEnd") LocalDate dueSoonEnd, Pageable pageable);

    /**
     * Most urgent first: furthest overdue at the top, companies with no due date last. Expressed as
     * a query rather than a {@code Sort}, because "NULLS LAST" is not portable through Pageable.
     */
    @Query("""
            SELECT c FROM Customer c
             ORDER BY CASE WHEN c.proximoPago IS NULL THEN 1 ELSE 0 END, c.proximoPago
            """)
    Page<Customer> findAllOrderByUrgency(Pageable pageable);

    long countByProximoPagoLessThan(LocalDate today);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.proximoPago >= :today AND c.proximoPago <= :dueSoonEnd")
    long countDueSoon(@Param("today") LocalDate today, @Param("dueSoonEnd") LocalDate dueSoonEnd);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.proximoPago > :dueSoonEnd OR c.proximoPago IS NULL")
    long countOnTime(@Param("dueSoonEnd") LocalDate dueSoonEnd);
}
