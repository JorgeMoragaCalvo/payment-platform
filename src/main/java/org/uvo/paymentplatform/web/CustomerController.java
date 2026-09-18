package org.uvo.paymentplatform.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.paymentplatform.config.PaymentAlertProperties;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.WebpayTransaction;
import org.uvo.paymentplatform.repository.CustomerRepository;
import org.uvo.paymentplatform.security.AuthenticatedUser;
import org.uvo.paymentplatform.service.CustomerAccountService;
import org.uvo.paymentplatform.service.CustomerLookupService;
import org.uvo.paymentplatform.service.CustomerRules;
import org.uvo.paymentplatform.service.WebpayCheckoutService;

import java.time.LocalDate;
import java.util.List;

/**
 * The staff page: every customer colour-coded by payment status, the RUT/id lookup, and the
 * actions — pay on the customer's behalf, suspend, reactivate.
 *
 * <p>Admin-only at the class level, and re-checked on every request by the filter chain. The source
 * project had to re-assert this by hand inside each action, because its component round trips
 * bypassed the route gate; that footgun does not exist here.
 */
@RestController
@RequestMapping("/api/customers")
@PreAuthorize("hasRole('ADMIN')")
public class CustomerController {

    private static final int PAGE_SIZE = 15;

    private final CustomerRepository customerRepository;
    private final CustomerLookupService lookupService;
    private final CustomerAccountService accountService;
    private final WebpayCheckoutService checkoutService;
    private final CustomerPresenter presenter;
    private final CustomerRules rules;
    private final PaymentAlertProperties properties;
    private final ReturnUrls returnUrls;

    public CustomerController(CustomerRepository customerRepository,
                              CustomerLookupService lookupService,
                              CustomerAccountService accountService,
                              WebpayCheckoutService checkoutService,
                              CustomerPresenter presenter,
                              CustomerRules rules,
                              PaymentAlertProperties properties,
                              ReturnUrls returnUrls) {
        this.customerRepository = customerRepository;
        this.lookupService = lookupService;
        this.accountService = accountService;
        this.checkoutService = checkoutService;
        this.presenter = presenter;
        this.rules = rules;
        this.properties = properties;
        this.returnUrls = returnUrls;
    }

    public record StatusCounts(long onTime, long dueSoon, long overdue) {
    }

    public record CustomerPage(List<CustomerPresenter.Summary> customers,
                               int page,
                               int totalPages,
                               long totalCount,
                               StatusCounts counts) {
    }

    /**
     * The paginated list, most urgent first, optionally filtered to one status. The three counts
     * are always for the whole table regardless of the filter — they are what the filter tiles show.
     *
     * <p>The status ranges here are SQL date arithmetic, and they exist a second time as per-row
     * logic in the rules. Both have to move together or the badge and the filter disagree.
     */
    @GetMapping
    public CustomerPage list(@RequestParam(defaultValue = "") String status,
                             @RequestParam(defaultValue = "0") int page) {
        LocalDate today = rules.today();
        LocalDate dueSoonEnd = today.plusDays(properties.dueSoonDays());
        PageRequest pageable = PageRequest.of(Math.max(0, page), PAGE_SIZE);

        Page<Customer> result = switch (status) {
            case "overdue" -> customerRepository.findOverdue(today, pageable);
            case "due_soon" -> customerRepository.findDueSoon(today, dueSoonEnd, pageable);
            case "on_time" -> customerRepository.findOnTime(dueSoonEnd, pageable);
            default -> customerRepository.findAllOrderByUrgency(pageable);
        };

        StatusCounts counts = new StatusCounts(
                customerRepository.countOnTime(dueSoonEnd),
                customerRepository.countDueSoon(today, dueSoonEnd),
                customerRepository.countByProximoPagoLessThan(today));

        return new CustomerPage(
                result.map(presenter::summary).getContent(),
                result.getNumber(),
                result.getTotalPages(),
                result.getTotalElements(),
                counts);
    }

    public record LookupRequest(
            @NotBlank(message = "Ingrese un RUT o ID de cliente.")
            @Size(max = 20, message = "Ingrese un RUT o ID de cliente.")
            String search) {
    }

    /**
     * The search box and the row click, which are the same lookup: a row click searches by the
     * row's id, so the search term stays populated for the checkout round trip. A term that cannot
     * be a RUT is rejected before touching the database.
     */
    @PostMapping("/lookup")
    public ResponseEntity<CustomerPresenter.Detail> lookup(@Valid @RequestBody LookupRequest request) {
        return lookupService.byRutOrId(request.search())
                .map(this::detail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerPresenter.Detail> get(@PathVariable long id) {
        return customerRepository.findById(id)
                .map(this::detail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record CheckoutRequest(String search) {
    }

    public record CheckoutResponse(String redirectUrl) {
    }

    /**
     * Starts a checkout on the customer's behalf. Answers with the gateway URL rather than
     * redirecting: a JSON API cannot move the browser itself. 409 when the customer may not pay —
     * not due, no priced plan, or suspended.
     */
    @PostMapping("/{id}/webpay-checkout")
    public ResponseEntity<CheckoutResponse> webpayCheckout(@PathVariable long id,
                                                           @RequestBody(required = false) CheckoutRequest request,
                                                           @AuthenticationPrincipal AuthenticatedUser staff) {
        Customer customer = customerRepository.findById(id).orElse(null);

        if (customer == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // Unlike the customer portal, the staff view does not let a suspended account be paid for
        // from here; reactivation is its own explicit action on this page.
        if (!customer.isActive()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        String search = request == null || request.search() == null ? String.valueOf(id) : request.search();

        return checkoutService.startCheckout(
                        customer,
                        presenter.chargeAmount(customer),
                        staff.getId(),
                        search,
                        WebpayTransaction.RETURN_TO_PAYMENT_ALERT,
                        returnUrls.webpayReturn())
                .map(url -> ResponseEntity.ok(new CheckoutResponse(url)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    /**
     * Manual, staff-confirmed suspension. The "are you sure" step is the frontend's: suspending
     * twice is a no-op, not a double charge, so there is nothing here that needs the server to
     * remember a confirmation was opened. Never automatic.
     */
    @PostMapping("/{id}/suspend")
    public ResponseEntity<CustomerPresenter.Detail> suspend(@PathVariable long id) {
        Customer customer = customerRepository.findById(id).orElse(null);

        if (customer == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Customer suspended = accountService.suspend(customer, rules.isSuspendable(customer));

        return ResponseEntity.ok(detail(suspended));
    }

    /** Manual override to reactivate without waiting for a payment. */
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<CustomerPresenter.Detail> reactivate(@PathVariable long id) {
        Customer customer = customerRepository.findById(id).orElse(null);

        if (customer == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok(detail(accountService.reactivate(customer)));
    }

    private CustomerPresenter.Detail detail(Customer customer) {
        Integer chargeAmount = presenter.chargeAmount(customer);
        boolean canPay = checkoutService.canPay(customer, chargeAmount);

        return presenter.detail(customer, chargeAmount, canPay);
    }
}
