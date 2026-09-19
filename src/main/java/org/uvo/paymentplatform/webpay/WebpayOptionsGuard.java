package org.uvo.paymentplatform.webpay;

import org.springframework.stereotype.Component;
import org.uvo.paymentplatform.config.WebpayProperties;

/**
 * Refuses a gateway configuration that would silently take no real money.
 *
 * <p>Two mistakes used to fall through to the sandbox without a word: an environment value that was
 * neither {@code integration} nor {@code production} (a deploy that set the variable to "prod"
 * believed it was live and charged nobody), and a production environment whose credentials were
 * still the defaults. Both are now errors.
 *
 * <p>The check is <b>lazy</b> on purpose, run when a payment starts rather than when the container
 * boots — the same choice the source project made. That has a consequence for deployment that the
 * runbook spells out: a green deploy proves nothing about the gateway configuration, so the deploy
 * checklist has to include starting a real checkout. Whether to add an eager check at startup as
 * well is still an open decision.
 *
 * <p>"Still the defaults" means blank <i>or</i> equal to Transbank's published sandbox credentials:
 * the sandbox values are what the client falls back to, so a production deploy that forgot the
 * variables would otherwise point at the live host with the public test keys.
 */
@Component
public class WebpayOptionsGuard {

    private final WebpayProperties properties;

    public WebpayOptionsGuard(WebpayProperties properties) {
        this.properties = properties;
    }

    /** @throws IllegalStateException when the configuration must not be used to charge anyone */
    public void assertUsable() {
        String environment = properties.environment();

        if (!WebpayProperties.ENV_INTEGRATION.equals(environment)
                && !WebpayProperties.ENV_PRODUCTION.equals(environment)) {
            throw new IllegalStateException(
                    "WEBPAY_ENVIRONMENT must be \"" + WebpayProperties.ENV_INTEGRATION + "\" or \""
                            + WebpayProperties.ENV_PRODUCTION + "\", got \"" + environment + "\".");
        }

        if (WebpayProperties.ENV_PRODUCTION.equals(environment)) {
            assertRealCredentials(properties.commerceCode(), properties.apiKey());
        }
    }

    public boolean isProduction() {
        return WebpayProperties.ENV_PRODUCTION.equals(properties.environment());
    }

    private static void assertRealCredentials(String commerceCode, String apiKey) {
        if (commerceCode == null || commerceCode.isBlank()
                || commerceCode.equals(WebpayRestClient.INTEGRATION_COMMERCE_CODE)) {
            throw new IllegalStateException(
                    "WEBPAY_ENVIRONMENT is \"production\" but WEBPAY_COMMERCE_CODE is still Transbank's "
                            + "integration commerce code. Set the real one issued for your merchant contract.");
        }

        if (apiKey == null || apiKey.isBlank() || apiKey.equals(WebpayRestClient.INTEGRATION_API_KEY)) {
            throw new IllegalStateException(
                    "WEBPAY_ENVIRONMENT is \"production\" but WEBPAY_API_KEY is still Transbank's "
                            + "public integration key. Set the real llave secreta from the merchant portal.");
        }
    }
}
