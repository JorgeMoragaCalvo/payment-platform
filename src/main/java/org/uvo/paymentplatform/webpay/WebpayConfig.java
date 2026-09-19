package org.uvo.paymentplatform.webpay;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.uvo.paymentplatform.config.WebpayProperties;

/**
 * Wires the real gateway client. Its presence is what makes {@link WebpayClientStub} step aside.
 *
 * <p>The client is built from configuration at startup, but the configuration is <b>not</b>
 * validated here — {@link WebpayOptionsGuard} does that when a payment starts, the same lazy
 * choice the source project made. A deploy with a bad gateway configuration therefore boots green
 * and fails on the first checkout; the deploy checklist has to include starting one.
 */
@Configuration
public class WebpayConfig {

    @Bean
    public WebpayClient webpayClient(WebpayProperties properties, RestClient.Builder builder) {
        return new WebpayRestClient(properties, builder);
    }
}
