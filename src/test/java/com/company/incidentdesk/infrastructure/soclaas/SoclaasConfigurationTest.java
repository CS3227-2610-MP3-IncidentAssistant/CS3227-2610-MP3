package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** Startup validation must not make a disabled or unavailable provider break the web app. */
class SoclaasConfigurationTest {
    @Test
    void createsDisabledClientWithoutCredentials() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment());
            context.register(SoclaasConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(SoclaasClient.class));
            assertFalse(context.getBean(SoclaasSettings.class).enabled());
            context.getBean(org.springframework.boot.ApplicationRunner.class)
                    .run(new DefaultApplicationArguments(new String[0]));
        }
    }

    @Test
    void invalidEnabledSettingsFailBeforeConnectingWithoutEchoingSecret() {
        var configuration = new SoclaasConfiguration();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> configuration.soclaasSettings(new MockEnvironment().withProperty("AI_ENABLED", "true")
                        .withProperty("SOCLAAS_API_KEY", "synthetic-secret")));
        assertFalse(failure.toString().contains("synthetic-secret"));
    }

    @Test
    void enabledAiRequiresSharedPersistenceAndNeverExposesCredentials() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty("AI_ENABLED", "true")
                    .withProperty("SOCLAAS_API_KEY", "synthetic-secret").withProperty("SOCLAAS_MODEL", "test"));
            context.register(SoclaasConfiguration.class);
            context.refresh();
            var failure = assertThrows(IllegalArgumentException.class, () -> context.getBean(org.springframework.boot.ApplicationRunner.class)
                    .run(new DefaultApplicationArguments(new String[0])));
            assertFalse(failure.toString().contains("synthetic-secret"));
        }
    }
}
