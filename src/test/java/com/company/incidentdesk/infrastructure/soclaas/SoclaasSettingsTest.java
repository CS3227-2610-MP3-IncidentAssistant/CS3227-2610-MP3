package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Configuration must fail safely before a provider connection is opened. */
class SoclaasSettingsTest {
    @Test
    void defaultsToDisabledWithoutCredentials() {
        assertFalse(SoclaasSettings.from(new MockEnvironment()).enabled());
    }

    @Test
    void enabledConfigurationRequiresKeyAndModelWithoutEchoingValues() {
        var environment = new MockEnvironment().withProperty("AI_ENABLED", "true")
                .withProperty("SOCLAAS_API_KEY", "synthetic-secret");
        var error = assertThrows(IllegalArgumentException.class, () -> SoclaasSettings.from(environment));
        assertTrue(error.getMessage().contains("SOCLAAS_MODEL"));
        assertFalse(error.toString().contains("synthetic-secret"));
    }

    @Test
    void acceptsOnlyExactProductionEndpoint() {
        for (String url : new String[]{"http://soclaas-api.comp.nus.edu.sg/v1",
                "https://evil.test/v1", "https://soclaas-api.comp.nus.edu.sg/v1?key=synthetic-secret",
                "https://user:synthetic-secret@soclaas-api.comp.nus.edu.sg/v1",
                "https://soclaas-api.comp.nus.edu.sg/v1/"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> SoclaasSettings.from(valid().withProperty("SOCLAAS_BASE_URL", url)));
            assertFalse(error.toString().contains("synthetic-secret"));
        }
    }

    @Test
    void rejectsInvalidAndRaisedLimitsWithoutEchoingValues() {
        Map<String, String> invalid = Map.of("AI_CONNECT_TIMEOUT_MS", "3001", "AI_REQUEST_TIMEOUT_MS", "30001",
                "AI_MAX_RESPONSE_BYTES", "65537", "AI_MAX_INPUT_BYTES", "16385", "AI_MAX_INPUT_TOKENS", "8001",
                "AI_MAX_OUTPUT_BYTES", "16385", "AI_MAX_OUTPUT_TOKENS", "1201");
        invalid.forEach((name, value) -> assertThrows(IllegalArgumentException.class,
                () -> SoclaasSettings.from(valid().withProperty(name, value))));
        assertThrows(IllegalArgumentException.class,
                () -> SoclaasSettings.from(valid().withProperty("AI_MAX_OUTPUT_TOKENS", "0")));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> SoclaasSettings.from(valid().withProperty("AI_REQUEST_TIMEOUT_MS", "synthetic-secret")));
        assertFalse(failure.toString().contains("synthetic-secret"));
        assertThrows(IllegalArgumentException.class,
                () -> SoclaasSettings.from(valid().withProperty("AI_ENABLED", "truthy")));
    }

    @Test
    void permitsLowerLimitsAndRedactsStringRepresentation() {
        var settings = SoclaasSettings.from(valid().withProperty("AI_MAX_OUTPUT_TOKENS", "64"));
        assertEquals(64, settings.maxOutputTokens());
        assertFalse(settings.toString().contains("synthetic-secret"));
        assertTrue(settings.enabled());
        assertThrows(IllegalArgumentException.class,
                () -> SoclaasSettings.from(valid().withProperty("SOCLAAS_API_KEY", "secret\r\nHeader: value")));
    }

    @Test
    void snapshotsValidatedLimitsInsteadOfRereadingMutableEnvironment() {
        var environment = valid().withProperty("AI_MAX_OUTPUT_TOKENS", "64");
        var settings = SoclaasSettings.from(environment);
        environment.withProperty("AI_MAX_OUTPUT_TOKENS", "9999");
        assertEquals(64, settings.maxOutputTokens());
    }

    private static MockEnvironment valid() {
        return new MockEnvironment().withProperty("AI_ENABLED", "true")
                .withProperty("SOCLAAS_API_KEY", "synthetic-secret").withProperty("SOCLAAS_MODEL", "test-model");
    }
}
