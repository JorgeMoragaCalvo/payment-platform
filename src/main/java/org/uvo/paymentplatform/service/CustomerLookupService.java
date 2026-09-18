package org.uvo.paymentplatform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.repository.CustomerRepository;
import org.uvo.paymentplatform.support.Rut;

import java.util.Optional;

/**
 * Looks a customer up by numeric id or by RUT, in any format.
 *
 * <p>The id-or-RUT rule lived in three places in the source project — the staff lookup, the model
 * scope and the manual assignment in reconciliation — with a comment on each warning to change all
 * three together. It lives here once.
 */
@Service
public class CustomerLookupService {

    public static final String RUT_INVALID = "El RUT ingresado no es válido.";

    /** A purely numeric term this short is a customer id; anything else has to be a RUT. */
    private static final int MAX_ID_DIGITS = 6;

    private final CustomerRepository customerRepository;

    public CustomerLookupService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /** Thrown for a term that is neither a short id nor a RUT with a valid check digit. */
    public static class InvalidRutException extends RuntimeException {
        public InvalidRutException() {
            super(RUT_INVALID);
        }
    }

    /**
     * @throws InvalidRutException before touching the database, when the term cannot be a RUT: the
     *                             check digit is validated first, so a typo is answered as a typo
     *                             rather than as "no such customer"
     */
    @Transactional(readOnly = true)
    public Optional<Customer> byRutOrId(String term) {
        String trimmed = term == null ? "" : term.trim();

        if (looksLikeId(trimmed)) {
            return customerRepository.findById(Long.parseLong(trimmed));
        }

        if (!Rut.isValid(trimmed)) {
            throw new InvalidRutException();
        }

        return customerRepository.findByNormalizedRut(Rut.normalize(trimmed));
    }

    private static boolean looksLikeId(String term) {
        return !term.isEmpty()
                && term.length() <= MAX_ID_DIGITS
                && term.chars().allMatch(Character::isDigit);
    }
}
