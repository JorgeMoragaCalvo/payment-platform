package org.uvo.paymentplatform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.paymentplatform.cartola.Movement;
import org.uvo.paymentplatform.config.BankReconciliationProperties;
import org.uvo.paymentplatform.model.BankMovement;
import org.uvo.paymentplatform.model.BankStatement;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.DatosPlan;
import org.uvo.paymentplatform.reconciliation.CandidateCompany;
import org.uvo.paymentplatform.reconciliation.MatchEngine;
import org.uvo.paymentplatform.reconciliation.MatchResult;
import org.uvo.paymentplatform.reconciliation.ScoredCandidate;
import org.uvo.paymentplatform.repository.BankMovementRepository;
import org.uvo.paymentplatform.repository.BankStatementRepository;
import org.uvo.paymentplatform.repository.CustomerRepository;
import org.uvo.paymentplatform.repository.DatosPlanRepository;
import org.uvo.paymentplatform.support.Rut;
import org.uvo.paymentplatform.support.Text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes an imported statement and its movements, and works out which deposits could be reconciled
 * without a human.
 *
 * <p><b>This is a separate bean from the importer on purpose.</b> The two have to be two distinct
 * transaction boundaries: the statement and its movements commit here, and only then does the
 * importer record payments. Keeping both in one class would mean calling this method from inside
 * the same bean, where the transactional proxy does not apply and the annotation silently does
 * nothing.
 */
@Service
public class CartolaStore {

    private final MatchEngine engine;
    private final CustomerRules customerRules;
    private final BankStatementRepository bankStatementRepository;
    private final BankMovementRepository bankMovementRepository;
    private final CustomerRepository customerRepository;
    private final DatosPlanRepository datosPlanRepository;
    private final BankReconciliationProperties properties;

    public CartolaStore(MatchEngine engine,
                        CustomerRules customerRules,
                        BankStatementRepository bankStatementRepository,
                        BankMovementRepository bankMovementRepository,
                        CustomerRepository customerRepository,
                        DatosPlanRepository datosPlanRepository,
                        BankReconciliationProperties properties) {
        this.engine = engine;
        this.customerRules = customerRules;
        this.bankStatementRepository = bankStatementRepository;
        this.bankMovementRepository = bankMovementRepository;
        this.customerRepository = customerRepository;
        this.datosPlanRepository = datosPlanRepository;
        this.properties = properties;
    }

    /**
     * @param autoConfirm movement id keyed by company id, for the deposits that qualify to be
     *                    reconciled without a human. Filled here, acted on after this commits.
     */
    public record StoreResult(BankStatement statement, Map<Long, Long> autoConfirm) {
    }

    /** A movement ready to store, plus the company it would be auto-confirmed against, if any. */
    private record Prepared(BankMovement movement, Long autoConfirmEmpresaId) {
    }

    @Transactional
    public StoreResult store(List<Movement> movements,
                             String bank,
                             String originalName,
                             String storedPath,
                             String hash,
                             Long userId) {

        List<LocalDate> dates = movements.stream().map(Movement::postedAt).sorted().toList();

        BankStatement statement = bankStatementRepository.save(BankStatement.builder()
                .bank(bank)
                .periodStart(dates.get(0))
                .periodEnd(dates.get(dates.size() - 1))
                .originalFilename(originalName)
                .storedPath(storedPath)
                .fileHash(hash)
                .importedBy(userId)
                .movementCount(0)
                .build());

        List<CandidateCompany> candidates = candidates();
        Map<Long, Long> autoConfirm = new LinkedHashMap<>();
        int imported = 0;

        for (Movement movement : movements) {
            // Statements are exported by date range and those ranges overlap, so the same deposit
            // routinely arrives in two files. The row hash keeps it from being offered twice.
            //
            // Unlike the source project, a duplicate that slips past this check — two simultaneous
            // imports of the same file — is NOT skipped but fails the whole import: a constraint
            // violation inside a JPA transaction leaves the persistence context unusable, so there
            // is nothing safe to continue with. The unique index still guarantees the outcome that
            // matters, which is that no deposit is ever reconciled twice.
            if (bankMovementRepository.existsByRowHash(movement.rowHash())) {
                continue;
            }

            Prepared prepared = prepare(movement, statement, candidates);
            BankMovement stored = bankMovementRepository.save(prepared.movement());
            imported++;

            // One automatic payment per company per statement. A second deposit of the same amount
            // from the same payer is as likely to be a duplicate transfer as a second month, and
            // that is a judgement call — it stays in the queue.
            //
            // The id only exists once the row is saved, which is why this is recorded here rather
            // than while the movement is being built.
            Long empresaId = prepared.autoConfirmEmpresaId();

            if (empresaId != null && !autoConfirm.containsKey(empresaId)) {
                autoConfirm.put(empresaId, stored.getId());
            }
        }

        statement.setMovementCount(imported);

        return new StoreResult(statement, autoConfirm);
    }

