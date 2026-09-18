package org.uvo.paymentplatform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.paymentplatform.security.SecurityConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The URLs that cross the gateway boundary in both directions.
 *
 * <p>Outbound, the absolute return URL the gateway calls back — public and HTTPS, or the return leg
 * cannot be exercised at all. Inbound, where to send the browser once the return has been handled:
 * the frontend, with the same two query parameters the previous pages read, so the router can
 * restore the staff lookup or show the customer their result.
 */
@Component
public class ReturnUrls {

    private final String publicUrl;
    private final String frontendUrl;

    public ReturnUrls(@Value("${app.public-url}") String publicUrl,
                      @Value("${app.frontend-url}") String frontendUrl) {
        this.publicUrl = stripTrailingSlash(publicUrl);
        this.frontendUrl = stripTrailingSlash(frontendUrl);
    }

    /** What the gateway is told to redirect to after checkout. */
    public String webpayReturn() {
        return publicUrl + SecurityConfig.WEBPAY_RETURN_PATH;
    }

    /** Back to the customer's own page, with the outcome. */
    public String myAccount(String result) {
        return frontendUrl + "/mi-cuenta?payment=" + encode(result);
    }

    /** Back to the staff page, restoring the term it was showing, with the outcome. */
    public String paymentAlert(String search, String result) {
        return frontendUrl + "/payment-alert?search=" + encode(search == null ? "" : search)
                + "&payment=" + encode(result);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
