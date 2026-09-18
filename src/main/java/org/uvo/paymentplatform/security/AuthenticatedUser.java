package org.uvo.paymentplatform.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.uvo.paymentplatform.model.User;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal.
 *
 * <p>It carries the company id as a plain value rather than the association: the id is resolved
 * while the loading transaction is still open, so nothing here can trip a lazy-loading failure once
 * the principal is sitting in a session and the transaction is long gone.
 */
public class AuthenticatedUser implements UserDetails {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** Active. The column is nullable, so anything other than 1 counts as not active. */
    private static final int STATUS_ACTIVE = 1;

    private final long id;
    private final String email;
    private final String name;
    private final String password;
    private final boolean admin;
    private final boolean enabled;
    private final Long empresaId;

    private AuthenticatedUser(long id, String email, String name, String password,
                              boolean admin, boolean enabled, Long empresaId) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.password = password;
        this.admin = admin;
        this.enabled = enabled;
        this.empresaId = empresaId;
    }

    /**
     * @param empresaId the company id, read while the loading transaction is open
     */
    public static AuthenticatedUser of(User user, Long empresaId) {
        return new AuthenticatedUser(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getPassword(),
                user.isAdmin(),
                isEnabled(user),
                empresaId);
    }

    /**
     * <b>The asymmetry here is deliberate, and it is a change from the source project rather than a
     * port of it.</b> Today a suspended user of either kind can log in, which is right for one and
     * wrong for the other:
     *
     * <ul>
     *   <li>A suspended <b>company</b> user must still get in. Locking them out would hide the
     *       payment button from exactly the customers who need it to be reactivated — paying is
     *       what lifts the suspension.
     *   <li>A suspended <b>staff</b> user must not get in. Their account was disabled on purpose,
     *       and the staff pages can suspend customers and record payments.
     * </ul>
     *
     * <p>So this is not simply {@code status == 1}: the status only gates staff.
     */
    private static boolean isEnabled(User user) {
        if (!user.isAdmin()) {
            return true;
        }

        return user.getStatus() != null && user.getStatus() == STATUS_ACTIVE;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return admin ? List.of(new SimpleGrantedAuthority(ROLE_ADMIN)) : List.of();
    }

    @Override
    public String getPassword() {
        return password;
    }

    /** The username is the email: that is what the login form collects and what the column holds. */
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public boolean isAdmin() {
        return admin;
    }

    /** Null for a staff account with no company, which is the normal shape for staff. */
    public Long getEmpresaId() {
        return empresaId;
    }
}
