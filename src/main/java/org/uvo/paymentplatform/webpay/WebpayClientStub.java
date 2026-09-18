package org.uvo.paymentplatform.webpay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gateway client the container falls back to while no real one is configured.
 *
 * <p>It exists so the application can start — every checkout endpoint injects
 * {@link WebpayClient}, and without any implementation the context would refuse to boot, taking the
 * customer list and the reconciliation queue down with it. It does not exist to make payments look
 * like they work: both calls throw, with a message that says what is missing.
 *
 * <p>Registering any other {@link WebpayClient} bean replaces this one automatically.
 */
@Configuration
public class WebpayClientStub {

    static final String NOT_CONFIGURED =
            "No hay un cliente de Webpay configurado. Falta la implementación real de WebpayClient "
                    + "(la integración con el SDK de Transbank está pendiente).";

    @Bean
    @ConditionalOnMissingBean(WebpayClient.class)
    public WebpayClient webpayClient() {
        return new WebpayClient() {
            @Override
            public WebpayCreateResponse create(String buyOrder, String sessionId, int amount, String returnUrl) {
                throw new IllegalStateException(NOT_CONFIGURED);
            }

            @Override
            public WebpayCommitResponse commit(String token) {
                throw new IllegalStateException(NOT_CONFIGURED);
            }
        };
    }
}
