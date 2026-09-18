package org.uvo.paymentplatform.reconciliation;

import java.util.List;

/**
 * What the match engine concluded about one bank movement.
 *
 * <p>Note what is deliberately absent: any notion of "applied". The engine ranks and explains.
 *
 * @param candidates       best first, already filtered to the suggestible ones
 * @param isHighConfidence true when the top candidate is clearly ahead and above the
 *                         high-confidence threshold, so the UI may pre-select it
 * @param isAmbiguous      true when a runner-up scored almost as well. Distinct from a merely weak
 *                         match: it means picking the leader would be a coin flip, which the review
 *                         queue has to say out loud — and which disqualifies a match from being
 *                         confirmed without a human.
 */
public record MatchResult(List<ScoredCandidate> candidates,
                          boolean isHighConfidence,
                          boolean isAmbiguous) {

    public ScoredCandidate best() {
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    public boolean hasSuggestion() {
        return !candidates.isEmpty();
    }
}
