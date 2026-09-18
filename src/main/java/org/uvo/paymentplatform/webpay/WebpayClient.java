package org.uvo.paymentplatform.webpay;

/**
 * The gateway boundary: the two calls the application makes to Webpay Plus, and nothing else.
 *
 * <p>An interface rather than a direct SDK dependency, for two reasons that both matter. First, it is
 * what makes the return leg testable at all — the commit path cannot be exercised without talking
 * to the gateway otherwise, which is exactly why the source project bound its SDK transaction object
 * so tests could fake it. Second, the gateway SDK has not been chosen yet: its Java artifact and API
 * shape still have to be verified against the real thing, and the application must not guess them.
 *
 * <p>Until a real implementation exists, {@link WebpayClientStub} is what the container wires, and
 * it refuses to do anything. That is intentional: the rest of the application works, and a payment
 * attempt fails loudly instead of pretending.
 */
public interface WebpayClient {

    /**
     * Starts a checkout. Called after the pending transaction row has been written, never before:
     * the return leg has to find that row by buy order, and a create that succeeded at the gateway
     * without a row behind it would be a charge the application cannot attribute.
     *
     * @param amount    whole pesos
     * @param returnUrl absolute URL the gateway sends the browser back to
     */
    WebpayCreateResponse create(String buyOrder, String sessionId, int amount, String returnUrl);

    /**
     * Commits a checkout the customer finished. Only ever called with a {@code token_ws}, never with
     * an abort token — committing an aborted checkout would record a payment the customer backed
     * out of.
     */
    WebpayCommitResponse commit(String token);
}
