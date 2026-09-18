package org.uvo.paymentplatform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.paymentplatform.security.AuthenticatedUser;

/**
 * Email and password login. No registration and no password reset, deliberately: accounts come
 * from the production user table.
 *
 * <p>Session cookie rather than a token, which is the cheapest arrangement that keeps the gateway
 * return leg working — see the security configuration for why that leg constrains the cookie.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String CREDENTIALS_INVALID = "Credenciales inválidas.";
    private static final String ACCOUNT_DISABLED = "Su cuenta está deshabilitada. Contacte al administrador.";

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    /**
     * @param remember accepted because the previous form offered it, but <b>not implemented</b>:
     *                 persistent login needs a token store and a decision about its lifetime, and
     *                 silently ignoring it is better than pretending it works.
     */
    public record LoginRequest(
            @NotBlank(message = "Ingrese su correo electrónico.")
            @Email(message = "Ingrese un correo electrónico válido.")
            String email,

            @NotBlank(message = "Ingrese su contraseña.")
            String password,

            boolean remember) {
    }

    /**
     * @param isAdmin what the frontend routes on: staff land on the customer list, everyone else on
     *                their own account. The decision is reported rather than performed, because a
     *                single-page frontend does its own navigation.
     */
    public record MeResponse(long id, String name, String email, boolean isAdmin, Long empresaId) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        Authentication authentication;

        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (DisabledException e) {
            // Only staff accounts can be disabled; a suspended customer is allowed in on purpose.
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(ApiError.of("email", ACCOUNT_DISABLED));
        } catch (BadCredentialsException e) {
            // Same answer for an unknown address and a wrong password: neither reveals which.
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(ApiError.of("email", CREDENTIALS_INVALID));
        }

        // A new session id for the authenticated session, which is what stops a session fixed by an
        // attacker before login from being the one that ends up authenticated.
        httpRequest.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // The response is required, not optional: this repository writes the context out through it,
        // and passing null here would fail at the first real login rather than at compile time.
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return ResponseEntity.ok(toResponse((AuthenticatedUser) authentication.getPrincipal()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (session != null) {
            session.invalidate();
        }

        SecurityContextHolder.clearContext();

        return ResponseEntity.noContent().build();
    }

    /**
     * Who is logged in. New here rather than ported: a server-rendered page always knew, and a
     * single-page frontend has to ask on load.
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(toResponse(user));
    }

    private MeResponse toResponse(AuthenticatedUser user) {
        return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.isAdmin(), user.getEmpresaId());
    }
}
