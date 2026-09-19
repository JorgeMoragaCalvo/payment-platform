package org.uvo.paymentplatform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Session-cookie security for a single-page frontend plus one non-API endpoint.
 *
 * <p>Two constraints come from the payment gateway rather than from any framework, and both were
 * live bugs in the source project:
 *
 * <ul>
 *   <li><b>The gateway's return is a cross-site POST with no CSRF token.</b> Without an exemption
 *       for that one path, every real payment ends in a rejected request — after the card has been
 *       charged. The exemption is safe because the route trusts nothing in the request body: it
 *       authenticates the payment by committing the token with the gateway and looking the buy
 *       order up locally.
 *   <li><b>That same POST carries no session cookie</b>, because the cookie is SameSite=Lax and a
 *       Lax cookie is not sent on a cross-site POST. The pending payment therefore lives in its own
 *       table, keyed by buy order, never in the session. <b>Do not weaken the cookie to
 *       SameSite=None to "fix" this</b> — that trades every cookie's protection for one route.
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * The gateway return path. Kept character for character: it is registered with the gateway and
     * it is the string the CSRF exemption below matches, so changing it breaks both at once.
     */
    public static final String WEBPAY_RETURN_PATH = "/payment-alert/webpay/return";

    public static final String LOGIN_PATH = "/api/auth/login";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // The frontend reads this cookie and echoes it back as a header, which is the usual
        // arrangement for a single-page app. Clearing the request-attribute name opts out of the
        // deferred-token behaviour that otherwise makes the cookie appear only after the first
        // write request.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers(WEBPAY_RETURN_PATH))

                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(LOGIN_PATH, WEBPAY_RETURN_PATH).permitAll()
                        // Staff-only rules are not listed here: they are method-level annotations on
                        // the controllers, so every request re-checks them through the full filter
                        // chain. The source project had to re-assert the admin gate by hand inside
                        // each action because its component requests bypassed the route middleware;
                        // that footgun does not exist here.
                        .anyRequest().authenticated())

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // The equivalent of regenerating the session on login.
                        .sessionFixation(fixation -> fixation.changeSessionId()))

                // An API answers with a status code. Redirecting to a login page would hand the
                // frontend an HTML document where it expected JSON. Only the entry point is
                // replaced: an unauthenticated request is 401, while a rejected CSRF token keeps
                // Spring's default 403, so the frontend can tell "log in again" from "your token is
                // stale" — a stale token after a backend restart used to surface as "session
                // expired", which sent people in circles.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, e) ->
                                response.sendError(HttpStatus.FORBIDDEN.value())))

                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }

    /**
     * Plain bcrypt, not a delegating encoder: the stored hashes are bare {@code $2y$...} strings
     * with no algorithm prefix, which is what the previous framework wrote. A delegating encoder
     * would look for a {@code {bcrypt}} prefix, find none, and reject every existing password.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(DatabaseUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);

        return new ProviderManager(provider);
    }
}
