package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentRow;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ListCriteria;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ResponderAccessDeniedException;

class ResponderIncidentServiceTest {
    @Test
    void queueUsesTrustedLoginNameAndReturnsOnlyTheGatewayProjection() {
        RecordingGateway gateway = new RecordingGateway();
        ResponderIncidentService service = new ResponderIncidentService(gateway);

        IncidentPage result = service.queue("CurrentResponder", 3, 500);

        assertEquals("CurrentResponder", gateway.loginName);
        assertEquals(criteria("", List.of(), List.of(), ResponderIncidentService.Assignment.ALL, 3, 100),
                gateway.criteria);
        assertEquals(List.of(row()), result.incidents());
    }

    @Test
    void deniesMissingDisabledOrChangedResponderIdentityBeforeReturningAQueue() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.page = Optional.empty();

        assertThrows(ResponderAccessDeniedException.class, () ->
                new ResponderIncidentService(gateway).queue("disabled-or-demoted", 0, 25));
        assertEquals("disabled-or-demoted", gateway.loginName);
    }

    @Test
    void boundsPageSizeAndKeepsOffsetArithmeticWithinLongRange() {
        assertEquals(criteria("", List.of(), List.of(), ResponderIncidentService.Assignment.ALL, 0, 25),
                criteria(null, null, null, null, -9, 0));
        assertEquals(214_748_364_700L,
                criteria("", List.of(), List.of(), ResponderIncidentService.Assignment.ALL,
                        Integer.MAX_VALUE, 100).offset());
        assertTrue(criteria("", List.of(), List.of(), ResponderIncidentService.Assignment.ALL, 0, 101).size() == 100);
    }

    @Test
    void queueCriteriaTrimSearchAndNormalizeFiltersAndRejectUnsupportedValues() {
        assertEquals(criteria("vpn", List.of("IT", "FACILITIES"), List.of("ASSIGNED"),
                ResponderIncidentService.Assignment.ASSIGNED, 0, 25),
                criteria("  vpn  ", List.of("FACILITIES", "IT", "IT"), List.of("ASSIGNED"),
                        ResponderIncidentService.Assignment.ASSIGNED, 0, 25));
        assertThrows(ResponderIncidentService.InvalidQueueFilterException.class,
                () -> criteria("x".repeat(201), List.of(), List.of(),
                        ResponderIncidentService.Assignment.ALL, 0, 25));
        assertThrows(ResponderIncidentService.InvalidQueueFilterException.class,
                () -> criteria("", List.of("UNKNOWN"), List.of(),
                        ResponderIncidentService.Assignment.ALL, 0, 25));
        assertThrows(ResponderIncidentService.InvalidQueueFilterException.class,
                () -> criteria("", List.of(), List.of("RESOLVED"),
                        ResponderIncidentService.Assignment.ALL, 0, 25));
    }

    @Test
    void claimUsesTrustedLoginNameAndIncidentIdentifier() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.claimResult = new ResponderIncidentService.ClaimResult(true);

        var result = new ResponderIncidentService(gateway).claim(
                "CurrentResponder", UUID.fromString("50000000-0000-0000-0000-000000000002"));

        assertTrue(result.claimed());
        assertEquals("CurrentResponder", gateway.claimLoginName);
        assertEquals(UUID.fromString("50000000-0000-0000-0000-000000000002"), gateway.claimIncidentId);
    }

    @Test
    void detailUsesTrustedLoginNameAndDeniesAnUnavailableIncident() {
        RecordingGateway gateway = new RecordingGateway();
        UUID incidentId = UUID.fromString("50000000-0000-0000-0000-000000000002");
        gateway.detail = Optional.of(new ResponderIncidentService.IncidentDetail(incidentId, "VPN unavailable",
                "Private details", "Anonymous reporter", "IT", "SUBMITTED", Instant.EPOCH, Instant.EPOCH));

        var detail = new ResponderIncidentService(gateway).detail("CurrentResponder", incidentId);

        assertEquals(incidentId, detail.id());
        assertEquals("CurrentResponder", gateway.claimLoginName);
        assertEquals(incidentId, gateway.claimIncidentId);
        gateway.detail = Optional.empty();
        assertThrows(ResponderAccessDeniedException.class,
                () -> new ResponderIncidentService(gateway).detail("CurrentResponder", incidentId));
    }

    private static ListCriteria criteria(String search, List<String> categories, List<String> statuses,
            ResponderIncidentService.Assignment assignment, int page, int size) {
        return new ListCriteria(search, categories, statuses, assignment, page, size);
    }

    private static IncidentRow row() {
        return new IncidentRow(UUID.fromString("50000000-0000-0000-0000-000000000001"),
                "VPN unavailable", "IT", "SUBMITTED",
                Instant.parse("2026-10-20T01:00:00Z"), Instant.parse("2026-10-20T01:00:00Z"));
    }

    private static final class RecordingGateway implements ResponderIncidentGateway {
        private Optional<IncidentPage> page = Optional.of(new IncidentPage(
                List.of(row()), 3, 100, 1, 1, true));
        private String loginName;
        private ListCriteria criteria;
        private String claimLoginName;
        private UUID claimIncidentId;
        private ResponderIncidentService.ClaimResult claimResult = new ResponderIncidentService.ClaimResult(false);
        private Optional<ResponderIncidentService.IncidentDetail> detail = Optional.empty();

        @Override
        public Optional<IncidentPage> findQueue(String loginName, ListCriteria criteria) {
            this.loginName = loginName;
            this.criteria = criteria;
            return page;
        }

        @Override
        public ResponderIncidentService.ClaimResult claim(String loginName, UUID incidentId) {
            claimLoginName = loginName;
            claimIncidentId = incidentId;
            return claimResult;
        }

        @Override
        public Optional<ResponderIncidentService.IncidentDetail> findDetail(String loginName, UUID incidentId) {
            claimLoginName = loginName;
            claimIncidentId = incidentId;
            return detail;
        }
    }
}
