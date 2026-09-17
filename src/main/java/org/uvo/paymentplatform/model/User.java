package org.uvo.paymentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * An application user. {@code role} distinguishes staff ({@code "admin"}) from customer logins
 * (null); the company association is by {@code empresa_id}.
 *
 * <p><b>{@code deletedAt} and the restriction below are deliberate, and are a fix rather than a
 * port.</b> The production {@code users} table has {@code deleted_at} with 567 logically deleted
 * rows, and the Laravel model did not use soft deletes — so a terminated employee could still log
 * in. Two consequences:
 *
 * <ul>
 *   <li>The local mirror schema does NOT have this column yet. It has to be added to the mirror
 *       migration, or every query against a local database fails.
 *   <li>{@code @SQLRestriction} filters entity loads and queries, but Hibernate does not apply it
 *       to bulk update statements. The bulk update that suspends a company's users must therefore
 *       still spell out {@code deleted_at IS NULL} itself.
 * </ul>
 *
 * <p>{@code status} is not a login gate on its own: a suspended <i>company</i> user must still be
 * able to log in, because paying is what reactivates them. A suspended <i>staff</i> user must not.
 * That asymmetry belongs in the authentication layer.
 */
@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class User {

    /** The only value of {@code role} that unlocks the staff pages. */
    public static final String ROLE_ADMIN = "admin";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    /** Bcrypt hash written by Laravel ({@code $2y$}), which Spring's encoder verifies as-is. */
    @Column(nullable = false)
    private String password;

    @Column(name = "remember_token", length = 100)
    private String rememberToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empresa_id")
    private Customer customer;

    @Column(nullable = false)
    @Builder.Default
    private Integer status = 1;

    /** Nullable: null is a plain company user, {@code "admin"} is staff. */
    @Column(length = 20)
    private String role;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
