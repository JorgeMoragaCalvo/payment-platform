package org.uvo.paymentplatform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.paymentplatform.model.SuscriptorPayment;

import java.time.LocalDateTime;
import java.util.List;

public interface SuscriptorPaymentRepository extends JpaRepository<SuscriptorPayment, Long> {

    /**
     * Payments taken through Webpay on one calendar day, identified by the buy-order prefix this
     * module writes into {@code external_reference}. Used by the Transbank settlement comparison,
     * which must not count manually recorded or bank-transfer payments as part of a card deposit.
     *
     * <p>The day is passed as a half-open instant range rather than by casting the column to a date:
     * the comparison stays index-friendly, and it avoids depending on how the dialect renders a
     * cast. Callers pass {@code date.atStartOfDay()} and the start of the following day.
     */
    @Query("""
            SELECT p FROM SuscriptorPayment p
             WHERE p.externalReference LIKE CONCAT(:prefix, '%')
               AND p.fechaPago >= :from AND p.fechaPago < :until
             ORDER BY p.fechaPago
            """)
    List<SuscriptorPayment> findWebpayPaymentsBetween(@Param("prefix") String prefix,
                                                      @Param("from") LocalDateTime from,
                                                      @Param("until") LocalDateTime until);
}
