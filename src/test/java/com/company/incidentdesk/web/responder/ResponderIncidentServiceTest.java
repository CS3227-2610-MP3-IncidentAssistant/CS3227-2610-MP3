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
        assertEquals(new ListCriteria(3, 100), gateway.criteria);
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
        assertEquals(new ListCriteria(0, 25), new ListCriteria(-9, 0));
        assertEquals(214_748_364_700L, new ListCriteria(Integer.MAX_VALUE, 100).offset());
        assertTrue(new ListCriteria(0, 101).size() == 100);
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

        @Override
        public Optional<IncidentPage> findQueue(String loginName, ListCriteria criteria) {
            this.loginName = loginName;
            this.criteria = criteria;
            return page;
        }
    }
}
