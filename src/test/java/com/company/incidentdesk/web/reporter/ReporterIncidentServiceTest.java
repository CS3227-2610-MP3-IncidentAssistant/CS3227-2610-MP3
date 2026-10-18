package com.company.incidentdesk.web.reporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.incident.Incident;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.IncidentPage;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.ListCriteria;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.ReporterAccessDeniedException;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.Status;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.SubmissionCommand;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.SubmissionResult;

class ReporterIncidentServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-18T09:30:00Z");
    private static final AccountId REPORTER_ID = new AccountId(
            UUID.fromString("20000000-0000-0000-0000-000000000001"));
    private static final UUID INCIDENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID AUDIT_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID SUBMISSION_KEY = UUID.fromString("20000000-0000-0000-0000-000000000004");

    @Test
    void submitsNormalizedOwnedIncidentThroughCanonicalLifecycle() {
        RecordingGateway gateway = new RecordingGateway();
        ReporterIncidentService service = service(gateway);

        SubmissionResult result = service.submit("CaseSensitiveReporter",
                new SubmissionCommand("  Network outage  ", "  Router is offline.\n", "IT", true, SUBMISSION_KEY));

        assertEquals(Status.CREATED, result.status());
        assertEquals("CaseSensitiveReporter", gateway.loginName);
        assertEquals(SUBMISSION_KEY, gateway.submissionKey);
        assertEquals(INCIDENT_ID, gateway.incident.id().value());
        assertEquals(REPORTER_ID, gateway.incident.reporterId());
        assertEquals("Network outage", gateway.incident.title());
        assertEquals("  Router is offline.\n", gateway.incident.description());
        assertTrue(gateway.incident.anonymous());
        assertEquals(NOW, gateway.incident.createdAt());
        assertEquals(NOW, gateway.incident.submittedAt().orElseThrow());
        assertEquals(AUDIT_ID, gateway.auditId);
    }

    @Test
    void rejectsMissingAndOversizedFieldsBeforeLookingUpReporter() {
        RecordingGateway gateway = new RecordingGateway();
        ReporterIncidentService service = service(gateway);

        SubmissionResult result = service.submit("reporter", new SubmissionCommand(
                "x".repeat(ReporterIncidentService.MAX_TITLE_LENGTH + 1),
                " ", "UNKNOWN", false, null));

        assertEquals(Status.VALIDATION_FAILED, result.status());
        assertEquals(List.of("category", "description", "submissionKey", "title"),
                result.fieldErrors().keySet().stream().sorted().toList());
        assertEquals(0, gateway.findReporterCalls);
        assertEquals(null, gateway.incident);
    }

    @Test
    void deniesWhenTrustedLoginNoLongerNamesAnEnabledReporter() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.reporter = Optional.empty();

        SubmissionResult result = service(gateway).submit("disabled", new SubmissionCommand(
                "Valid title", "Valid description", "FACILITIES", false, SUBMISSION_KEY));

        assertEquals(Status.DENIED, result.status());
        assertEquals(null, gateway.incident);
    }

    @Test
    void deniesListWhenTrustedLoginNoLongerNamesAnEnabledReporter() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.reporter = Optional.empty();

        assertThrows(ReporterAccessDeniedException.class,
                () -> service(gateway).findOwned("disabled", new ListCriteria("", 0, 25)));
        assertEquals(0, gateway.findOwnedCalls);
    }

    @Test
    void listsOwnedIncidentsOnlyAfterConfirmingTheReporterIsEnabled() {
        RecordingGateway gateway = new RecordingGateway();

        IncidentPage page = service(gateway).findOwned(
                "CaseSensitiveReporter", new ListCriteria("router", 0, 25));

        assertEquals(0, page.totalElements());
        assertEquals(1, gateway.findReporterCalls);
        assertEquals(1, gateway.findOwnedCalls);
    }

    private static ReporterIncidentService service(RecordingGateway gateway) {
        Deque<UUID> identifiers = new ArrayDeque<>();
        identifiers.add(INCIDENT_ID);
        identifiers.add(AUDIT_ID);
        return new ReporterIncidentService(
                gateway, Clock.fixed(NOW, ZoneOffset.UTC), identifiers::removeFirst);
    }

    private static final class RecordingGateway implements ReporterIncidentGateway {
        private Optional<AccountId> reporter = Optional.of(REPORTER_ID);
        private int findReporterCalls;
        private int findOwnedCalls;
        private String loginName;
        private UUID submissionKey;
        private Incident incident;
        private UUID auditId;

        @Override
        public Optional<AccountId> findEnabledReporter(String loginName) {
            findReporterCalls++;
            return reporter;
        }

        @Override
        public IncidentPage findOwned(String loginName, ListCriteria criteria) {
            findOwnedCalls++;
            return new IncidentPage(List.of(), criteria.page(), criteria.size(), 0, 1);
        }

        @Override
        public SubmissionResult submit(
                String loginName,
                UUID submissionKey,
                Incident incident,
                UUID auditEventId) {
            this.loginName = loginName;
            this.submissionKey = submissionKey;
            this.incident = incident;
            this.auditId = auditEventId;
            return SubmissionResult.created(new ReporterIncidentService.IncidentRow(
                    incident.id().value(), incident.title(), incident.category().name(), incident.status().name(),
                    incident.anonymous(), incident.createdAt(), incident.submittedAt().orElse(null), null, 0));
        }
    }
}
