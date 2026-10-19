package com.company.incidentdesk.infrastructure.soclaas;

import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Validates local settings immediately; remote failure affects AI alone. */
@Configuration(proxyBeanMethods = false)
public class SoclaasConfiguration {
    @Bean
    SoclaasSettings soclaasSettings(Environment environment) {
        return SoclaasSettings.from(environment);
    }

    @Bean(destroyMethod = "close")
    SoclaasClient soclaasClient(SoclaasSettings settings) {
        return new SoclaasClient(settings);
    }

    @Bean
    ApplicationRunner initializeAiGateway(SoclaasSettings settings, ObjectProvider<AiGatewayMaintenance> maintenance) {
        return arguments -> {
            if (!settings.enabled()) { return; }
            var gateway = maintenance.getIfAvailable();
            if (gateway == null) { throw new IllegalArgumentException("AI requires shared PostgreSQL persistence"); }
            gateway.start();
        };
    }
}
