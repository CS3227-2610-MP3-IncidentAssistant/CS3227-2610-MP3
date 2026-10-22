package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class AdministratorInsightControllerTest {
    @Test
    void acceptsOnlyTheClosedBrowserRequestShape() {
        AdministratorInsightService service = mock(AdministratorInsightService.class);
        AdministratorInsightController controller = new AdministratorInsightController(service);
        MockHttpServletRequest request = json("""
                {"question":"What changed this month?","categories":[],"responderIds":[],"model":"forbidden"}
                """);

        var response = controller.answer(principal(), request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void returnsOnlyTheDeterministicServiceResponse() {
        AdministratorInsightService service = mock(AdministratorInsightService.class);
        var expected = new AdministratorInsightRenderer.Response("ANSWER", null, "summary", "INCIDENT_COUNT",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-23T00:00:00Z"),
                Instant.parse("2026-10-23T00:00:00Z"), List.of(), "advisory", "COMPANY");
        when(service.answer(eq("admin"), any())).thenReturn(expected);
        AdministratorInsightController controller = new AdministratorInsightController(service);

        var response = controller.answer(principal(), json("{\"question\":\"What changed this month?\"}"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void unauthenticatedRequestsReceiveNonEnumeratingDenial() {
        AdministratorInsightService service = mock(AdministratorInsightService.class);
        var response = new AdministratorInsightController(service).answer(null,
                json("{\"question\":\"What changed this month?\"}"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void missingAiGatewayReturnsServiceUnavailableInsteadOfNotFound() {
        AdministratorInsightService service = mock(AdministratorInsightService.class);
        when(service.answer(eq("admin"), any()))
                .thenThrow(new AdministratorInsightService.AiUnavailableException());

        var response = new AdministratorInsightController(service).answer(principal(),
                json("{\"question\":\"What changed this month?\"}"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(Map.of("code", "AI_UNAVAILABLE",
                "message", "AI insights are temporarily unavailable. Operational statistics remain available."),
                response.getBody());
    }

    private static MockHttpServletRequest json(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private static Principal principal() { return () -> "admin"; }
}
