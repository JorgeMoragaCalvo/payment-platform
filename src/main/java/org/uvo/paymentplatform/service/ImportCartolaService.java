package org.uvo.paymentplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.uvo.paymentplatform.cartola.CartolaException;
import org.uvo.paymentplatform.cartola.CartolaReader;
import org.uvo.paymentplatform.cartola.Movement;
import org.uvo.paymentplatform.model.BankMovement;
import org.uvo.paymentplatform.model.BankStatement;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.repository.BankMovementRepository;
import org.uvo.paymentplatform.repository.BankStatementRepository;
import org.uvo.paymentplatform.repository.CustomerRepository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * Imports an uploaded bank statement: parses it, stores the file for audit, writes one movement per
 * line, and asks the match engine which company each deposit might belong to.
 *
 * <p>Almost everything it produces is a suggestion for a staff member to decide. The exception is a
 * deposit that carries the payer's RUT <i>and</i> the exact plan amount with no rival candidate:
 * there is nothing left for a human to add, so the import records that payment itself and the
 * movement arrives already reconciled.
 *
 * <p>That behaviour has a kill switch in configuration, because <b>a recorded payment cannot be
 * undone from the UI</b>.
 */
@Service
public class ImportCartolaService {

    private static final Logger log = LoggerFactory.getLogger(ImportCartolaService.class);

    private final CartolaReader reader;
    private final CartolaStore store;
    private final CartolaStorage storage;
    private final ConfirmMovementService confirmer;
    private final BankStatementRepository bankStatementRepository;
    private final BankMovementRepository bankMovementRepository;
    private final CustomerRepository customerRepository;

    public ImportCartolaService(CartolaReader reader,
                                CartolaStore store,
                                CartolaStorage storage,
                                ConfirmMovementService confirmer,
                                BankStatementRepository bankStatementRepository,
                                BankMovementRepository bankMovementRepository,
                                CustomerRepository customerRepository) {
        this.reader = reader;
        this.store = store;
        this.storage = storage;
        this.confirmer = confirmer;
        this.bankStatementRepository = bankStatementRepository;
        this.bankMovementRepository = bankMovementRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * @param autoConfirmedCount how many deposits were reconciled without a human. Reported to the
     *                           caller rather than stored: it is per-request state, not a column.
     */
    public record ImportResult(BankStatement statement, int autoConfirmedCount) {
    }

    /**
     * @param bank key of a configured bank layout
     * @throws CartolaException when the file is unreadable or was already imported
     */
    public ImportResult importCartola(byte[] contents, String originalName, String bank, Long userId) {
        String hash = sha256Hex(contents);

        if (bankStatementRepository.existsByFileHash(hash)) {
            throw new CartolaException("Esta cartola ya fue importada anteriormente.");
        }

        // Parse before storing anything: a file we cannot read should not leave a statement row or
        // an orphaned upload behind.
        List<Movement> movements = reader.read(contents, bank);

        String storedPath = storage.put(hash, contents);

        CartolaStore.StoreResult result;
        try {
            result = store.store(movements, bank, originalName, storedPath, hash, userId);
        } catch (RuntimeException e) {
            // Nothing was imported, so the upload is now an orphan the file-hash guard would never
            // point at again.
            storage.delete(storedPath);
            throw e;
        }

        // Deliberately outside the import transaction: recording a payment touches three more
        // tables, and a failure there must leave the imported movements in the queue rather than
        // roll the whole statement back.
        int confirmed = confirmAll(result.autoConfirm(), userId);

        return new ImportResult(result.statement(), confirmed);
    }

    /**
     * Records the payment for each qualifying deposit, after the import itself has committed, one
     * movement at a time.
     *
     * <p>A failure here is logged and the movement left as a suggestion: it reappears in the review
     * queue, which is the outcome the whole module defaults to. <b>Silence is the one thing that is
     * not acceptable</b> — a deposit that neither paid a customer nor asked for review is money lost
     * from the books.
     *
     * @param autoConfirm movement id keyed by company id
     */
    private int confirmAll(Map<Long, Long> autoConfirm, Long userId) {
        // The payment row requires a user, so an import with no known user cannot record anything;
        // the queue takes over.
        if (autoConfirm.isEmpty() || userId == null) {
            return 0;
        }

        int confirmed = 0;

        for (Map.Entry<Long, Long> entry : autoConfirm.entrySet()) {
            Long empresaId = entry.getKey();
            Long movementId = entry.getValue();

            BankMovement movement = bankMovementRepository.findById(movementId).orElse(null);
            Customer customer = customerRepository.findById(empresaId).orElse(null);

            if (movement == null || customer == null || movement.isResolved()) {
                continue;
            }

            try {
                if (confirmer.confirm(movement, customer, userId, true).isPresent()) {
                    confirmed++;
                }
            } catch (RuntimeException e) {
                log.error("Auto-conciliación de movimiento bancario falló: movementId={} empresaId={} "
                                + "amount={} postedAt={} reference={}",
                        movementId, empresaId, movement.getAmountClp(), movement.getPostedAt(),
                        movement.getReference(), e);
            }
        }

        return confirmed;
    }

    private static String sha256Hex(byte[] contents) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(contents);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
