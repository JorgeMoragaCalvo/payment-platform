package org.uvo.paymentplatform.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.User;
import org.uvo.paymentplatform.repository.UserRepository;

/**
 * Loads users from the existing user table. There is no registration and no password reset:
 * accounts come from the production data, not from self sign-up.
 *
 * <p><b>Logically deleted users cannot authenticate</b>, and that is also a change rather than a
 * port. Production has hundreds of rows with a deletion timestamp and the source model never
 * filtered them, so a terminated employee could still log in. The filter lives on the entity, which
 * means it applies to this lookup without anything here having to remember it.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Transactional so the company association can be read here: the id is copied into the
     * principal while this transaction is open, because the principal outlives it by sitting in the
     * session.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                // The message is never shown: the login endpoint answers with the same wording for
                // an unknown address and a wrong password, so neither reveals which it was.
                .orElseThrow(() -> new UsernameNotFoundException("No user for " + email));

        Customer customer = user.getCustomer();

        return AuthenticatedUser.of(user, customer == null ? null : customer.getId());
    }
}
