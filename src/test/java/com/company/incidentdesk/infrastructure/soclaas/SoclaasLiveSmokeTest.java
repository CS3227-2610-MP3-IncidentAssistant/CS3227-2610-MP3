package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;

/** Explicit opt-in synthetic probe; normal tests and CI never require provider credentials. */
@EnabledIfEnvironmentVariable(named = "SOCLAAS_LIVE_SMOKE", matches = "true")
class SoclaasLiveSmokeTest {
    @Test
    void validatesConfiguredModelAndCompletesOneSyntheticRequest() {
        try (var client = new SoclaasClient(SoclaasSettings.from(new StandardEnvironment()))) {
            assertNotNull(client.validateModel());
            var completion = client.complete("Reply with exactly OK. Do not explain or reason.",
                    "{\"purpose\":\"synthetic connectivity check\"}");
            assertFalse(completion.text().isBlank());
            System.out.println("Synthetic provider check: catalog and non-blank completion verified; usage="
                    + completion.usage());
        }
    }
}
