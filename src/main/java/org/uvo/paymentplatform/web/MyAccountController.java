package org.uvo.paymentplatform.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.model.WebpayTransaction;
import org.uvo.paymentplatform.repository.CustomerRepository;
import org.uvo.paymentplatform.security.AuthenticatedUser;
import org.uvo.paymentplatform.service.WebpayCheckoutService;

/**
 * The customer's own page: their company's payment status, and the pay button.
 *
 * <p>No search and no suspend/reactivate — those stay staff-only. <b>The company always comes from
 * the authenticated principal, never from the request</b>, exactly as the source project took it
 * from the session.
 */
@RestController
@RequestMapping("/api/my-account")
public class MyAccountController {

    private final CustomerRepository customerRepository;
    private final WebpayCheckoutService checkoutService;
    private final CustomerPresenter presenter;
    private final ReturnUrls returnUrls;

    public MyAccountController(CustomerRepository customerRepository,
                               WebpayCheckoutService checkoutService,
                               CustomerPresenter presenter,
                               ReturnUrls returnUrls) {
        this.customerRepository = customerRepository;
        this.checkoutService = checkoutService;
        this.presenter = presenter;
        this.returnUrls = returnUrls;
    }

    /** 404 for a login with no company behind it, which is the normal shape for a staff account. */
    @GetMapping
    public ResponseEntity<CustomerPresenter.MyAccount> myAccount(@AuthenticationPrincipal AuthenticatedUser user) {
        Customer customer = customerOf(user);

        if (customer == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Integer chargeAmount = presenter.chargeAmount(customer);

        return ResponseEntity.ok(presenter.myAccount(customer, checkoutService.canPay(customer, chargeAmount)));
    }

    public record CheckoutResponse(String redirectUrl) {
    }

    /**
     * Starts the checkout. Unlike the staff view this does not require an active account: a
     * suspended customer paying is how they get reactivated. 409 when they may not pay — not due,
     * or no priced plan.
     */
    @PostMapping("/webpay-checkout")
    public ResponseEntity<CheckoutResponse> webpayCheckout(@AuthenticationPrincipal AuthenticatedUser user) {
        Customer customer = customerOf(user);

        if (customer == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return checkoutService.startCheckout(
                        customer,
                        presenter.chargeAmount(customer),
                        user.getId(),
                        "",
                        WebpayTransaction.RETURN_TO_MY_ACCOUNT,
                        returnUrls.webpayReturn())
                .map(url -> ResponseEntity.ok(new CheckoutResponse(url)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    private Customer customerOf(AuthenticatedUser user) {
        if (user == null || user.getEmpresaId() == null) {
            return null;
        }

        return customerRepository.findById(user.getEmpresaId()).orElse(null);
    }
}
