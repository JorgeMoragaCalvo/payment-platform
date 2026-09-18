package org.uvo.paymentplatform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;
import org.uvo.paymentplatform.model.WebpayTransaction;
import org.uvo.paymentplatform.security.SecurityConfig;
import org.uvo.paymentplatform.service.WebpayReturnService;

/**
 * Where the gateway sends the customer's browser back after checkout.
 *
 * <p><b>Not part of the JSON API</b>, and not a REST controller: its caller is the gateway's
 * browser redirect, not the frontend. Two properties of that request shape everything here, and
 * both were live bugs in the source project:
 *
 * <ul>
 *   <li>It is <b>cross-site</b>. It carries no CSRF token — hence the exemption in the security
 *       configuration — and no session cookie, because the cookie is SameSite=Lax. So none of the
 *       state from the page that started the payment is available; the pending payment is looked
 *       up by buy order, which travels in the request or comes back from the commit.
 *   <li>It has <b>four shapes, and only one is a payment</b>:
 *       <pre>
 *       token_ws alone                            → completed checkout, commit it
 *       TBK_TOKEN present (with or without token) → the customer aborted, possibly AFTER authorising
 *       TBK_ID_SESION + TBK_ORDEN_COMPRA, no token → the payment form timed out
 *       anything else                             → log and bail
 *       </pre>
 *       Committing the abort shape would record a payment the customer explicitly backed out of, so
 *       the presence of {@code TBK_TOKEN} vetoes the commit whatever else arrived.
 * </ul>
 *
 * <p>Accepts GET as well as POST, as the source route did: the gateway posts, but a customer who
 * refreshes the result page issues a GET with the same parameters, and that replay must land on
 * the same idempotent path rather than a 405.
 */
@Controller
public class WebpayReturnController {

    private final WebpayReturnService returnService;
    private final ReturnUrls returnUrls;

    public WebpayReturnController(WebpayReturnService returnService, ReturnUrls returnUrls) {
        this.returnService = returnService;
        this.returnUrls = returnUrls;
    }

    @PostMapping(SecurityConfig.WEBPAY_RETURN_PATH)
    public RedirectView handlePost(@RequestParam(name = "token_ws", required = false) String token,
                                   @RequestParam(name = "TBK_TOKEN", required = false) String abortToken,
                                   @RequestParam(name = "TBK_ORDEN_COMPRA", required = false) String buyOrder,
                                   HttpServletRequest request) {
        return handle(token, abortToken, buyOrder, request);
    }

    @GetMapping(SecurityConfig.WEBPAY_RETURN_PATH)
    public RedirectView handleGet(@RequestParam(name = "token_ws", required = false) String token,
                                  @RequestParam(name = "TBK_TOKEN", required = false) String abortToken,
                                  @RequestParam(name = "TBK_ORDEN_COMPRA", required = false) String buyOrder,
                                  HttpServletRequest request) {
        return handle(token, abortToken, buyOrder, request);
    }

    private RedirectView handle(String token, String abortToken, String buyOrder, HttpServletRequest request) {
        WebpayReturnService.Outcome outcome;

        // The customer backed out, or the payment form expired. Neither is a payment; nothing is
        // committed and nothing is written beyond closing the row.
        if (abortToken != null || (token == null && buyOrder != null)) {
            outcome = returnService.abandon(buyOrder, abortToken != null);
        } else if (token == null || token.isBlank()) {
            outcome = returnService.unrecognised(request.getParameterMap().keySet());
        } else {
            outcome = returnService.complete(token);
        }

        return back(outcome);
    }

    /**
     * Back to whichever page started the payment: the customer's portal, or the staff page with its
     * search term restored. Both come off the transaction row rather than the session, which does
     * not survive the cross-site POST. With no row at all there is no context, so fall back to the
     * staff page — a customer landing there is sent on to their own account by the frontend.
     */
    private RedirectView back(WebpayReturnService.Outcome outcome) {
        WebpayTransaction transaction = outcome.transaction();

        if (transaction != null && WebpayTransaction.RETURN_TO_MY_ACCOUNT.equals(transaction.getReturnTo())) {
            return redirect(returnUrls.myAccount(outcome.result()));
        }

        String search = transaction == null ? "" : transaction.getSearch();

        return redirect(returnUrls.paymentAlert(search, outcome.result()));
    }

    private static RedirectView redirect(String url) {
        RedirectView view = new RedirectView(url);
        // The target is a full URL, or a root-relative path on this same origin; never resolve it
        // against the controller's own path.
        view.setContextRelative(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}