    private Prepared prepare(Movement movement, BankStatement statement, List<CandidateCompany> candidates) {
        BankMovement.BankMovementBuilder builder = BankMovement.builder()
                .statement(statement)
                .postedAt(movement.postedAt())
                .description(movement.description())
                .reference(movement.reference())
                .amount(BigDecimal.valueOf(movement.amount()))
                .direction(movement.direction())
                .counterpartyRut(movement.counterpartyRut())
                .rowHash(movement.rowHash())
                .status(BankMovement.Status.UNMATCHED);

        // A processor payout is not a customer paying — it is the money from card charges already
        // recorded. It belongs to the settlement comparison, not the customer review queue.
        if (isTransbankPayout(movement)) {
            return new Prepared(builder
                    .status(BankMovement.Status.MATCHED)
                    .matchReason(BankMovement.MATCH_REASON_TRANSBANK_PAYOUT)
                    .build(), null);
        }

        MatchResult result = engine.match(movement, candidates);

        if (!result.hasSuggestion()) {
            return new Prepared(builder.build(), null);
        }

        ScoredCandidate best = result.best();
        Long autoConfirmEmpresaId = qualifiesForAutoConfirm(result, best) ? best.company().id() : null;

        BankMovement prepared = builder
                // Still written as a suggestion. Auto-confirmation happens after this transaction
                // commits, so a movement whose payment could not be recorded is left where staff
                // will see it.
                .status(BankMovement.Status.SUGGESTED)
                .customer(customerRepository.getReferenceById(best.company().id()))
                .matchConfidence(Math.min(100, best.score()))
                .matchReason(best.reasonText() + caveat(result))
                .build();

        return new Prepared(prepared, autoConfirmEmpresaId);
    }

    /**
     * The evidence that leaves a human nothing to add: the payer's RUT in the glosa, check digit
     * validated, and the plan amount to the peso, with no other company within the tie margin.
     *
     * <p>Decided on <b>which signals fired, not on the score</b> — the score is a blend, and 80
     * points of weak signals is not the same evidence as these two. Requiring the exact amount also
     * keeps a partial transfer off this path: a payment buys a full period whatever its amount, so a
     * shortfall has to stay a visible choice.
     */
    private boolean qualifiesForAutoConfirm(MatchResult result, ScoredCandidate best) {
        if (properties.autoConfirm() == null || !properties.autoConfirm().enabled()) {
            return false;
        }

        // Two companies this close apart means the evidence points at more than one of them,
        // however strong it is.
        if (result.isAmbiguous()) {
            return false;
        }

        return best.hasAllSignals(properties.autoConfirm().requireSignals());
    }

    /**
     * Why the suggestion still needs a human. The two reasons are different and staff act on them
     * differently: a tie means check the other candidate, a weak match means check the customer.
     */
    private String caveat(MatchResult result) {
        if (result.isAmbiguous()) {
            return " (revisar: hay otro candidato igual de probable)";
        }
        if (!result.isHighConfidence()) {
            return " (revisar: coincidencia parcial)";
        }
        return "";
    }

    private boolean isTransbankPayout(Movement movement) {
        if (movement.direction() != BankMovement.Direction.CREDIT) {
            return false;
        }

        List<String> patterns = properties.transbankGlosaPatterns();

        if (patterns == null) {
            return false;
        }

        String glosa = Text.normalize(movement.description());

        for (String pattern : patterns) {
            String needle = Text.normalize(pattern);

            // Whole words only. Tagging is silent and takes the movement out of the customer queue,
            // so a company name that merely contains "tbk" must not be mistaken for a payout.
            if (!needle.isEmpty()
                    && Pattern.compile("\\b" + Pattern.quote(needle) + "\\b").matcher(glosa).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Every company, scored against once per movement. Built a single time per import: the whole
     * table is around 1.200 rows, so loading it is cheaper than a query per movement — and the
     * active plans are loaded in one go for the same reason, since the expected charge comes from
     * them and a per-company lookup would be a query per row.
     */
    private List<CandidateCompany> candidates() {
        Map<Long, DatosPlan> activePlans = new HashMap<>();

        // Ascending by id, so the last one written per company is the highest id — the same "most
        // recent active plan" the per-company lookup resolves.
        for (DatosPlan plan : datosPlanRepository.findByEstadoOrderByIdAsc(1)) {
            if (plan.getEmpresaId() != null) {
                activePlans.put(plan.getEmpresaId().longValue(), plan);
            }
        }

        List<Customer> customers = customerRepository.findAll();
        List<CandidateCompany> candidates = new ArrayList<>(customers.size());

        for (Customer customer : customers) {
            String name = customer.getName();

            candidates.add(new CandidateCompany(
                    customer.getId(),
                    Rut.normalize(customer.getRut()),
                    name == null ? "" : name,
                    customerRules.chargeAmount(activePlans.get(customer.getId())),
                    customer.getProximoPago()));
        }

        return candidates;
    }
}
