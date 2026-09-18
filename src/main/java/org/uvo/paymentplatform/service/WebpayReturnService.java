package org.uvo.paymentplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.uvo.paymentplatform.model.WebpayTransaction;
import org.uvo.paymentplatform.repository.WebpayTransactionRepository;
import org.uvo.paymentplatform.webpay.WebpayClient;
import org.uvo.paymentplatform.webpay.WebpayCommitResponse;

import java.util.Optional;

/**
 * What happens when the gateway sends the customer back. This is the most constrained code in the
 * module, and the reasons are the gateway's, not the framework's — the controller handles the
 * request shapes; this service holds the outcomes, and {@link WebpayTransactionWriter} the writes.
 *
 * <p>The five outcomes, and only one is a payment:
 *
 * <ul>
 *   <li>{@code success} — committed, approved, recorded.
 *   <li>{@code declined} — committed, the card was refused. Nothing recorded.
 *   <li>{@code aborted} — the customer backed out, or the form timed out. <b>Never committed.</b>
 *   <li>{@code error} — the commit could not be completed, or the amount did not match. <b>The
 *       charge may exist</b>, so neither the UI copy nor the logs may suggest simply retrying.
 *   <li>{@code failed} — no usable token, or a buy order we have no record of.
 * </ul>
 *
 * <p>Every path that could lose money is logged at error: an unknown buy order, a customer that no
 * longer exists, an amount mismatch. These used to return silently, which is how a charged customer
 * could leave zero trace.
 *
 * <p>Deliberately not transactional: it wraps an HTTP round trip to the gateway.
 */
@Service
public class WebpayReturnService {

    private static final Logger log = LoggerFactory.getLogger(WebpayReturnService.class);

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_DECLINED = "declined";
    public static final String RESULT_ABORTED = "aborted";
    public static final String RESULT_ERROR = "error";
    public static final String RESULT_FAILED = "failed";

    /** The outcome, plus the transaction it belongs to when one was found. */
    public record Outcome(String result, WebpayTransaction transaction) {
    }

    private final WebpayTransactionRepository transactionRepository;
    private final WebpayTransactionWriter writer;
    private final WebpayClient webpayClient;

    public WebpayReturnService(WebpayTransactionRepository transactionRepository,
                               WebpayTransactionWriter writer,
                               WebpayClient webpayClient) {
        this.transactionRepository = transactionRepository;
        this.writer = writer;
        this.webpayClient = webpayClient;
    }

    /** A finished checkout: commit it, and record the payment if it was approved. */
    public Outcome complete(String token) {
        WebpayCommitResponse response;
        try {
            response = webpayClient.commit(token);
        } catch (RuntimeException e) {
            // We cannot tell from here whether the charge went through. The buy order is unknown
            // (it comes back with the commit), so the token is the only handle for investigation.
            log.error("Webpay commit failed: token={}", token, e);
            return new Outcome(RESULT_ERROR, null);
        }

        Optional<WebpayTransaction> found = transactionRepository.findByBuyOrder(response.buyOrder());

        // Never the full response: the real one carries card details. Buy order, code, status,
        // amount — and whether we recognise it.
        log.info("Webpay commit: buyOrder={} responseCode={} status={} amount={} known={}",
                response.buyOrder(), response.responseCode(), response.status(), response.amount(),
                found.isPresent());

        if (found.isEmpty()) {
            // The gateway committed a transaction we have no record of starting. If it was approved
            // the customer has been charged and we cannot safely guess who for.
            log.error("Webpay commit for an unknown buy order: buyOrder={} approved={} amount={}",
                    response.buyOrder(), response.approved(), response.amount());
            return new Outcome(RESULT_FAILED, null);
        }

        WebpayTransaction transaction = found.get();

        if (!response.approved()) {
            writer.close(transaction, WebpayTransaction.Status.DECLINED, token, response.responseCode());
            return new Outcome(RESULT_DECLINED, transaction);
        }

        if (response.amount() != transaction.getAmountClp()) {
            // Should be impossible: the amount is ours, set at create and echoed back. If it ever
            // differs, recording a payment for either figure is a guess — flag it for a human. The
            // deposit still surfaces in the settlement view.
            log.error("Webpay amount mismatch — payment NOT recorded: buyOrder={} expected={} committed={} empresaId={}",
                    response.buyOrder(), transaction.getAmountClp(), response.amount(),
                    transaction.getCustomer().getId());
            writer.close(transaction, WebpayTransaction.Status.FAILED, token, response.responseCode());
            return new Outcome(RESULT_ERROR, transaction);
        }

        try {
            writer.applySuccessfulPayment(transaction, token, response);
        } catch (RuntimeException e) {
            // The card WAS charged — the commit above succeeded — and recording the payment failed
            // after that: a payer the user table does not know, a constraint, a database error.
            // The write is one transaction, so the row is still pending and nothing is half-done;
            // but a charged customer with no payment on the books is the case that must never be
            // silent. Loud log, and an "error" result whose copy says not to simply retry.
            log.error("Webpay payment could not be recorded after a successful commit — CHARGE EXISTS, payment NOT recorded: "
                            + "buyOrder={} empresaId={} amount={} userId={}",
                    transaction.getBuyOrder(), transaction.getCustomer().getId(), response.amount(),
                    transaction.getUserId(), e);
            return new Outcome(RESULT_ERROR, transaction);
        }

        return new Outcome(RESULT_SUCCESS, transaction);
    }

    /**
     * Cancelled or timed out. Both arrive without a committable token, so there is nothing to
     * reverse — just close the row so a later replay cannot be mistaken for a fresh attempt.
     */
    public Outcome abandon(String buyOrder, boolean cancelled) {
        Optional<WebpayTransaction> found = buyOrder == null
                ? Optional.empty()
                : transactionRepository.findByBuyOrder(buyOrder);

        log.info("Webpay checkout abandoned: buyOrder={} reason={} known={}",
                buyOrder, cancelled ? "cancelled by customer" : "payment form timed out", found.isPresent());

        found.ifPresent(transaction -> writer.close(transaction, WebpayTransaction.Status.ABORTED, null, null));

        return new Outcome(RESULT_ABORTED, found.orElse(null));
    }

    /** Not a shape the gateway documents — a stale bookmark, a bot, or a return we failed to parse. */
    public Outcome unrecognised(Iterable<String> parameterNames) {
        log.warn("Webpay return with no usable token: params={}", parameterNames);
        return new Outcome(RESULT_FAILED, null);
    }
}
