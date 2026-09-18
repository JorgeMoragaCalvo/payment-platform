package org.uvo.paymentplatform.webpay;

/**
 * What the gateway answers when a checkout is committed.
 *
 * <p>This is deliberately the whole of what the application reads from the commit. The real gateway
 * response also carries card details, and <b>none of that may ever reach a log line</b>: keeping the
 * record this narrow is what makes it safe to log the object at all.
 *
 * @param buyOrder     the order this commit belongs to — the key the pending transaction is looked
 *                     up by, since the return leg carries no session
 * @param responseCode the gateway's numeric result; 0 is approved
 * @param status       the gateway's status word, for the log line only
 * @param amount       what was actually charged, in whole pesos, checked against what was requested
 * @param approved     whether the charge went through
 */
public record WebpayCommitResponse(String buyOrder, int responseCode, String status, int amount, boolean approved) {
}
