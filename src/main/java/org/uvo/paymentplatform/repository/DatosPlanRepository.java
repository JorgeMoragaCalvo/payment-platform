package org.uvo.paymentplatform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.paymentplatform.model.DatosPlan;

import java.util.List;
import java.util.Optional;

public interface DatosPlanRepository extends JpaRepository<DatosPlan, Integer> {

    /**
     * The company's active plan: the most recent row (highest id) with {@code estado = 1}. This is
     * what prices the charge and what supplies the renewal length, so "most recent + active" has to
     * be resolved here rather than by loading a collection.
     *
     * <p>Expressed as {@code findFirst…} on purpose. A company can have several active plan rows,
     * so a query returning {@code Optional} would blow up with an incorrect-result-size error on
     * exactly the customers that have the messiest data.
     */
    Optional<DatosPlan> findFirstByEmpresaIdAndEstadoOrderByIdDesc(Integer empresaId, Integer estado);

    /**
     * {@code datos_plan.empresa_id} is an {@code int} while company ids are {@code bigint}, so the
     * narrowing happens here, once, and throws rather than silently truncating an id that does not
     * fit.
     */
    default Optional<DatosPlan> findActivePlan(Long customerId) {
        return findFirstByEmpresaIdAndEstadoOrderByIdDesc(Math.toIntExact(customerId), 1);
    }

    /**
     * Every active plan, for the importer, which needs the expected charge of every company at once
     * and would otherwise issue a query per row over about 1.200 companies.
     *
     * <p>Ascending by id so that a caller collecting these into a map by company keeps the highest
     * id per company — the same "most recent active plan" the single-company lookup above resolves.
     */
    List<DatosPlan> findByEstadoOrderByIdAsc(Integer estado);
}
