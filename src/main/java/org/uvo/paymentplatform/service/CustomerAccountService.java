package org.uvo.paymentplatform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.repository.CustomerRepository;
import org.uvo.paymentplatform.repository.UserRepository;

/**
 * Manual, staff-confirmed suspension and reactivation.
 *
 * <p><b>Suspension is never automatic</b> — not even long past the grace period. The system proposes
 * (the button appears once the customer is suspendable) and staff decide, with a confirmation step
 * in the UI; nothing may ever suspend on a schedule, because it would cut off real paying customers.
 *
 * <p>Both actions also enable or disable the company's user logins, through a statement that is
 * narrower than the source project's on purpose: it never touches staff accounts and it is
 * idempotent. See the repository for why that matters with up to 57 users per company.
 */
@Service
public class CustomerAccountService {

    private static final int USER_ENABLED = 1;
    private static final int USER_DISABLED = 0;

    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;

    public CustomerAccountService(CustomerRepository customerRepository, UserRepository userRepository) {
        this.customerRepository = customerRepository;
        this.userRepository = userRepository;
    }

    /** Thrown when the requested transition is not allowed from the customer's current state. */
    public static class NotAllowedException extends RuntimeException {
        public NotAllowedException(String message) {
            super(message);
        }
    }

    /**
     * @param suspendable the rule's verdict for this customer, computed by the caller with today's
     *                    date — kept as a parameter so this service does not decide the policy, only
     *                    apply it
     * @throws NotAllowedException when the customer is not yet suspendable or is already suspended
     */
    @Transactional
    public Customer suspend(Customer customer, boolean suspendable) {
        if (!suspendable) {
            throw new NotAllowedException("El cliente aún no puede ser suspendido.");
        }

        customer.setEstado(Customer.ESTADO_SUSPENDED);
        customerRepository.save(customer);
        userRepository.updateStatusByCustomerId(customer.getId(), USER_DISABLED);

        return customer;
    }

    /**
     * Manual override to reactivate without waiting for a payment — the customer paid through some
     * other channel, or the suspension was a mistake.
     *
     * @throws NotAllowedException when the customer is already active
     */
    @Transactional
    public Customer reactivate(Customer customer) {
        if (customer.isActive()) {
            throw new NotAllowedException("El cliente ya está activo.");
        }

        customer.setEstado(Customer.ESTADO_ACTIVE);
        customerRepository.save(customer);
        userRepository.updateStatusByCustomerId(customer.getId(), USER_ENABLED);

        return customer;
    }
}
