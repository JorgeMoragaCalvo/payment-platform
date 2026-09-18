package org.uvo.paymentplatform.web;

import org.springframework.stereotype.Component;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.DatosPlan;
import org.uvo.paymentplatform.repository.DatosPlanRepository;
import org.uvo.paymentplatform.service.CustomerRules;

import java.time.LocalDate;

/**
 * Turns a customer into what the two views show. Entities never cross the controller boundary —
 * each response is shaped for what its page renders, so the API leaks neither associations nor the
 * soft-delete internals.
 *
 * <p>All the derived fields come from {@link CustomerRules}; nothing is computed here. The status
 * value is the lowercase wire form the frontend switches on.
 */
@Component
public class CustomerPresenter {

    /** One row of the staff list. */
    public record Summary(long id,
                          String name,
                          String formattedRut,
                          LocalDate paymentDate,
                          Integer daysPastDue,
                          String paymentStatus,
                          String planType,
                          boolean isActive) {
    }

    /** The detail panel: the row plus what the actions need. */
    public record Detail(long id,
                         String name,
                         String formattedRut,
                         LocalDate paymentDate,
                         Integer daysPastDue,
                         String paymentStatus,
                         String planType,
                         boolean isActive,
                         Integer chargeAmount,
                         boolean isSuspendable,
                         Integer daysUntilSuspendable,
                         boolean canPay) {
    }

    /**
     * The customer's own view. {@code canPay} deliberately omits the staff view's active check: a
     * suspended customer must still be able to pay, since paying is what reactivates them.
     */
    public record MyAccount(String companyName,
                            String formattedRut,
                            LocalDate paymentDate,
                            Integer daysPastDue,
                            String paymentStatus,
                            String planType,
                            boolean isActive,
                            Integer daysUntilSuspendable,
                            boolean canPay) {
    }

    private final CustomerRules rules;
    private final DatosPlanRepository datosPlanRepository;

    public CustomerPresenter(CustomerRules rules, DatosPlanRepository datosPlanRepository) {
        this.rules = rules;
        this.datosPlanRepository = datosPlanRepository;
    }

    public Summary summary(Customer customer) {
        return new Summary(
                customer.getId(),
                customer.getName(),
                rules.formattedRut(customer),
                customer.getProximoPago(),
                rules.daysPastDue(customer),
                rules.paymentStatus(customer).value(),
                rules.planType(customer),
                customer.isActive());
    }

    /**
     * @param canPay decided by the checkout service, which owns that rule; passed in so the presenter
     *               does not grow a second copy of it
     */
    public Detail detail(Customer customer, Integer chargeAmount, boolean canPay) {
        return new Detail(
                customer.getId(),
                customer.getName(),
                rules.formattedRut(customer),
                customer.getProximoPago(),
                rules.daysPastDue(customer),
                rules.paymentStatus(customer).value(),
                rules.planType(customer),
                customer.isActive(),
                chargeAmount,
                rules.isSuspendable(customer),
                rules.daysUntilSuspendable(customer),
                // The staff view's pay button additionally requires an active account.
                canPay && customer.isActive());
    }

    public MyAccount myAccount(Customer customer, boolean canPay) {
        return new MyAccount(
                customer.getName(),
                rules.formattedRut(customer),
                customer.getProximoPago(),
                rules.daysPastDue(customer),
                rules.paymentStatus(customer).value(),
                rules.planType(customer),
                customer.isActive(),
                rules.daysUntilSuspendable(customer),
                canPay);
    }

    /** The amount to charge, or null when the company has no active plan on file. */
    public Integer chargeAmount(Customer customer) {
        DatosPlan plan = datosPlanRepository.findActivePlan(customer.getId()).orElse(null);
        return rules.chargeAmount(plan);
    }
}
