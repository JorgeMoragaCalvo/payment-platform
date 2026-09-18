package org.uvo.paymentplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.SuscriptorPayment;
import org.uvo.paymentplatform.model.WebpayTransaction;
import org.uvo.paymentplatform.repository.CustomerRepository;
import org.uvo.paymentplatform.repository.WebpayTransactionRepository;
import org.uvo.paymentplatform.webpay.WebpayCommitResponse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Every write to the gateway transaction table, each in its own transaction.
 *
 * <p><b>A separate bean on purpose.</b> The checkout and return services orchestrate an HTTP round
 * trip to the gateway around these writes, and a transactional method called from inside its own
 * bean is not transactional at all — the proxy never sees the call. Putting the writes here is what
 * makes the two properties this module depends on actually hold:
 *
 * <ul>
 *   <li>the pending row is <b>committed before</b> the gateway is called, so the return leg can
 *       always find it, and
 *   <li>the claim, the payment and the link back to it <b>commit together</b>, so a row can never be
 *       left marked authorized with no payment behind it.
 * </ul>
 */
@Service
public class WebpayTransactionWriter {

    private static final Logger log = LoggerFactory.getLogger(WebpayTransactionWriter.class);

    private final WebpayTransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final RecordPaymentService recordPaymentService;
    private final Clock clock;

    public WebpayTransactionWriter(WebpayTransactionRepository transactionRepository,
                                   CustomerRepository customerRepository,
                                   RecordPaymentService recordPaymentService,
                                   Clock clock) {
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.recordPaymentService = recordPaymentService;
        this.clock = clock;
    }

    /** The pending row, written and committed before the browser leaves for the gateway. */
    @Transactional
    public WebpayTransaction createPending(Customer customer,
                                           int chargeAmount,
                                           long userId,
                                           String search,
                                           String returnTo) {
        return transactionRepository.save(WebpayTransaction.builder()
                .buyOrder(WebpayTransaction.makeBuyOrder(customer.getId()))
                .sessionId("pa-" + UUID.randomUUID())
                .customer(customer)
                .userId(userId)
                .amount(BigDecimal.valueOf(chargeAmount))
                .search(search == null ? "" : search)
                .returnTo(returnTo)
                .status(WebpayTransaction.Status.PENDING)
                .build());
    }

    /**
     * Moves a still-pending transaction to a terminal non-paying state. Guarded by the conditional
     * UPDATE, so a transaction already settled by an earlier delivery of the same return is left
     * untouched.
     */
    @Transactional
    public void close(WebpayTransaction transaction, WebpayTransaction.Status status, String token, Integer responseCode) {
        transactionRepository.closeIfPending(
                transaction.getId(),
                status.value(),
                token,
                responseCode == null ? null : responseCode.shortValue(),
                LocalDateTime.now(clock));
    }

    /**
     * Records an approved payment. Recording itself lives in {@link RecordPaymentService}, shared with
     * bank reconciliation, so both channels extend the due date by exactly the same rule.
     *
     * <p>The row is claimed with a conditional UPDATE — the same guard reconciliation uses — so a
     * replayed return POST, a refreshed tab or two concurrent requests record the payment exactly
     * once. The unique constraint on the payment's external reference is the backstop behind that.
     *
     * <p>Because this is one transaction, a failure while recording — a user id the payment table's
     * foreign key rejects, say — rolls the claim back too. The row stays pending and the failure is
     * visible, rather than the row saying "authorized" over a payment that was never written.
     *
     * @return false when there was nobody to credit — the customer no longer exists — which is logged
     *         at error because the card was charged and this needs a manual refund or a manual
     *         payment row
     */
    @Transactional
    public boolean applySuccessfulPayment(WebpayTransaction transaction, String token, WebpayCommitResponse response) {
        Long empresaId = transaction.getCustomer().getId();
        Customer customer = customerRepository.findById(empresaId).orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);

        if (customer == null) {
            log.error("Webpay payment for a customer that no longer exists: buyOrder={} empresaId={} amount={}",
                    transaction.getBuyOrder(), empresaId, response.amount());
            transactionRepository.closeIfPending(
                    transaction.getId(),
                    WebpayTransaction.Status.FAILED.value(),
                    token,
                    (short) response.responseCode(),
                    now);
            return false;
        }

        int claimed = transactionRepository.authorizeIfPending(
                transaction.getId(), token, (short) response.responseCode(), now);

        if (claimed == 0) {
            // Already settled by an earlier delivery of this same return.
            return true;
        }

        SuscriptorPayment payment = recordPaymentService.apply(
                customer,
                response.amount(),
                now,
                RecordPaymentService.SOURCE_WEBPAY,
                transaction.getBuyOrder(),
                transaction.getUserId(),
                "Pago online via Webpay Plus (orden " + transaction.getBuyOrder() + ")");

        transactionRepository.attachPayment(transaction.getId(), payment.getId());

        return true;
    }
}
