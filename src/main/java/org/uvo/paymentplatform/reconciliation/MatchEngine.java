package org.uvo.paymentplatform.reconciliation;

import org.uvo.paymentplatform.cartola.Movement;
import org.uvo.paymentplatform.config.BankReconciliationProperties;
import org.uvo.paymentplatform.model.BankMovement;
import org.uvo.paymentplatform.support.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Scores a bank deposit against the companies it might be paying for.
 *
 * <p>Chilean transfers carry no structured reference — the payer types whatever they like into the
 * glosa — so no single signal is conclusive. The engine combines four of them and hands the ranked
 * result, with the reasons, to whoever asked: usually the review queue, and, when the result
 * carries the identity-grade signals the importer requires, the importer itself.
 *
 * <p>The engine never decides that. It reports what matched and how the candidates compare.
 *
 * <p>Pure logic on purpose: every threshold is injected rather than read from configuration, so
 * this can be unit-tested without a container.
 */
public class MatchEngine {

    private final Map<String, Integer> signals;
    private final int suggestThreshold;
    private final int highConfidenceThreshold;
    private final int tieMargin;
    private final double amountTolerancePct;
    private final int amountToleranceClp;
    private final int windowBeforeDays;
    private final int windowAfterDays;

    public MatchEngine(Map<String, Integer> signals,
                       int suggestThreshold,
                       int highConfidenceThreshold,
                       int tieMargin,
                       double amountTolerancePct,
                       int amountToleranceClp,
                       int windowBeforeDays,
                       int windowAfterDays) {
        this.signals = signals;
        this.suggestThreshold = suggestThreshold;
        this.highConfidenceThreshold = highConfidenceThreshold;
        this.tieMargin = tieMargin;
        this.amountTolerancePct = amountTolerancePct;
        this.amountToleranceClp = amountToleranceClp;
        this.windowBeforeDays = windowBeforeDays;
        this.windowAfterDays = windowAfterDays;
    }

    public static MatchEngine fromProperties(BankReconciliationProperties properties) {
        return new MatchEngine(
                properties.signals(),
                properties.suggestThreshold(),
                properties.highConfidenceThreshold(),
                properties.tieMargin(),
                properties.amountTolerancePct(),
                properties.amountToleranceClp(),
                properties.dateWindowBeforeDays(),
                properties.dateWindowAfterDays());
    }

    public MatchResult match(Movement movement, List<CandidateCompany> candidates) {
        // Only money coming in can settle a subscription.
        if (movement.direction() != BankMovement.Direction.CREDIT) {
            return new MatchResult(List.of(), false, false);
        }

        List<ScoredCandidate> scored = new ArrayList<>();

        for (CandidateCompany candidate : candidates) {
            ScoredCandidate result = score(movement, candidate);

            if (result.score() >= suggestThreshold) {
                scored.add(result);
            }
        }

        scored.sort(Comparator.comparingInt(ScoredCandidate::score).reversed());

        return new MatchResult(scored, isHighConfidence(scored), isAmbiguous(scored));
    }

    public ScoredCandidate score(Movement movement, CandidateCompany candidate) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        List<String> fired = new ArrayList<>();

        if (movement.counterpartyRut() != null && movement.counterpartyRut().equals(candidate.rut())) {
            score += weight(BankReconciliationProperties.SIGNAL_RUT_IN_GLOSA);
            reasons.add("RUT en la glosa");
            fired.add(BankReconciliationProperties.SIGNAL_RUT_IN_GLOSA);
        }

        if (candidate.chargeAmount() != null && candidate.chargeAmount() > 0) {
            if (movement.amount() == candidate.chargeAmount()) {
                score += weight(BankReconciliationProperties.SIGNAL_AMOUNT_EXACT);
                reasons.add("monto exacto");
                fired.add(BankReconciliationProperties.SIGNAL_AMOUNT_EXACT);
            } else if (amountIsClose(movement.amount(), candidate.chargeAmount())) {
                score += weight(BankReconciliationProperties.SIGNAL_AMOUNT_CLOSE);
                reasons.add("monto aproximado");
                fired.add(BankReconciliationProperties.SIGNAL_AMOUNT_CLOSE);
            }
        }

        if (dateIsInWindow(movement, candidate)) {
            score += weight(BankReconciliationProperties.SIGNAL_DATE_WINDOW);
            reasons.add("fecha cercana al vencimiento");
            fired.add(BankReconciliationProperties.SIGNAL_DATE_WINDOW);
        }

        int nameScore = nameScore(movement.description(), candidate.name());

        if (nameScore > 0) {
            score += nameScore;
            reasons.add("nombre en la glosa");
            fired.add(BankReconciliationProperties.SIGNAL_NAME_TOKENS);
        }

        return new ScoredCandidate(candidate, score, reasons, fired);
    }

    /**
     * The top candidate may only be pre-selected when it is both strong enough on its own and
     * clearly ahead of the runner-up. Two companies on the same plan produce identical amount and
     * date signals, and silently picking one of them would extend the wrong subscription.
     */
    private boolean isHighConfidence(List<ScoredCandidate> scored) {
        if (scored.isEmpty() || scored.get(0).score() < highConfidenceThreshold) {
            return false;
        }

        if (scored.size() < 2) {
            return true;
        }

        return (scored.get(0).score() - scored.get(1).score()) > tieMargin;
    }

    /**
     * Two candidates within the tie margin of each other. Reported separately from confidence so
     * the review queue can say "there is another company just as likely" rather than the vaguer
     * "this match is weak".
     */
    private boolean isAmbiguous(List<ScoredCandidate> scored) {
        if (scored.size() < 2) {
            return false;
        }

        return (scored.get(0).score() - scored.get(1).score()) <= tieMargin;
    }

    private boolean amountIsClose(int paid, int expected) {
        int tolerance = Math.max(amountToleranceClp, (int) Math.round(expected * amountTolerancePct / 100));

        return Math.abs(paid - expected) <= tolerance;
    }

    /**
     * The window runs from the start of the day {@code windowBeforeDays} before the due date to the
     * <b>end</b> of the day {@code windowAfterDays} after it, inclusive at both ends. Movements
     * carry a date with no time, so comparing dates reproduces that exactly — do not "improve" this
     * to an instant comparison, which would move the upper boundary by a day.
     */
    private boolean dateIsInWindow(Movement movement, CandidateCompany candidate) {
        if (candidate.dueDate() == null) {
            return false;
        }

        LocalDate from = candidate.dueDate().minusDays(windowBeforeDays);
        LocalDate to = candidate.dueDate().plusDays(windowAfterDays);

        return !movement.postedAt().isBefore(from) && !movement.postedAt().isAfter(to);
    }

    /**
     * Proportion of the company's distinctive name words present in the glosa, scaled to the
     * signal's weight. Partial credit matters here: banks truncate the glosa, so "COMERCIAL ANDES
     * SPA" often arrives as "TRANSF COMERCIAL AND".
     */
    private int nameScore(String description, String name) {
        List<String> nameTokens = Text.tokens(name);

        if (nameTokens.isEmpty()) {
            return 0;
        }

        List<String> glosaTokens = Text.tokens(description);
        long hits = nameTokens.stream().filter(glosaTokens::contains).count();

        if (hits == 0) {
            return 0;
        }

        return (int) Math.round(
                weight(BankReconciliationProperties.SIGNAL_NAME_TOKENS) * (double) hits / nameTokens.size());
    }

    private int weight(String signal) {
        return signals == null ? 0 : signals.getOrDefault(signal, 0);
    }
}
