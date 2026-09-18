package org.uvo.paymentplatform.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Boot 4 moved this out of spring-boot-test into the webmvc test module; the old
// boot.test.autoconfigure.web.servlet package no longer exists.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * The full application against a real MySQL — the one the datasource points at.
 *
 * <p>Not an in-memory database, and not a slice: the RUT lookup relies on MySQL string functions,
 * the payment idempotency guards rely on a unique index accepting several NULLs, the CSRF exemption
 * and the redirect are part of what is tested, and Flyway applying the mirror schema plus the six
 * production statements is itself the first rehearsal of that change. In CI the datasource is a
 * service container; locally it is the developer's own MySQL.
 *
 * <p><b>Cases are not wrapped in a transaction.</b> The code under test opens its own transactions
 * — the gateway return commits a claim before recording a payment — and the replay cases need to
 * observe real commits. So each case starts from empty tables instead: everything the module
 * touches is truncated before it runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeWebpayClient.class)
public abstract class AbstractIntegrationTest {

    /** Every table a case can write, in no particular order — the checks are off while truncating. */
    private static final List<String> TABLES = List.of(
            "webpay_transactions",
            "bank_movements",
            "bank_statements",
            "suscriptor_payments",
            "datos_plan",
            "empresas",
            "users");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected FakeWebpayClient webpay;

    @BeforeEach
    void cleanDatabase() {
        webpay.reset();

        // The mirror schema carries the real foreign keys from production, so the payment table
        // cannot be emptied before its parents unless the checks are suspended.
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : TABLES) {
                jdbc.execute("TRUNCATE TABLE " + table);
            }
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    protected long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
