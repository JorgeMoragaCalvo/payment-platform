package org.uvo.paymentplatform.reconciliation;

import java.time.LocalDate;

/**
 * The few facts about a company that the match engine needs, lifted out of the entity so the
 * scoring stays pure — no persistence, no container, no database — and can be unit-tested.
 *
 * @param rut          normalized RUT, as the RUT helper returns it
 * @param name         display name
 * @param chargeAmount expected charge, or null when the company has no priced plan
 */
public record CandidateCompany(long id,
                               String rut,
                               String name,
                               Integer chargeAmount,
                               LocalDate dueDate) {
}
