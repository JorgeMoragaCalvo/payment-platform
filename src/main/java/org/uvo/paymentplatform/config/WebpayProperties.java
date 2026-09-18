package org.uvo.paymentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Transbank Webpay Plus credentials.
 *
 * <p>The sandbox defaults are deliberately NOT hardcoded here. In the source project they come from
 * the payment SDK's own constants, and the values must be taken from whichever Java SDK the
 * integration spike settles on rather than copied from memory — a wrong commerce code fails at the
 * worst possible moment, mid-checkout.
 *
 * <p>What must survive from the source project is the guard around these values: an environment of
 * anything other than {@code integration} or {@code production} has to throw, and {@code production}
 * with either credential still equal to the SDK default has to throw too. Both cases used to fall
 * through to the sandbox silently, so a production deploy that forgot its environment variables
 * took no real money and looked fine.
 */
@ConfigurationProperties(prefix = "webpay")
public record WebpayProperties(
        String commerceCode,
        String apiKey,
        @DefaultValue(WebpayProperties.ENV_INTEGRATION) String environment) {

    public static final String ENV_INTEGRATION = "integration";
    public static final String ENV_PRODUCTION = "production";
}
