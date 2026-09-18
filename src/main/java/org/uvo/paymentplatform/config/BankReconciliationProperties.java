package org.uvo.paymentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Reconciliation tuning: signal weights, thresholds, tolerances, the auto-confirmation rule, the
 * settlement figures and the per-bank column layouts.
 *
 * <p>A bank movement is scored against every candidate company. The thresholds decide what happens
 * to the result: at or above {@code highConfidenceThreshold} it is offered and pre-selected, at or
 * above {@code suggestThreshold} it is offered but a staff member must confirm it, and below that
 * the movement is left unmatched. **Score alone never applies a payment** — see {@link AutoConfirm}.
 *
 * <p>Supporting one more bank is an entry in {@code banks}, not a new class.
 */
@ConfigurationProperties(prefix = "bank-reconciliation")
public record BankReconciliationProperties(

        @DefaultValue("40") int suggestThreshold,
        @DefaultValue("80") int highConfidenceThreshold,

        /**
         * When the two best candidates are within this many points of each other the match is
         * ambiguous, so neither is pre-selected — both are shown. Two companies on the same plan
         * produce identical amount and date signals, and silently picking one extends the wrong
         * subscription while taking the other's payment off the books.
         */
        @DefaultValue("10") int tieMargin,

        Map<String, Integer> signals,

        AutoConfirm autoConfirm,

        /**
         * Tolerance for the "close enough" amount signal. A transfer often arrives a few pesos off
         * the plan price — rounding, or the payer adding a reference amount — so an exact-only rule
         * misses real payments. The effective tolerance is the larger of the two.
         */
        @DefaultValue("2") double amountTolerancePct,
        @DefaultValue("1000") int amountToleranceClp,

        /** How far around the due date a deposit still counts as paying it. */
        @DefaultValue("5") int dateWindowBeforeDays,
        @DefaultValue("30") int dateWindowAfterDays,

        /**
         * Glosas that identify a card-processor payout rather than a customer. Matched as whole
         * words: the tag resolves a movement with nobody confirming it, so a company whose name
         * merely contains one of these must not read as a payout.
         */
        List<String> transbankGlosaPatterns,

        /**
         * The processor deposits the day's takings a few days later, net of commission.
         *
         * <p>Both of these are still uncalibrated: the lag counts calendar days while settlement
         * happens in business days, and the commission defaults to zero. Until they are set from
         * real deposits the settlement view flags healthy days as mismatches.
         */
        @DefaultValue("2") int settlementLagDays,
        @DefaultValue("0") BigDecimal transbankCommissionPct,
        @DefaultValue("100") int settlementToleranceClp,

        Map<String, Bank> banks) {

    /** Signal keys. Anything deciding what to <b>do</b> with a match reads these, never the copy. */
    public static final String SIGNAL_RUT_IN_GLOSA = "rut_in_glosa";
    public static final String SIGNAL_AMOUNT_EXACT = "amount_exact";
    public static final String SIGNAL_AMOUNT_CLOSE = "amount_close";
    public static final String SIGNAL_DATE_WINDOW = "date_window";
    public static final String SIGNAL_NAME_TOKENS = "name_tokens";

    /**
     * A deposit whose glosa carries the payer's RUT — check digit validated — <b>and</b> whose
     * amount equals the plan price to the peso has nothing left for a human to decide, so the
     * importer records the payment itself.
     *
     * <p>{@code requireSignals} lists the signals that must all have fired, and the match must also
     * be unambiguous. It is decided on <b>which signals matched, not on how many points they add up
     * to</b>: 80 points of weak signals is not the same evidence as a validated payer RUT plus the
     * exact price. Requiring {@code amount_exact} is what keeps a partial transfer out, since a
     * payment buys a full period whatever its amount.
     *
     * <p>There is no undo once a payment exists, so {@code enabled} is the kill switch: turn it off
     * and every deposit goes back to waiting for a staff click.
     */
    public record AutoConfirm(@DefaultValue("true") boolean enabled, List<String> requireSignals) {
    }

    /**
     * One bank's export layout. {@code label} is what the upload dropdown shows; {@code columns}
     * maps each field the importer needs onto the header labels that bank uses.
     *
     * <p>{@code date}, {@code description} and {@code reference} name a single column each. Amounts
     * arrive either as two columns ({@code credit} + {@code debit}) or as one signed {@code amount}
     * column. Matching is accent- and case-insensitive and succeeds on a prefix, so "fecha" matches
     * "Fecha Movimiento".
     */
    public record Bank(String label, Map<String, List<String>> columns) {
    }

    public int signalWeight(String signal) {
        return signals == null ? 0 : signals.getOrDefault(signal, 0);
    }
}
