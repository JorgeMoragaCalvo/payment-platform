package org.uvo.paymentplatform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.DatosPlan;
import org.uvo.paymentplatform.model.SuscriptorPayment;
import org.uvo.paymentplatform.repository.CustomerRepository;
import org.uvo.paymentplatform.repository.DatosPlanRepository;
import org.uvo.paymentplatform.repository.SuscriptorPaymentRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The single place a payment is recorded, whatever channel it arrived through: it appends to the
 * payment audit trail, extends the company's due date, extends the plan's expiry, and reactivates a
 * suspended account.
 *
 * <p>Both callers go through here — the card-gateway return leg and a reconciled bank transfer — so
 * the two channels can never drift apart on how far a payment moves the due date. <b>Nothing may
 * write the payment table directly.</b>
 */
@Service
public class RecordPaymentService {

    public static final String SOURCE_WEBPAY = "webpay";
    public static final String SOURCE_BANK_TRANSFER = "bank_transfer";

    /** Fallback renewal length when the plan has none. */
    private static final int DEFAULT_PERIOD_DAYS = 30;

    /**
     * Bounds on the plan's renewal length. Production holds 0 — which would leave the due date
     * untouched after a real payment — and 99999999, which would push it to the year 275000. So
     * anything outside this range is treated as bad data and replaced with the default rather than
     * trusted.
     */
    private static final int MIN_PERIOD_DAYS = 1;
    private static final int MAX_PERIOD_DAYS = 1095;

    private final CustomerRepository customerRepository;
    private final DatosPlanRepository datosPlanRepository;
    private final SuscriptorPaymentRepository suscriptorPaymentRepository;

    public RecordPaymentService(CustomerRepository customerRepository,
                                DatosPlanRepository datosPlanRepository,
                                SuscriptorPaymentRepository suscriptorPaymentRepository) {
        this.customerRepository = customerRepository;
        this.datosPlanRepository = datosPlanRepository;
        this.suscriptorPaymentRepository = suscriptorPaymentRepository;
    }

    /**
     * @param amount            amount actually paid, in whole pesos
     * @param source            one of the {@code SOURCE_*} constants. Accepted but deliberately not
     *                          persisted — there is no column for it, exactly as in the source
     *                          project; the channel is told apart by the {@code external_reference}
     *                          prefix. It stays in the signature because it documents the call site.
     * @param externalReference the gateway buy order, or the bank movement reference
     * @param userId            whoever took or confirmed the payment. Not nullable, and the column
     *                          has a foreign key to the user table, so an id that does not exist
     *                          fails the insert — after the customer has already been charged.
     */
    @Transactional
    public SuscriptorPayment apply(Customer customer,
                                   int amount,
                                   LocalDateTime paidAt,
                                   String source,
                                   String externalReference,
                                   long userId,
                                   String notes) {

        Optional<DatosPlan> plan = datosPlanRepository.findActivePlan(customer.getId());
        LocalDate oldDueDate = customer.getProximoPago();
        LocalDate newDueDate = nextDueDate(oldDueDate,
                plan.map(DatosPlan::getPeriodoDays).orElse(null),
                paidAt.toLocalDate());

        SuscriptorPayment payment = suscriptorPaymentRepository.save(SuscriptorPayment.builder()
                .amount(BigDecimal.valueOf(amount))
                .userId(userId)
                .responsable((int) userId)
                .customer(customer)
                .planId(plan.map(DatosPlan::getPlanId).orElse(null))
                .periodoPlan(plan.map(DatosPlan::getPeriodoPlan).orElse(null))
                .notes(notes)
                .externalReference(externalReference)
                .fechaPago(paidAt)
                .fechaVencimientoOriginal(oldDueDate)
                .build());

        customer.setProximoPago(newDueDate);
        customer.setEstado(Customer.ESTADO_ACTIVE);
        // Saved explicitly rather than relying on dirty checking: callers may hand over an entity
        // loaded in an earlier transaction, and a detached one would flush nothing at all, silently.
        customerRepository.save(customer);

        plan.ifPresent(p -> {
            p.setFechaVencimiento(newDueDate);
            datosPlanRepository.save(p);
        });

        return payment;
    }

    /**
     * A payment always buys a full period. It is added to the current due date when that is still
     * ahead — paying early does not forfeit the remaining days — and to the payment date when the
     * account was already overdue, since being late does not buy back the lapsed ones.
     *
     * <p>The period runs from when the money arrived, not from when it was reconciled: a transfer
     * sitting unreviewed in the queue for a week must not cost the customer a week of service.
     *
     * <p>A null renewal length lands on the default, the same way the source project's cast of null
     * to zero fails the lower bound below.
     */
    private LocalDate nextDueDate(LocalDate oldDueDate, Integer periodoDays, LocalDate paidAt) {
        int days = periodoDays == null ? 0 : periodoDays;

        if (days < MIN_PERIOD_DAYS || days > MAX_PERIOD_DAYS) {
            days = DEFAULT_PERIOD_DAYS;
        }

        LocalDate from = paidAt;

        if (oldDueDate != null && oldDueDate.isAfter(from)) {
            from = oldDueDate;
        }

        return from.plusDays(days);
    }
}
