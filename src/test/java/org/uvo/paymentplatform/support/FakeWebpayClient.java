package org.uvo.paymentplatform.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.uvo.paymentplatform.webpay.WebpayClient;
import org.uvo.paymentplatform.webpay.WebpayCommitResponse;
import org.uvo.paymentplatform.webpay.WebpayCreateResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A gateway client the tests can programme, standing in for the real one exactly the way the
 * source project's tests bound a fake in place of the SDK transaction object.
 *
 * <p>The commit path is otherwise impossible to exercise without talking to the gateway, and the
 * gateway boundary is the one part of the payment path that most needs exercising: two blockers
 * survived there once, and this is what catches the next one.
 *
 * <p>Also pins the clock. Every assertion of the form "a payment on the 10th against a due date of
 * the 12th lands on 11 April" depends on the application asking the injected clock and nothing else.
 */
@TestConfiguration
public class FakeWebpayClient {

    /** The instant the source project's tests fix: 2026-03-10 09:00 in Chile. */
    public static final ZoneId ZONE = ZoneId.of("America/Santiago");
    public static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z"); // 09:00 in Santiago (UTC-3)

    private volatile Supplier<WebpayCommitResponse> onCommit =
            () -> { throw new IllegalStateException("commit() not programmed for this test"); };

    private final AtomicInteger commitCalls = new AtomicInteger();

    /**
     * Named differently from the production bean on purpose. {@code @Primary} settles which of two
     * beans wins, but two beans with the same NAME are a definition override, which Boot refuses at
     * startup — every test in the class would then fail before running.
     */
    @Bean
    @Primary
    public WebpayClient fakeWebpayClient() {
        return new WebpayClient() {
            @Override
            public WebpayCreateResponse create(String buyOrder, String sessionId, int amount, String returnUrl) {
                return new WebpayCreateResponse("https://webpay.test/init", "tok-created");
            }

            @Override
            public WebpayCommitResponse commit(String token) {
                commitCalls.incrementAndGet();
                return onCommit.get();
            }
        };
    }

    @Bean
    @Primary
    public Clock testClock() {
        return Clock.fixed(NOW, ZONE);
    }

    /** The gateway answers every commit with this canned response. */
    public void commitReturns(WebpayCommitResponse response) {
        onCommit = () -> response;
    }

    /** The gateway cannot be reached: every commit throws. */
    public void commitThrows() {
        onCommit = () -> { throw new RuntimeException("Transbank unreachable"); };
    }

    public int commitCalls() {
        return commitCalls.get();
    }

    public void reset() {
        commitCalls.set(0);
        onCommit = () -> { throw new IllegalStateException("commit() not programmed for this test"); };
    }
}
