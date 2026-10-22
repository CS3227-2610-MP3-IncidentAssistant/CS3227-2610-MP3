package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AdministratorInsightPromptBuilderTest {
    @Test
    void providerPayloadContainsOnlyQuestionAndApplicationInstant() {
        Instant requestedAt = Instant.parse("2026-10-23T08:30:00Z");
        var prepared = AiPromptBuilder.prepare(new AiRequest.AdministratorInsight(
                "Which category had the longest claim time this month?", requestedAt),
                UUID.randomUUID(), new AiDataSelector() { });

        var payload = tools.jackson.databind.json.JsonMapper.builder().build().readTree(prepared.data());
        assertEquals(2, payload.size());
        assertEquals("Which category had the longest claim time this month?", payload.path("question").asString());
        assertEquals("2026-10-23T08:30:00Z", payload.path("requestInstant").asString());
        assertTrue(prepared.system().contains("RANK_CATEGORIES"));
        assertTrue(prepared.system().contains("Never include prose"));
        assertFalse(prepared.data().contains("responder"));
        assertFalse(prepared.data().contains("category\":"));
        assertFalse(prepared.data().contains("model"));
    }
}
