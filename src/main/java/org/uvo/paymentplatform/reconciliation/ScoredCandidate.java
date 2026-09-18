package org.uvo.paymentplatform.reconciliation;

import java.util.List;

/**
 * One company scored against one bank movement, with the human-readable reasons that produced the
 * score. The reasons are shown to the staff member making the call — a number on its own is not a
 * justification for moving someone's due date.
 *
 * @param reasons Spanish, shown in the review queue
 * @param signals the same signals as {@code reasons} but by their config key. {@code reasons} is
 *                display copy and will be reworded; <b>anything deciding what to do with a match
 *                reads these instead</b>.
 */
public record ScoredCandidate(CandidateCompany company,
                              int score,
                              List<String> reasons,
                              List<String> signals) {

    public String reasonText() {
        return String.join(" + ", reasons);
    }

    public boolean hasSignal(String signal) {
        return signals.contains(signal);
    }

    /**
     * An empty list is false, not vacuously true: the caller asking this is deciding whether to act
     * without a human, and a misconfigured empty requirement must not qualify every match.
     */
    public boolean hasAllSignals(List<String> required) {
        if (required == null || required.isEmpty()) {
            return false;
        }

        return required.stream().allMatch(this::hasSignal);
    }
}
