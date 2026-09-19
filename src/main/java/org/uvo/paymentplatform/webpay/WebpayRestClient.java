package org.uvo.paymentplatform.webpay;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.uvo.paymentplatform.config.WebpayProperties;

import java.util.Map;
import java.util.Set;

/**
 * Webpay Plus over Transbank's REST API, with no SDK in between. The API is two calls, and the SDK's
 * own source (`Webpay/WebpayPlus/Transaction.php`, `Webpay/Options.php`, `Utils/HasTransactionStatus.php`)
 * is where every constant below was read from — hosts, endpoint version, header names, payload
 * field names, and the approval rule.
 *
 * <p>Two things about the approval rule matter and are easy to get wrong:
 *
 * <ul>
 *   <li>{@code response_code == 0} alone is NOT approval. The SDK also requires the status to be one
 *       of a fixed set; a commit that comes back with code 0 and an unexpected status is treated as
 *       not approved, and the return leg then records nothing. Reproduced exactly.
 *   <li>The commit response carries card details. Only the five fields the application needs are
 *       read out of it, so the response object can be logged without leaking them.
 * </ul>
 */
public class WebpayRestClient implements WebpayClient {

    /** Transbank's hosts, from {@code Webpay\Options}. */
    static final String BASE_URL_INTEGRATION = "https://webpay3gint.transbank.cl/";
    static final String BASE_URL_PRODUCTION = "https://webpay3g.transbank.cl/";

    /** The public sandbox credentials the SDK ships with. Real only for the integration host. */
    public static final String INTEGRATION_COMMERCE_CODE = "597055555532";
    public static final String INTEGRATION_API_KEY = "579B532A7440BB0C9079DED94D31EA1615BACEB56610332264630D42D0A36B1C";

    private static final String ENDPOINT_CREATE = "rswebpaytransaction/api/webpay/v1.3/transactions";
    private static final String ENDPOINT_COMMIT = "rswebpaytransaction/api/webpay/v1.3/transactions/{token}";

    private static final String HEADER_KEY_ID = "Tbk-Api-Key-Id";
    private static final String HEADER_KEY_SECRET = "Tbk-Api-Key-Secret";

    private static final int RESPONSE_CODE_APPROVED = 0;

    /** Statuses that, together with response code 0, mean approved — {@code TransactionStatusResponse::isApproved}. */
    private static final Set<String> APPROVED_STATUSES = Set.of(
            "AUTHORIZED", "CAPTURED", "REVERSED", "NULLIFIED", "PARTIALLY_NULLIFIED");

    private final RestClient rest;

    public WebpayRestClient(WebpayProperties properties, RestClient.Builder builder) {
        boolean production = WebpayProperties.ENV_PRODUCTION.equals(properties.environment());

        String commerceCode = orDefault(properties.commerceCode(), INTEGRATION_COMMERCE_CODE);
        String apiKey = orDefault(properties.apiKey(), INTEGRATION_API_KEY);

        this.rest = builder
                .baseUrl(production ? BASE_URL_PRODUCTION : BASE_URL_INTEGRATION)
                .defaultHeader(HEADER_KEY_ID, commerceCode)
                .defaultHeader(HEADER_KEY_SECRET, apiKey)
                .build();
    }

    @Override
    public WebpayCreateResponse create(String buyOrder, String sessionId, int amount, String returnUrl) {
        Map<String, Object> payload = Map.of(
                "buy_order", buyOrder,
                "session_id", sessionId,
                "amount", amount,
                "return_url", returnUrl);

        Map<String, Object> body = exchange(() -> rest.post()
                .uri(ENDPOINT_CREATE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(MAP));

        return new WebpayCreateResponse(string(body, "url"), string(body, "token"));
    }

    @Override
    public WebpayCommitResponse commit(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token parameter given is empty.");
        }

        Map<String, Object> body = exchange(() -> rest.put()
                .uri(ENDPOINT_COMMIT, token)
                .retrieve()
                .body(MAP));

        int responseCode = integer(body, "response_code");
        String status = string(body, "status");
        boolean approved = responseCode == RESPONSE_CODE_APPROVED && status != null && APPROVED_STATUSES.contains(status);

        return new WebpayCommitResponse(
                string(body, "buy_order"),
                responseCode,
                status,
                integer(body, "amount"),
                approved);
    }

    // ----------------------------------------------------------------------------- plumbing

    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> MAP = (Class<Map<String, Object>>) (Class<?>) Map.class;

    private interface Call {
        Map<String, Object> run();
    }

    /**
     * A gateway error is surfaced with its status and its body — Transbank puts a readable
     * {@code error_message} in the body — never swallowed into a generic failure.
     */
    private static Map<String, Object> exchange(Call call) {
        try {
            Map<String, Object> body = call.run();
            if (body == null) {
                throw new WebpayException("Transbank respondió sin cuerpo.", null);
            }
            return body;
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            throw new WebpayException("Transbank respondió " + status.value() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : value.toString();
    }

    private static int integer(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            throw new WebpayException("Transbank no envió el campo \"" + key + "\".", null);
        }
        return Integer.parseInt(value.toString());
    }

    /** A failure at the gateway boundary. Message is safe to log: it never includes card data. */
    public static class WebpayException extends RuntimeException {
        public WebpayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
