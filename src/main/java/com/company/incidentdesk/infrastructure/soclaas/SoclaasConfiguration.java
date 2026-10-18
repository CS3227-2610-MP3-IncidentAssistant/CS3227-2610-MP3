package com.company.incidentdesk.infrastructure.soclaas;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Wires only the provider adapter; no controller or production AI admission is introduced. */
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
    ApplicationRunner validateSoclaasModel(SoclaasClient client) {
        return arguments -> {
            try {
                client.validateModel();
            } catch (SoclaasException failure) {
                System.getLogger(SoclaasConfiguration.class.getName()).log(System.Logger.Level.INFO,
                        "SoCLaaS adapter unavailable: {0}", failure.category());
            }
        };
    }
}
