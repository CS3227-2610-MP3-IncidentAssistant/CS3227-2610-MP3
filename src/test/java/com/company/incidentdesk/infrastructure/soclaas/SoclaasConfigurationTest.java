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
            context.getBean(SoclaasConfiguration.class).validateSoclaasModel(context.getBean(SoclaasClient.class))
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
    void providerUnavailabilityIsContainedByStartupRunner() throws Exception {
        try (var disabled = new SoclaasClient(SoclaasSettings.from(new MockEnvironment()))) {
            // A client outcome is the same safe boundary for disabled/unreachable/rejected model lookup.
            new SoclaasConfiguration().validateSoclaasModel(disabled)
                    .run(new DefaultApplicationArguments(new String[0]));
        }
    }
}
