package org.uvo.paymentplatform.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.paymentplatform.config.BankReconciliationProperties;
import org.uvo.paymentplatform.model.BankMovement;
import org.uvo.paymentplatform.model.SuscriptorPayment;
import org.uvo.paymentplatform.repository.BankMovementRepository;
import org.uvo.paymentplatform.repository.SuscriptorPaymentRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Compares what the app charged through the card gateway against what the processor actually
 * deposited.
 *
 * <p>The customer-facing half of reconciliation answers "who paid us?"; this half answers "did the
 * money arrive?". They are different failures: a card transaction can be approved, recorded, and
 * still never settle — a reversal, a chargeback, or a commit recorded twice. Nothing else in the
 * module would notice.
 *
 * <p><b>Read-only.</b> It never writes payments: the charges it compares against are already
 * recorded, so creating more would double-count them.
 */
@Service
public class SettlementReportService {

    private final BankMovementRepository bankMovementRepository;
    private final SuscriptorPaymentRepository suscriptorPaymentRepository;
    private final BankReconciliationProperties properties;

    public SettlementReportService(BankMovementRepository bankMovementRepository,
                                   SuscriptorPaymentRepository suscriptorPaymentRepository,
                                   BankReconciliationProperties properties) {
        this.bankMovementRepository = bankMovementRepository;
        this.suscriptorPaymentRepository = suscriptorPaymentRepository;
        this.properties = properties;
    }

    public record DepositReconciliation(BankMovement movement,
                                        LocalDate chargeDate,
                                        int charged,
                                        int expected,
                                        int deposited,
                                        int difference,
                                        boolean matches,
                                        List<SuscriptorPayment> payments) {
    }

    public record Summary(int deposits, int mismatched, int difference) {
    }

    /** One row per processor deposit, newest first. */
    @Transactional(readOnly = true)
    public List<DepositReconciliation> forDeposits(int limit) {
        int lag = properties.settlementLagDays();
        BigDecimal commission = properties.transbankCommissionPct() == null
                ? BigDecimal.ZERO
                : properties.transbankCommissionPct();
        int tolerance = properties.settlementToleranceClp();

        List<BankMovement> deposits = bankMovementRepository.findTransbankPayouts(
                BankMovement.Direction.CREDIT,
                BankMovement.MATCH_REASON_TRANSBANK_PAYOUT,
                PageRequest.of(0, limit));

        return deposits.stream().map(deposit -> {
            LocalDate chargeDate = deposit.getPostedAt().minusDays(lag);

            // The payment timestamp is a datetime, so the day is a half-open instant range. Only
            // payments carrying a gateway buy order count: a manually recorded payment or a
            // reconciled transfer was never part of a card deposit.
            List<SuscriptorPayment> payments = suscriptorPaymentRepository.findWebpayPaymentsBetween(
                    SuscriptorPayment.WEBPAY_REFERENCE_PREFIX,
                    chargeDate.atStartOfDay(),
                    chargeDate.plusDays(1).atStartOfDay());

            int charged = payments.stream()
                    .mapToInt(payment -> payment.getAmount().setScale(0, RoundingMode.HALF_UP).intValue())
                    .sum();

            int expected = BigDecimal.valueOf(charged)
                    .multiply(BigDecimal.ONE.subtract(commission.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)))
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValue();

            int deposited = deposit.getAmountClp();
            int difference = deposited - expected;

            return new DepositReconciliation(deposit, chargeDate, charged, expected, deposited,
                    difference, Math.abs(difference) <= tolerance, payments);
        }).toList();
    }

    /**
     * Totals for the page header: how many deposits reconcile cleanly and how much money is
     * unaccounted for across the ones that do not.
     */
    @Transactional(readOnly = true)
    public Summary summary(int limit) {
        List<DepositReconciliation> rows = forDeposits(limit);
        List<DepositReconciliation> mismatched = rows.stream().filter(row -> !row.matches()).toList();

        int difference = mismatched.stream().mapToInt(DepositReconciliation::difference).sum();

        return new Summary(rows.size(), mismatched.size(), difference);
    }
}
