package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentRow;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ListCriteria;
import com.company.incidentdesk.web.responder.ResponderIncidentService.Assignment;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ResponderAccessDeniedException;

class ResponderIncidentControllerTest {
    private static final IncidentRow SAFE_ROW = new IncidentRow(
            UUID.fromString("50000000-0000-0000-0000-000000000001"), "VPN unavailable", "IT", "SUBMITTED",
            Instant.parse("2026-10-20T01:00:00Z"), Instant.parse("2026-10-20T01:00:00Z"));

    @Test
    void queueIsNoStoreAndSerializesOnlyTheResponderProjection() throws Exception {
        StubGateway gateway = new StubGateway();
        gateway.page = Optional.of(new IncidentPage(List.of(SAFE_ROW), 0, 25, 1, 1, true));
        ResponderIncidentController controller = new ResponderIncidentController(new ResponderIncidentService(gateway));
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
        ResponderIncidentController controller = new ResponderIncidentController(new ResponderIncidentService(new StubGateway()));

        ResponseEntity<?> denied = controller.denied();
        ResponseEntity<?> unavailable = controller.unavailable();

        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode());
        assertEquals(Map.of("code", "UNAVAILABLE", "message", "Resource unavailable"), denied.getBody());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertEquals("no-store", denied.getHeaders().getCacheControl());
        assertEquals("no-store", unavailable.getHeaders().getCacheControl());
        assertTrue(unavailable.getBody().toString().contains("temporarily unavailable"));
    }

    @Test
    void claimReturnsNoStoreSuccessOrSafeConflict() {
        var gateway = new StubGateway();
        ResponderIncidentController controller = new ResponderIncidentController(new ResponderIncidentService(gateway));
        Principal principal = () -> "responder";
        UUID incidentId = SAFE_ROW.id();

        ResponseEntity<?> success = controller.claim(principal, incidentId);
        gateway.claimed = false;
        ResponseEntity<?> conflict = controller.claim(principal, incidentId);

        assertEquals(HttpStatus.OK, success.getStatusCode());
        assertEquals("no-store", success.getHeaders().getCacheControl());
        assertEquals(Map.of("claimed", true), success.getBody());
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("no-store", conflict.getHeaders().getCacheControl());
        assertEquals(Map.of("code", "CLAIM_CONFLICT",
                "message", "This incident is no longer available to claim."), conflict.getBody());
        assertEquals("responder", gateway.loginName);
        assertEquals(incidentId, gateway.incidentId);
    }

    private static final class StubGateway implements ResponderIncidentGateway {
        private boolean claimed = true;
        private String loginName;
        private UUID incidentId;
        private Optional<IncidentPage> page = Optional.empty();

        @Override
        public Optional<IncidentPage> findQueue(String loginName, ListCriteria criteria) {
            return page;
        }

        @Override
        public ResponderIncidentService.ClaimResult claim(String loginName, UUID incidentId) {
            this.loginName = loginName;
            this.incidentId = incidentId;
            return new ResponderIncidentService.ClaimResult(claimed);
        }
    }
}
