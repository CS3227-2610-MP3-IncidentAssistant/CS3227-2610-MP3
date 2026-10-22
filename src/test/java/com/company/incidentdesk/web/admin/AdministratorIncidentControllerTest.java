package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdministratorIncidentControllerTest {
    private static final UUID INCIDENT = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID RESPONDER = UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID REQUEST = UUID.fromString("71000000-0000-0000-0000-000000000003");

    @Test
    void detailIsPrivacySafeNoStoreAndUsesTrustedPrincipal() {
        StubStore store = new StubStore();
        store.detail = Optional.of(detail());
        var controller = new AdministratorIncidentController(new AdministratorIncidentService(store));

        var response = controller.detail(() -> "administrator", INCIDENT);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("administrator", store.loginName);
        assertEquals("Anonymous reporter", ((AdministratorIncidentService.IncidentDetail) response.getBody()).reporterLabel());
        assertTrue(((AdministratorIncidentService.IncidentDetail) response.getBody()).anonymous());
    }

    @Test
    void mutationEndpointsReturnStableSuccessAndConflictResponses() {
        StubStore store = new StubStore();
        var controller = new AdministratorIncidentController(new AdministratorIncidentService(store));

        var assigned = controller.assign(() -> "administrator", INCIDENT,
                new AdministratorIncidentController.AssignmentRequest(4, RESPONDER));
        var unassigned = controller.unassign(() -> "administrator", INCIDENT,
                new AdministratorIncidentController.VersionRequest(5));
        var resolved = controller.resolve(() -> "administrator", INCIDENT,
                new AdministratorIncidentController.ResolutionRequest(6, REQUEST, "  resolved  "));
        var commented = controller.comment(() -> "administrator", INCIDENT,
                new AdministratorIncidentController.CommentRequest(REQUEST, "  update  "));

        assertEquals(HttpStatus.OK, assigned.getStatusCode());
        assertEquals(HttpStatus.OK, unassigned.getStatusCode());
        assertEquals(HttpStatus.OK, resolved.getStatusCode());
        assertEquals(HttpStatus.OK, commented.getStatusCode());
        assertEquals("administrator", store.loginName);
        assertEquals(RESPONDER, store.responderId);
        assertEquals("  resolved  ", store.remarks);

        store.success = false;
        assertEquals(HttpStatus.CONFLICT, controller.assign(() -> "administrator", INCIDENT,
                new AdministratorIncidentController.AssignmentRequest(4, RESPONDER)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.unassign(() -> "administrator", INCIDENT,
                new AdministratorIncidentController.VersionRequest(5)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.resolve(() -> "administrator", INCIDENT,
                new AdministratorIncidentController.ResolutionRequest(6, REQUEST, "resolved")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.comment(() -> "administrator", INCIDENT,
                new AdministratorIncidentController.CommentRequest(REQUEST, "update")).getStatusCode());
    }

    @Test
    void invalidTextAndMalformedBodiesReturnSafeNoStoreErrors() throws Exception {
        var service = new AdministratorIncidentService(new StubStore());
        assertThrows(AdministratorIncidentService.InvalidCommentException.class,
                () -> service.addComment("administrator", INCIDENT, REQUEST, " \t"));
        assertThrows(AdministratorIncidentService.InvalidResolutionException.class,
                () -> service.resolve("administrator", INCIDENT, 0, REQUEST, "x".repeat(2001)));

        var mvc = MockMvcBuilders.standaloneSetup(new AdministratorIncidentController(service)).build();
        var malformed = mvc.perform(post("/api/v1/admin/incidents/{id}/assignment", INCIDENT)
                .principal(() -> "administrator").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andReturn().getResponse();
        var blank = mvc.perform(post("/api/v1/admin/incidents/{id}/comments", INCIDENT)
                .principal(() -> "administrator").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + REQUEST + "\",\"text\":\" \"}"))
                .andReturn().getResponse();

        assertEquals(HttpStatus.BAD_REQUEST.value(), malformed.getStatus());
        assertEquals(HttpStatus.BAD_REQUEST.value(), blank.getStatus());
        assertEquals("no-store", malformed.getHeader("Cache-Control"));
        assertTrue(blank.getContentAsString().contains("INVALID_COMMENT"));
    }

    private static AdministratorIncidentService.IncidentDetail detail() {
        return new AdministratorIncidentService.IncidentDetail(INCIDENT, 4, "Title", "Description", "IT",
                "SUBMITTED", true, "Anonymous reporter", null, Instant.EPOCH, Instant.EPOCH, null,
                Instant.EPOCH, null, null,
                new AdministratorIncidentService.SloProgress(10L, 20L, 1L, null,
                        "WITHIN_TARGET", "NOT_APPLICABLE"),
                List.of(), List.of(), List.of(), List.of());
    }

    private static final class StubStore implements AdministratorIncidentStore {
        private Optional<AdministratorIncidentService.IncidentDetail> detail = Optional.empty();
        private boolean success = true;
        private String loginName;
        private UUID responderId;
        private String remarks;

        @Override public Optional<AdministratorIncidentService.IncidentDetail> findDetail(String login, UUID incident) {
            loginName = login;
            return detail;
        }

        @Override public AdministratorIncidentService.CommentResult addComment(
                String login, UUID incident, UUID request, String text) {
            loginName = login;
            return success ? new AdministratorIncidentService.CommentResult(true, REQUEST)
                    : AdministratorIncidentService.CommentResult.conflict();
        }

        @Override public AdministratorIncidentService.MutationResult assign(
                String login, UUID incident, long version, UUID responder) {
            loginName = login;
            responderId = responder;
            return success ? new AdministratorIncidentService.MutationResult(true, version + 1)
                    : AdministratorIncidentService.MutationResult.conflict();
        }

        @Override public AdministratorIncidentService.MutationResult unassign(
                String login, UUID incident, long version) {
            loginName = login;
            return success ? new AdministratorIncidentService.MutationResult(true, version + 1)
                    : AdministratorIncidentService.MutationResult.conflict();
        }

        @Override public AdministratorIncidentService.ResolutionResult resolve(
                String login, UUID incident, long version, UUID request, String resolutionRemarks) {
            loginName = login;
            remarks = resolutionRemarks;
            return success ? new AdministratorIncidentService.ResolutionResult(true, version + 1, 2)
                    : AdministratorIncidentService.ResolutionResult.conflict();
        }
    }
}
