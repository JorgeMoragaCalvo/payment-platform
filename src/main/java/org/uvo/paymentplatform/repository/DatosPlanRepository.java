package org.uvo.paymentplatform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.paymentplatform.model.DatosPlan;

import java.util.Optional;

public interface DatosPlanRepository extends JpaRepository<DatosPlan, Long> {

    /**
     * The company's active plan: the most recent row (highest id) with {@code estado = 1}. This is
     * what prices the Webpay charge and what supplies the renewal length, so "most recent + active"
     * has to be resolved here rather than by loading the collection.
     *
     * <p>Expressed as {@code findFirst…} on purpose. A company can have several active plan rows,
     * so a plain {@code @Query} returning {@code Optional} would blow up with an
     * incorrect-result-size error on exactly the customers that have the messiest data.
     */
    Optional<DatosPlan> findFirstByCustomer_IdAndEstadoOrderByIdDesc(Long customerId, Integer estado);

    default Optional<DatosPlan> findActivePlan(Long customerId) {
        return findFirstByCustomer_IdAndEstadoOrderByIdDesc(customerId, 1);
    }
}
