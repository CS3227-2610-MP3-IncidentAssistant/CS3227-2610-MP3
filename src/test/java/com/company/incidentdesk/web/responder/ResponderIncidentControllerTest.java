package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentRow;
import com.company.incidentdesk.web.responder.ResponderIncidentService.Assignment;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ResponderAccessDeniedException;

class ResponderIncidentControllerTest {
    private static final IncidentRow SAFE_ROW = new IncidentRow(
            UUID.fromString("50000000-0000-0000-0000-000000000001"), "VPN unavailable", "IT", "SUBMITTED",
            Instant.parse("2026-10-20T01:00:00Z"), Instant.parse("2026-10-20T01:00:00Z"));

    @Test
    void queueIsNoStoreAndSerializesOnlyTheResponderProjection() throws Exception {
        ResponderIncidentController controller = new ResponderIncidentController(new ResponderIncidentService(
                (loginName, criteria) -> java.util.Optional.of(
                        new IncidentPage(List.of(SAFE_ROW), 0, 25, 1, 1, true))));
        Principal principal = () -> "responder";

        ResponseEntity<IncidentPage> response = controller.queue(principal,
                new ResponderIncidentController.QueueRequest("", null, null, Assignment.ALL, 0, 25));
        List<String> fieldNames = java.util.Arrays.stream(IncidentRow.class.getRecordComponents())
                .map(component -> component.getName()).toList();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(List.of(SAFE_ROW), response.getBody().incidents());
        assertFalse(fieldNames.stream().anyMatch(name -> name.toLowerCase().contains("reporter")));
        assertFalse(fieldNames.contains("anonymous"));
        assertFalse(fieldNames.contains("assigneeId"));
    }

    @Test
    void deniedAndUnavailableFailuresHaveSafeNoStoreResponses() {
        ResponderIncidentController controller = new ResponderIncidentController(new ResponderIncidentService(
                (loginName, criteria) -> java.util.Optional.empty()));

        ResponseEntity<?> denied = controller.denied();
        ResponseEntity<?> unavailable = controller.unavailable();

        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode());
        assertEquals(Map.of("code", "UNAVAILABLE", "message", "Resource unavailable"), denied.getBody());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertEquals("no-store", denied.getHeaders().getCacheControl());
        assertEquals("no-store", unavailable.getHeaders().getCacheControl());
        assertTrue(unavailable.getBody().toString().contains("temporarily unavailable"));
    }
}
