package org.uvo.paymentplatform.webpay;

/**
 * What the gateway answers when a checkout is created: where to send the browser, and the token
 * that identifies the checkout on the gateway's side.
 */
public record WebpayCreateResponse(String url, String token) {

    /** The full redirect target, the way the gateway expects the token to be delivered. */
    public String redirectUrl() {
        return url + "?token_ws=" + token;
    }
}
