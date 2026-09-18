package org.uvo.paymentplatform.cartola;

/**
 * A bank statement that could not be read. The message is shown to staff as-is, so it is Spanish
 * and actionable: it has to say what to check, not that parsing failed.
 */
public class CartolaException extends RuntimeException {

    public CartolaException(String message) {
        super(message);
    }
}
