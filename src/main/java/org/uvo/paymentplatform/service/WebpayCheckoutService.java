package org.uvo.paymentplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.PaymentStatus;
import org.uvo.paymentplatform.model.WebpayTransaction;
import org.uvo.paymentplatform.webpay.WebpayClient;
import org.uvo.paymentplatform.webpay.WebpayCreateResponse;
import org.uvo.paymentplatform.webpay.WebpayOptionsGuard;

import java.util.Optional;

/**
 * Starts a Webpay Plus checkout. One service for both places a payment can start from — the staff
 * lookup and the customer's own portal — which the source project had as two identical copies that
 * differed only in where the gateway should send the customer back to.
 *
 * <p>The order of operations is the whole point, and it is why the writes live in
 * {@link WebpayTransactionWriter} rather than here:
 *
 * <ol>
 *   <li>The pending transaction row is written and <b>committed</b> first. The return leg has to
 *       find it by buy order, because the gateway sends the customer back with a cross-site POST
 *       that carries no session — and it also carries who is paying, since the payment row needs a
 *       user and the return lands unauthenticated.
 *   <li>Only then is the gateway called, outside any transaction: an HTTP round trip must not hold
 *       a database connection, and a row that exists before the gateway knows about the order can
 *       never become the "unknown buy order" case the return leg logs as lost money.
 *   <li>If the gateway call fails, the row is closed as {@code failed}. Nothing was charged.
 * </ol>
 *
 * <p>This class is deliberately not transactional.
 */
@Service
public class WebpayCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(WebpayCheckoutService.class);

    public static final String CHECKOUT_FAILED = "No se pudo iniciar el pago. Intente nuevamente en unos minutos.";

    private final WebpayTransactionWriter writer;
    private final WebpayClient webpayClient;
    private final WebpayOptionsGuard optionsGuard;
    private final CustomerRules customerRules;

    public WebpayCheckoutService(WebpayTransactionWriter writer,
                                 WebpayClient webpayClient,
                                 WebpayOptionsGuard optionsGuard,
                                 CustomerRules customerRules) {
        this.writer = writer;
        this.webpayClient = webpayClient;
        this.optionsGuard = optionsGuard;
        this.customerRules = customerRules;
    }

    /** The gateway could not be reached, or refused the request. Nothing was charged. */
    public static class CheckoutFailedException extends RuntimeException {
        public CheckoutFailedException(Throwable cause) {
            super(CHECKOUT_FAILED, cause);
        }
    }

    /**
     * Whether a payment may be started at all: the account is due soon or overdue, and has a priced
     * plan. Deliberately does <b>not</b> require the account to be active — a suspended customer
     * paying is how they get reactivated.
     */
    public boolean canPay(Customer customer, Integer chargeAmount) {
        if (chargeAmount == null) {
            return false;
        }

        PaymentStatus status = customerRules.paymentStatus(customer);

        return status == PaymentStatus.DUE_SOON || status == PaymentStatus.OVERDUE;
    }

    /**
     * @param search    the term the staff lookup was showing, restored after the round trip; empty
     *                  from the customer portal
     * @param returnTo  which page the return leg sends the browser back to
     * @param returnUrl the absolute URL the gateway calls back
     * @return the gateway URL to redirect the browser to, or empty when the customer may not pay
     * @throws CheckoutFailedException when the gateway call failed
     */
    public Optional<String> startCheckout(Customer customer,
                                          Integer chargeAmount,
                                          long userId,
                                          String search,
                                          String returnTo,
                                          String returnUrl) {
        if (!canPay(customer, chargeAmount)) {
            return Optional.empty();
        }

        // Fails here, before the customer sees a payment form, when the gateway configuration would
        // silently take no real money.
        optionsGuard.assertUsable();

        WebpayTransaction transaction = writer.createPending(customer, chargeAmount, userId, search, returnTo);

        WebpayCreateResponse response;
        try {
            response = webpayClient.create(
                    transaction.getBuyOrder(),
                    transaction.getSessionId(),
                    transaction.getAmountClp(),
                    returnUrl);
        } catch (RuntimeException e) {
            // Gateway unreachable, or credentials rejected. Nothing was charged.
            log.error("Webpay transaction create failed: buyOrder={}", transaction.getBuyOrder(), e);
            writer.close(transaction, WebpayTransaction.Status.FAILED, null, null);
            throw new CheckoutFailedException(e);
        }

        return Optional.of(response.redirectUrl());
    }
}
