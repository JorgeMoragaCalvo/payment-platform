package org.uvo.paymentplatform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.paymentplatform.model.BankMovement;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.SuscriptorPayment;
import org.uvo.paymentplatform.repository.BankMovementRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Turns a reconciled deposit into a payment. The one place a bank movement becomes money, whether a
 * staff member confirmed it in the review queue or the importer confirmed it on its own.
 *
 * <p>The two callers share this for the same reason both payment channels share
 * {@link RecordPaymentService}: the guard against paying twice has to be identical on every path,
 * or one of them will drift and stop guarding.
 *
 * <p>That guard is the conditional UPDATE in the repository. The movement is <i>claimed</i> inside
 * the transaction, and a caller who finds nothing to claim — a double click, a stale tab, a second
 * staff member, a re-import racing the queue — records nothing and gets an empty result back.
 */
@Service
public class ConfirmMovementService {

    private static final DateTimeFormatter NOTE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final BankMovementRepository bankMovementRepository;
    private final RecordPaymentService recordPaymentService;
    private final Clock clock;

    public ConfirmMovementService(BankMovementRepository bankMovementRepository,
                                  RecordPaymentService recordPaymentService,
                                  Clock clock) {
        this.bankMovementRepository = bankMovementRepository;
        this.recordPaymentService = recordPaymentService;
        this.clock = clock;
    }

    /**
     * @param userId whoever is confirming, or whoever uploaded the statement when {@code auto}. Not
     *               nullable, and constrained by a foreign key on the payment row.
     * @param auto   true when nobody reviewed this — the importer decided it from the match
     *               signals. It governs who is credited with the decision: nobody, when automatic.
     * @return empty when the movement was already resolved.
     */
    @Transactional
    public Optional<SuscriptorPayment> confirm(BankMovement movement, Customer customer, long userId, boolean auto) {

        // Nobody made this decision when it is automatic, so nobody is credited with it. The
        // auto-confirmed flag is what tells such a row apart from a processor payout tag, which
        // also leaves the reconciler null.
        int claimed = bankMovementRepository.claimForConfirmation(
                movement.getId(),
                customer.getId(),
                auto ? null : userId,
                LocalDateTime.now(clock),
                auto);

        if (claimed == 0) {
            return Optional.empty();
        }

        SuscriptorPayment payment = recordPaymentService.apply(
                customer,
                movement.getAmountClp(),
                movement.getPostedAt().atStartOfDay(),
                RecordPaymentService.SOURCE_BANK_TRANSFER,
                movement.getReference(),
                userId,
                notes(movement, auto));

        bankMovementRepository.attachPayment(movement.getId(), payment.getId());

        return Optional.of(payment);
    }

    private String notes(BankMovement movement, boolean auto) {
        String date = movement.getPostedAt().format(NOTE_DATE);

        return auto
                ? "Transferencia bancaria conciliada automáticamente (" + date + ")"
                : "Transferencia bancaria conciliada (" + date + ")";
    }
}
