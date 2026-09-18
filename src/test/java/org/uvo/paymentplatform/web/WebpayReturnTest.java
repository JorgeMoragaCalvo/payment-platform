package org.uvo.paymentplatform.web;

import org.junit.jupiter.api.Test;
import org.uvo.paymentplatform.security.SecurityConfig;
import org.uvo.paymentplatform.support.AbstractIntegrationTest;
import org.uvo.paymentplatform.webpay.WebpayCommitResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gateway boundary itself — previously the only part of the payment path with no test, which
 * is how two blockers survived: the return route was not exempt from CSRF (a rejected request on
 * every real payment) and the pending payload lived in a SameSite=Lax session cookie that a
 * cross-site POST never sends.
 *
 * <p>Both are regression-tested here, along with the three return shapes that are not payments and
 * the replay that must not buy a second month. The clock is pinned to 2026-03-10 09:00.
 */
class WebpayReturnTest extends AbstractIntegrationTest {

    private static final String RETURN = SecurityConfig.WEBPAY_RETURN_PATH;
    private static final int AMOUNT = 35000;
    private static final long USER_ID = 99;
    private static final String SESSION = "pa-test-session";

    // ------------------------------------------------------------------------------- fixtures

    /** A company on a 30-day plan priced at AMOUNT, due on the given date. */
    private long company(String due, String estado) {
        jdbc.update("""
                INSERT INTO empresas (rut, RazonSocial, nombre_fantasia, proximoPago, tipoPlan, estado, created_at, updated_at)
                VALUES ('76543210-3', 'Comercial Andes SpA', 'Comercial Andes SpA', ?, 'Mensual', ?, NOW(), NOW())
                """, due, estado);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO datos_plan (empresa_id, plan_id, monto_plan, monto_hardware, fecha_vencimiento, periodo_plan, periodo_days, estado, created_at, updated_at)
                VALUES (?, 7, ?, 0, ?, 'Mensual', 30, 1, NOW(), NOW())
                """, id, AMOUNT, due);

        return id;
    }

    private long company(String due) {
        return company(due, "1");
    }

    private long company() {
        return company("2026-03-12");
    }

    /**
     * The user the pending transaction names as the payer. The source project's tests never insert
     * it, and get away with that only because their in-memory database has no foreign keys; the
     * mirror here has the real one, so a payment for a user that does not exist fails on insert —
     * which is exactly what production would do.
     */
    private void user99() {
        jdbc.update("""
                INSERT INTO users (id, name, email, password, status, created_at, updated_at)
                VALUES (?, 'Staff', 'staff@example.test', 'x', 1, NOW(), NOW())
                """, USER_ID);
    }

    private String pending(long empresaId, String returnTo) {
        String buyOrder = "PA" + empresaId + "-1772000000-AB";

        jdbc.update("""
                INSERT INTO webpay_transactions (buy_order, session_id, empresa_id, user_id, amount, search, return_to, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', NOW(), NOW())
                """, buyOrder, SESSION, empresaId, USER_ID, AMOUNT, String.valueOf(empresaId), returnTo);

        return buyOrder;
    }

    private String pending(long empresaId) {
        return pending(empresaId, "payment-alert");
    }

    private WebpayCommitResponse approved(String buyOrder) {
        return new WebpayCommitResponse(buyOrder, 0, "AUTHORIZED", AMOUNT, true);
    }

    // ------------------------------------------------------------------------------- readers

    private Map<String, Object> transaction(String buyOrder) {
        return jdbc.queryForMap("SELECT status, suscriptor_payment_id, response_code FROM webpay_transactions WHERE buy_order = ?", buyOrder);
    }

    private LocalDate dueDate(long empresaId) {
        return jdbc.queryForObject("SELECT proximoPago FROM empresas WHERE id = ?", LocalDate.class, empresaId);
    }

    private String estado(long empresaId) {
        return jdbc.queryForObject("SELECT estado FROM empresas WHERE id = ?", String.class, empresaId);
    }

    private static String paymentAlertUrl(String search, String result) {
        return "/payment-alert?search=" + search + "&payment=" + result;
    }

    // ------------------------------------------------------------------------------ the cases

    /**
     * Without the exemption the gateway's cross-site POST is rejected and the handler never runs —
     * after the card has been charged. Asserted over HTTP here, which the source project could not
     * do because its CSRF middleware short-circuits under test. The second half keeps the exemption
     * narrow: an API route without a token must still be refused.
     */
    @Test
    void the_return_route_is_exempt_from_csrf() throws Exception {
        webpay.commitReturns(approved("PA0-unknown"));

        mvc.perform(post(RETURN).param("token_ws", "tok-123"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(post("/api/customers/lookup").contentType("application/json").content("{\"search\":\"1\"}"))
                .andExpect(status().isForbidden());
    }

    /** The handler must find the pending payment with nothing but the request body — no session survives the cross-site POST. */
    @Test
    void an_approved_payment_is_recorded_without_any_session() throws Exception {
        user99();
        long empresa = company("2026-03-12");
        String buyOrder = pending(empresa);
        webpay.commitReturns(approved(buyOrder));

        mvc.perform(post(RETURN).param("token_ws", "tok-123"))
                .andExpect(redirectedUrl(paymentAlertUrl(String.valueOf(empresa), "success")));

        Map<String, Object> payment = jdbc.queryForMap("SELECT amount, external_reference, user_id FROM suscriptor_payments");
        assertThat(count("suscriptor_payments")).isEqualTo(1);
        assertThat(new BigDecimal(payment.get("amount").toString())).isEqualByComparingTo(BigDecimal.valueOf(AMOUNT));
        assertThat(payment.get("external_reference")).isEqualTo(buyOrder);
        assertThat(((Number) payment.get("user_id")).longValue()).isEqualTo(USER_ID);

        // max(due date 2026-03-12, paid 2026-03-10) + 30 days
        assertThat(dueDate(empresa)).isEqualTo(LocalDate.of(2026, 4, 11));

        Map<String, Object> tx = transaction(buyOrder);
        assertThat(tx.get("status")).isEqualTo("authorized");
        assertThat(tx.get("suscriptor_payment_id")).isNotNull();
    }

    @Test
    void paying_reactivates_a_suspended_company() throws Exception {
        user99();
        long empresa = company("2026-03-01", "0");
        String buyOrder = pending(empresa);
        webpay.commitReturns(approved(buyOrder));

        mvc.perform(post(RETURN).param("token_ws", "tok-123"));

        assertThat(estado(empresa)).isEqualTo("1");
    }

    /** The one that silently cost a month before: a refreshed tab, a retried delivery or two concurrent requests all replay this POST. */
    @Test
    void a_replayed_return_does_not_record_a_second_payment() throws Exception {
        user99();
        long empresa = company("2026-03-12");
        String buyOrder = pending(empresa);
        webpay.commitReturns(approved(buyOrder));

        mvc.perform(post(RETURN).param("token_ws", "tok-123"));
        mvc.perform(post(RETURN).param("token_ws", "tok-123"));
        mvc.perform(post(RETURN).param("token_ws", "tok-123"));

        assertThat(count("suscriptor_payments")).isEqualTo(1);
        assertThat(dueDate(empresa)).isEqualTo(LocalDate.of(2026, 4, 11));
    }

    /**
     * The gateway sends TBK_TOKEN alongside token_ws when the customer backed out after the card
     * was authorised. Committing it would book a payment they explicitly abandoned — so the gateway
     * must not even be asked.
     */
    @Test
    void an_abort_carrying_both_tokens_is_never_committed() throws Exception {
        user99();
        long empresa = company("2026-03-12");
        String buyOrder = pending(empresa);
        webpay.commitReturns(approved(buyOrder));

        mvc.perform(post(RETURN)
                        .param("token_ws", "tok-123")
                        .param("TBK_TOKEN", "abort-tok")
                        .param("TBK_ORDEN_COMPRA", buyOrder)
                        .param("TBK_ID_SESION", SESSION))
                .andExpect(redirectedUrl(paymentAlertUrl(String.valueOf(empresa), "aborted")));

        assertThat(webpay.commitCalls()).isZero();
        assertThat(count("suscriptor_payments")).isZero();
        assertThat(transaction(buyOrder).get("status")).isEqualTo("aborted");
        assertThat(dueDate(empresa)).isEqualTo(LocalDate.of(2026, 3, 12));
    }

    @Test
    void a_cancelled_checkout_records_nothing() throws Exception {
        user99();
        String buyOrder = pending(company());

        mvc.perform(post(RETURN)
                .param("TBK_TOKEN", "abort-tok")
                .param("TBK_ORDEN_COMPRA", buyOrder)
                .param("TBK_ID_SESION", SESSION));

        assertThat(count("suscriptor_payments")).isZero();
        assertThat(transaction(buyOrder).get("status")).isEqualTo("aborted");
    }

    /** Form timeout: no token of any kind, only the session and order. */
    @Test
    void a_timed_out_payment_form_records_nothing() throws Exception {
        user99();
        String buyOrder = pending(company());

        mvc.perform(post(RETURN)
                .param("TBK_ID_SESION", SESSION)
                .param("TBK_ORDEN_COMPRA", buyOrder));

        assertThat(count("suscriptor_payments")).isZero();
        assertThat(transaction(buyOrder).get("status")).isEqualTo("aborted");
    }

    @Test
    void a_declined_card_records_nothing() throws Exception {
        user99();
        long empresa = company();
        String buyOrder = pending(empresa);
        webpay.commitReturns(new WebpayCommitResponse(buyOrder, -1, "FAILED", AMOUNT, false));

        mvc.perform(post(RETURN).param("token_ws", "tok-123"))
                .andExpect(redirectedUrl(paymentAlertUrl(String.valueOf(empresa), "declined")));

        assertThat(count("suscriptor_payments")).isZero();
        assertThat(transaction(buyOrder).get("status")).isEqualTo("declined");
    }

    /** Should be impossible — the amount is ours. If it happens, guessing which figure to book is worse than flagging it for a human. */
    @Test
    void an_amount_mismatch_is_not_recorded() throws Exception {
        user99();
        long empresa = company();
        String buyOrder = pending(empresa);
        webpay.commitReturns(new WebpayCommitResponse(buyOrder, 0, "AUTHORIZED", 1000, true));

        mvc.perform(post(RETURN).param("token_ws", "tok-123"))
                .andExpect(redirectedUrl(paymentAlertUrl(String.valueOf(empresa), "error")));

        assertThat(count("suscriptor_payments")).isZero();
        assertThat(transaction(buyOrder).get("status")).isEqualTo("failed");
    }

    /** A commit we could not complete is not a decline: the charge may exist, so the UI must not tell anyone to simply try again. */
    @Test
    void an_unreachable_gateway_reports_an_error_not_a_decline() throws Exception {
        user99();
        pending(company());
        webpay.commitThrows();

        mvc.perform(post(RETURN).param("token_ws", "tok-123"))
                .andExpect(redirectedUrl(paymentAlertUrl("", "error")));

        assertThat(count("suscriptor_payments")).isZero();
    }

    @Test
    void a_commit_for_an_unknown_buy_order_records_nothing() throws Exception {
        company();
        webpay.commitReturns(approved("PA999-nope"));

        mvc.perform(post(RETURN).param("token_ws", "tok-123"))
                .andExpect(redirectedUrl(paymentAlertUrl("", "failed")));

        assertThat(count("suscriptor_payments")).isZero();
    }

    @Test
    void a_return_with_no_recognisable_parameters_records_nothing() throws Exception {
        company();

        mvc.perform(post(RETURN))
                .andExpect(redirectedUrl(paymentAlertUrl("", "failed")));

        assertThat(count("suscriptor_payments")).isZero();
    }

    /** The customer portal and the staff page share this handler; the landing page comes off the stored row, not the (absent) session. */
    @Test
    void a_customer_portal_payment_returns_to_mi_cuenta() throws Exception {
        user99();
        String buyOrder = pending(company(), "mi-cuenta");
        webpay.commitReturns(approved(buyOrder));

        mvc.perform(post(RETURN).param("token_ws", "tok-123"))
                .andExpect(redirectedUrl("/mi-cuenta?payment=success"));
    }

    /**
     * Not in the source project — it could not be, because its test database had no foreign keys.
     * The pending transaction names a payer that does not exist in the user table. The commit
     * succeeds (the card is charged), then the payment insert fails on the foreign key. Because the
     * claim and the payment commit together, the claim rolls back with it: the row must still say
     * pending, not authorized-with-no-payment — and the customer must land on the "error" result,
     * whose copy says a charge may exist and not to simply retry. A blank server error after a
     * real charge is exactly what must not happen.
     *
     * <p>This is the production scenario the runbook's service-account item warns about, and the
     * reason the writes live in their own transactional bean.
     */
    @Test
    void a_payer_the_user_table_does_not_know_leaves_the_transaction_pending() throws Exception {
        long empresa = company();
        String buyOrder = pending(empresa); // user 99 deliberately NOT inserted
        webpay.commitReturns(approved(buyOrder));

        mvc.perform(post(RETURN).param("token_ws", "tok-123"))
                .andExpect(redirectedUrl(paymentAlertUrl(String.valueOf(empresa), "error")));

        assertThat(count("suscriptor_payments")).isZero();
        assertThat(transaction(buyOrder).get("status")).isEqualTo("pending");
        assertThat(dueDate(empresa)).isEqualTo(LocalDate.of(2026, 3, 12));
    }
}
