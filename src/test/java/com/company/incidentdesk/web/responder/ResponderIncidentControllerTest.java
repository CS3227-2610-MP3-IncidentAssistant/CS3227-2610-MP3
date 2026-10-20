package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentRow;
import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentDetail;
import com.company.incidentdesk.web.responder.ResponderIncidentService.Assignment;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ListCriteria;
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
    void detailIsNoStoreAndContainsOnlyCoreIncidentFields() throws Exception {
        StubGateway gateway = new StubGateway();
        gateway.detail = Optional.of(new IncidentDetail(SAFE_ROW.id(), SAFE_ROW.title(), "Untrusted <script>text</script>",
                "Anonymous reporter", "IT", "SUBMITTED", SAFE_ROW.submittedAt(), SAFE_ROW.queueEnteredAt()));
        ResponderIncidentController controller = new ResponderIncidentController(new ResponderIncidentService(gateway));

        ResponseEntity<?> response = controller.detail(() -> "responder", SAFE_ROW.id());
        List<String> fieldNames = java.util.Arrays.stream(IncidentDetail.class.getRecordComponents())
                .map(component -> component.getName()).toList();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(fieldNames.contains("reporterLabel"));
        assertFalse(fieldNames.contains("reporterId"));
        assertFalse(fieldNames.contains("anonymous"));
        assertFalse(fieldNames.contains("assigneeId"));
        assertTrue(response.getBody().toString().contains("<script>"));
        assertEquals("responder", gateway.detailLoginName);
        assertEquals(SAFE_ROW.id(), gateway.detailIncidentId);
    }

    @Test
    void detailMissingAndForbiddenUseTheSameSafeNoStoreHttpResponse() throws Exception {
        StubGateway gateway = new StubGateway();
        gateway.detail = Optional.of(new IncidentDetail(SAFE_ROW.id(), SAFE_ROW.title(), "Description",
                "Anonymous reporter", "IT", "SUBMITTED", SAFE_ROW.submittedAt(), SAFE_ROW.queueEnteredAt()));
        var mockMvc = MockMvcBuilders.standaloneSetup(
                new ResponderIncidentController(new ResponderIncidentService(gateway))).build();

        MvcResult allowed = mockMvc.perform(get("/api/v1/responder/incidents/50000000-0000-0000-0000-000000000001")
                .principal(() -> "responder")).andReturn();
        assertEquals(HttpStatus.OK.value(), allowed.getResponse().getStatus());
        assertEquals("no-store", allowed.getResponse().getHeader("Cache-Control"));
        assertTrue(allowed.getResponse().getContentAsString().contains("Anonymous reporter"));

        MvcResult missing = mockMvc.perform(get("/api/v1/responder/incidents/50000000-0000-0000-0000-000000000002")
                .principal(() -> "responder")).andReturn();
        MvcResult forbidden = mockMvc.perform(get("/api/v1/responder/incidents/50000000-0000-0000-0000-000000000003")
                .principal(() -> "responder")).andReturn();

        assertEquals(HttpStatus.NOT_FOUND.value(), missing.getResponse().getStatus());
        assertEquals(missing.getResponse().getStatus(), forbidden.getResponse().getStatus());
        assertEquals(missing.getResponse().getContentAsString(), forbidden.getResponse().getContentAsString());
        assertEquals("no-store", missing.getResponse().getHeader("Cache-Control"));
        assertEquals(missing.getResponse().getHeader("Cache-Control"), forbidden.getResponse().getHeader("Cache-Control"));
        assertTrue(missing.getResponse().getContentAsString().contains("Resource unavailable"));
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
        private Optional<IncidentDetail> detail = Optional.empty();
        private String detailLoginName;
        private UUID detailIncidentId;

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

        @Override
        public Optional<IncidentDetail> findDetail(String loginName, UUID incidentId) {
            detailLoginName = loginName;
            detailIncidentId = incidentId;
            return detailIncidentId.equals(SAFE_ROW.id()) ? detail : Optional.empty();
        }
    }
}
