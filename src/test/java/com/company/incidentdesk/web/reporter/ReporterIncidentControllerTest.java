package com.company.incidentdesk.web.reporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ReporterIncidentControllerTest {
    @Test
    void deniedAccessUsesTheSafeUnavailableResponse() {
        ReporterIncidentController controller = new ReporterIncidentController(
                mock(ReporterIncidentService.class));

        ResponseEntity<?> response = controller.denied();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(Map.of("code", "UNAVAILABLE", "message", "Resource unavailable"), response.getBody());
    }
}
