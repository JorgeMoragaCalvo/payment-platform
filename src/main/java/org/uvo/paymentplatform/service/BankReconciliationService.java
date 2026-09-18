package org.uvo.paymentplatform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.paymentplatform.config.BankReconciliationProperties;
import org.uvo.paymentplatform.model.BankMovement;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.SuscriptorPayment;
import org.uvo.paymentplatform.repository.BankMovementRepository;
import org.uvo.paymentplatform.repository.CustomerRepository;
import org.uvo.paymentplatform.repository.DatosPlanRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The review queue's decisions: what the import could not settle on its own, decided by a person.
 *
 * <p>Confirming here is what records a payment, and it is always a deliberate action. That mirrors
 * the module's rule for suspension: the system proposes, staff decide, because both directions of a
 * wrong guess cost a real customer real money.
 */
@Service
public class BankReconciliationService {

    public static final String CUSTOMER_NOT_FOUND = "No se encontró la empresa seleccionada.";

    private final BankMovementRepository bankMovementRepository;
    private final CustomerRepository customerRepository;
    private final DatosPlanRepository datosPlanRepository;
    private final ConfirmMovementService confirmMovementService;
    private final CustomerRules customerRules;
    private final BankReconciliationProperties properties;
    private final Clock clock;

    public BankReconciliationService(BankMovementRepository bankMovementRepository,
                                     CustomerRepository customerRepository,
                                     DatosPlanRepository datosPlanRepository,
                                     ConfirmMovementService confirmMovementService,
                                     CustomerRules customerRules,
                                     BankReconciliationProperties properties,
                                     Clock clock) {
        this.bankMovementRepository = bankMovementRepository;
        this.customerRepository = customerRepository;
        this.datosPlanRepository = datosPlanRepository;
        this.confirmMovementService = confirmMovementService;
        this.customerRules = customerRules;
        this.properties = properties;
        this.clock = clock;
    }

    /** The movement is already decided, or is not a deposit — nothing to do. */
    public static class AlreadyResolvedException extends RuntimeException {
        public AlreadyResolvedException() {
            super("El movimiento ya fue resuelto.");
        }
    }

    public static class CustomerNotFoundException extends RuntimeException {
        public CustomerNotFoundException() {
            super(CUSTOMER_NOT_FOUND);
        }
    }

    /**
     * What the confirmation step has to say before money moves: which company, how much arrived,
     * and — the part staff cannot see anywhere else — whether the deposit falls short of the plan
     * charge. The engine only ever <i>scores</i> on the amount; without this, a customer who
     * transfers half the charge would still buy a whole period on a single click.
     */
    public record Confirmation(BankMovement movement, Customer customer, Integer chargeAmount, int shortfall) {
    }

    @Transactional(readOnly = true)
    public Optional<Confirmation> confirmation(long movementId, long empresaId) {
        BankMovement movement = bankMovementRepository.findById(movementId).orElse(null);
        Customer customer = customerRepository.findById(empresaId).orElse(null);

        if (movement == null || customer == null) {
            return Optional.empty();
        }

        Integer chargeAmount = customerRules.chargeAmount(
                datosPlanRepository.findActivePlan(customer.getId()).orElse(null));

        return Optional.of(new Confirmation(movement, customer, chargeAmount,
                shortfall(movement.getAmountClp(), chargeAmount)));
    }

    /**
     * Records the payment this deposit represents — the only action on this screen that moves money.
     *
     * <p><b>The company id is required, with no fallback to the row's own suggestion.</b> The source
     * project guarded this with server-held "which row is the confirm step open for" state, which a
     * stateless API does not have. Requiring the caller to name the company means a replayed or
     * stale request can only ever record the payment against the company the operator was actually
     * looking at — never against whatever the row happened to suggest since.
     *
     * <p>The claim inside {@link ConfirmMovementService} is what prevents a double payment: a second
     * click, a stale tab or a second staff member finds nothing left to claim.
     *
     * @return empty when the movement was already claimed by another request
     * @throws AlreadyResolvedException  when the movement is resolved, or is not a deposit
     * @throws CustomerNotFoundException when the named company does not exist
     */
    @Transactional
    public Optional<SuscriptorPayment> confirm(long movementId, long empresaId, long userId) {
        BankMovement movement = bankMovementRepository.findById(movementId)
                .orElseThrow(AlreadyResolvedException::new);

        if (movement.isResolved() || movement.getDirection() != BankMovement.Direction.CREDIT) {
            throw new AlreadyResolvedException();
        }

        Customer customer = customerRepository.findById(empresaId)
                .orElseThrow(CustomerNotFoundException::new);

        return confirmMovementService.confirm(movement, customer, userId, false);
    }

    /**
     * Not a subscription payment — a refund, a supplier, an internal transfer. Ignoring only clears
     * it from the queue; nothing is written to the customer.
     */
    @Transactional
    public BankMovement ignore(long movementId, long userId) {
        BankMovement movement = bankMovementRepository.findById(movementId)
                .orElseThrow(AlreadyResolvedException::new);

        if (movement.isResolved()) {
            throw new AlreadyResolvedException();
        }

        movement.setStatus(BankMovement.Status.IGNORED);
        movement.setReconciledBy(userId);
        movement.setReconciledAt(LocalDateTime.now(clock));

        return bankMovementRepository.save(movement);
    }

    /**
     * Undo for the two decisions that did not move money: an ignore, and the automatic payout tag
     * the importer applies. Both remove a movement from the queue without anyone confirming
     * anything, so both need a way back — a customer transfer whose glosa merely mentions the card
     * processor would otherwise be lost silently.
     *
     * <p><b>A movement that produced a payment is deliberately not reversible here.</b> Unwinding a
     * recorded payment is a financial decision, not a queue correction, and it has no UI.
     */
    @Transactional
    public BankMovement returnToQueue(long movementId) {
        BankMovement movement = bankMovementRepository.findById(movementId)
                .orElseThrow(AlreadyResolvedException::new);

        if (movement.getPayment() != null) {
            throw new AlreadyResolvedException();
        }

        boolean hasCustomer = movement.getCustomer() != null;

        movement.setStatus(hasCustomer ? BankMovement.Status.SUGGESTED : BankMovement.Status.UNMATCHED);
        if (!hasCustomer) {
            movement.setMatchReason(null);
        }
        movement.setReconciledBy(null);
        movement.setReconciledAt(null);

        return bankMovementRepository.save(movement);
    }

    /**
     * How far below the plan charge a deposit is, in pesos, or 0 when it is close enough. Reuses the
     * tolerance the match engine scores with, so the queue and the warning agree on what "the right
     * amount" means.
     */
    int shortfall(int paid, Integer charge) {
        if (charge == null || charge <= 0 || paid >= charge) {
            return 0;
        }

        int tolerance = Math.max(
                properties.amountToleranceClp(),
                (int) Math.round(charge * properties.amountTolerancePct() / 100));

        int missing = charge - paid;

        return missing > tolerance ? missing : 0;
    }
}
