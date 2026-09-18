package org.uvo.paymentplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.uvo.paymentplatform.cartola.CartolaReader;
import org.uvo.paymentplatform.reconciliation.MatchEngine;

/**
 * Builds the two framework-free collaborators of the importer.
 *
 * <p>Both take their tuning as constructor arguments rather than reading configuration internally,
 * which is what keeps them unit-testable without a container — and which means the container has to
 * be told how to build them. Annotating them as components would not work: neither has a no-argument
 * constructor, and their arguments are a map and a handful of ints.
 */
@Configuration
public class ReconciliationConfig {

    @Bean
    public CartolaReader cartolaReader(BankReconciliationProperties properties) {
        return CartolaReader.fromProperties(properties);
    }

    @Bean
    public MatchEngine matchEngine(BankReconciliationProperties properties) {
        return MatchEngine.fromProperties(properties);
    }
}
