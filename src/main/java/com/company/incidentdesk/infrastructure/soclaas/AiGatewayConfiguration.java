package com.company.incidentdesk.infrastructure.soclaas;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import io.micrometer.core.instrument.MeterRegistry;

/** No AI route is exposed. Role integrations call the trusted backend gateway separately. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "AI_ENABLED", havingValue = "true")
public class AiGatewayConfiguration {
    @Bean
    AiGatewayControl aiGatewayControl(JdbcTemplate jdbc, PlatformTransactionManager manager, SoclaasSettings settings) {
        return new AiGatewayControl(jdbc, manager, Clock.systemUTC(), settings.usageLimits());
    }

    @Bean(destroyMethod = "close")
    SoclaasBudgetClient soclaasBudgetClient(SoclaasSettings settings) {
        return new SoclaasBudgetClient(settings, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    SecuredAiGateway securedAiGateway(SoclaasSettings settings, SoclaasClient client, AiGatewayControl control,
            JdbcTemplate jdbc, ObjectProvider<AiDataSelector> selector, MeterRegistry metrics) {
        return new SecuredAiGateway(settings, client, control, jdbc,
                selector.getIfAvailable(() -> new AiDataSelector() { }), Clock.systemUTC(), metrics);
    }

    @Bean(destroyMethod = "close")
    AiGatewayMaintenance aiGatewayMaintenance(SoclaasSettings settings, SoclaasClient client, AiGatewayControl control,
            SoclaasBudgetClient budget, MeterRegistry metrics) {
        return new AiGatewayMaintenance(settings, control, client, budget::load, Clock.systemUTC(), metrics);
    }
}
