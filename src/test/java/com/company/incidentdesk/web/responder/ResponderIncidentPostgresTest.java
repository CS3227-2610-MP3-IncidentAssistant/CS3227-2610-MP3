package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;
import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ListCriteria;

/** Isolated-schema PostgreSQL checks for current Responder category and assignment visibility. */
@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class ResponderIncidentPostgresTest {
    private static final String SCHEMA = "responder_queue_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-20T01:00:00Z");
    private static final UUID REPORTER = UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID RESPONDER = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_RESPONDER = UUID.fromString("51000000-0000-0000-0000-000000000003");

    private static DataSource administrativeDataSource;
    private static DataSource testDataSource;
    private static JdbcTemplate jdbc;
    private static ResponderIncidentService service;

    @BeforeAll
    static void createIsolatedSchema() {
        requireIsolationConfirmation();
        NeonDatabaseUrl database = NeonDatabaseUrl.parse(requiredEnvironment("INCIDENT_DESK_TEST_DATABASE_URL"));
        administrativeDataSource = PostgresTestDataSource.administrative(
                database.jdbcUrl(), database.username(), database.password());
        new JdbcTemplate(administrativeDataSource).execute("CREATE SCHEMA " + SCHEMA);
        testDataSource = PostgresTestDataSource.isolated(
                database.jdbcUrl(), database.username(), database.password(), SCHEMA);
        Flyway.configure().dataSource(testDataSource).locations("classpath:db/migration")
                .defaultSchema(SCHEMA).schemas(SCHEMA).load().migrate();
        jdbc = new JdbcTemplate(testDataSource);
        JdbcResponderIncidentGateway gateway = new JdbcResponderIncidentGateway(
                new NamedParameterJdbcTemplate(testDataSource),
                new DataSourceTransactionManager(testDataSource),
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC), UUID::randomUUID);
        service = new ResponderIncidentService(gateway);
    }

    @AfterAll
    static void dropIsolatedSchema() {
        if (administrativeDataSource != null) {
            new JdbcTemplate(administrativeDataSource).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbc.update("DELETE FROM SPRING_SESSION");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM incident_comments");
        jdbc.update("DELETE FROM incident_attachments");
        jdbc.update("DELETE FROM incident_resolutions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM responder_category_access");
        jdbc.update("DELETE FROM account_credentials");
        jdbc.update("DELETE FROM accounts");
        account(REPORTER, "reporter-private-marker", "REPORTER");
        account(RESPONDER, "responder", "RESPONDER");
        account(OTHER_RESPONDER, "other-responder", "RESPONDER");
    }

    @Test
    void queueScopesCategoriesAndAssignmentsBeforeCountingAndPaging() throws Exception {
        grant(RESPONDER, "IT");
        UUID first = incident("10000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        UUID second = incident("10000000-0000-0000-0000-000000000002", "IT", "SUBMITTED", null, NOW);
        UUID assigned = incident("10000000-0000-0000-0000-000000000003", "FACILITIES", "ASSIGNED",
                RESPONDER, NOW.plusSeconds(1));
        incident("10000000-0000-0000-0000-000000000004", "FACILITIES", "SUBMITTED", null, NOW);
        incident("10000000-0000-0000-0000-000000000005", "IT", "ASSIGNED", OTHER_RESPONDER, NOW);
        incident("10000000-0000-0000-0000-000000000006", "IT", "RESOLVED", null, NOW);

        IncidentPage firstPage = service.queue("responder", 0, 2);
        IncidentPage secondPage = service.queue("responder", 1, 2);

        assertEquals(3, firstPage.totalElements());
        assertEquals(2, firstPage.totalPages());
        assertTrue(firstPage.categoriesConfigured());
        assertEquals(List.of(first, second), firstPage.incidents().stream().map(row -> row.id()).toList());
        assertEquals(List.of(assigned), secondPage.incidents().stream().map(row -> row.id()).toList());
        List<String> fieldNames = java.util.Arrays.stream(
                ResponderIncidentService.IncidentRow.class.getRecordComponents())
                .map(component -> component.getName()).toList();
        assertFalse(fieldNames.stream().anyMatch(name -> name.toLowerCase().contains("reporter")));
        assertFalse(fieldNames.contains("anonymous"));
        assertFalse(fieldNames.contains("assigneeId"));
    }

    @Test
    void assignedIncidentRemainsVisibleAfterCategoryAccessIsRevokedButNewClaimsDisappear() {
        grant(RESPONDER, "IT");
        UUID assigned = incident("20000000-0000-0000-0000-000000000001", "IT", "ASSIGNED", RESPONDER, NOW);
        incident("20000000-0000-0000-0000-000000000002", "IT", "SUBMITTED", null, NOW);
        jdbc.update("DELETE FROM responder_category_access WHERE account_id = ? AND category = 'IT'", RESPONDER);

        IncidentPage queue = service.queue("responder", 0, 25);

        assertFalse(queue.categoriesConfigured());
        assertEquals(1, queue.totalElements());
        assertEquals(List.of(assigned), queue.incidents().stream().map(row -> row.id()).toList());
    }

    @Test
    void searchAndFiltersApplyOnlyInsideTheAuthorizedQueueBeforeCountingAndPaging() {
        grant(RESPONDER, "IT");
        UUID vpn = incident("40000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        incident("41000000-0000-0000-0000-000000000002", "IT", "SUBMITTED", null, NOW.plusSeconds(1));
        incident("42000000-0000-0000-0000-000000000003", "FACILITIES", "SUBMITTED", null, NOW.plusSeconds(2));
        jdbc.update("UPDATE incidents SET title = 'VPN outage', description = 'Private outage details are 100% fixed' WHERE id = ?", vpn);

        IncidentPage byDescription = service.queue("responder", "  PRIVATE OUTAGE ", List.of(), List.of(),
                ResponderIncidentService.Assignment.ALL, 0, 25);
        IncidentPage byIdentifier = service.queue("responder", vpn.toString().substring(0, 8), List.of("IT"),
                List.of("SUBMITTED"), ResponderIncidentService.Assignment.UNASSIGNED, 0, 25);
        IncidentPage byLiteralWildcard = service.queue("responder", "100%", List.of(), List.of(),
                ResponderIncidentService.Assignment.ALL, 0, 25);
        IncidentPage byConflictingFilters = service.queue("responder", "", List.of("IT"), List.of("ASSIGNED"),
                ResponderIncidentService.Assignment.ALL, 0, 25);

        assertEquals(1, byDescription.totalElements());
        assertEquals(List.of(vpn), byDescription.incidents().stream().map(row -> row.id()).toList());
        assertEquals(1, byIdentifier.totalElements());
        assertEquals(List.of(vpn), byIdentifier.incidents().stream().map(row -> row.id()).toList());
        assertEquals(List.of(vpn), byLiteralWildcard.incidents().stream().map(row -> row.id()).toList());
        assertEquals(0, byConflictingFilters.totalElements());
    }

    @Test
    void disabledOrDemotedCurrentAccountCannotReadEvenWithAnExistingLoginPrincipal() {
        grant(RESPONDER, "IT");
        incident("30000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", RESPONDER);

        assertThrowsAccessDenied("responder");

        jdbc.update("UPDATE accounts SET status = 'ENABLED', role = 'REPORTER' WHERE id = ?", RESPONDER);
        assertThrowsAccessDenied("responder");
    }

    @Test
    void detailRechecksCurrentAccessAndReturnsOnlyThePrivacyMinimizedCoreProjection() throws Exception {
        grant(RESPONDER, "IT");
        UUID eligible = incident("55000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        UUID outsideCategory = incident("55000000-0000-0000-0000-000000000002", "FACILITIES", "SUBMITTED", null, NOW);
        UUID assignedToOther = incident("55000000-0000-0000-0000-000000000003", "IT", "ASSIGNED", OTHER_RESPONDER, NOW);
        UUID assignedToActor = incident("55000000-0000-0000-0000-000000000004", "FACILITIES", "ASSIGNED", RESPONDER, NOW);
        jdbc.update("UPDATE incidents SET anonymous = true, title = ?, description = ? WHERE id = ?",
                "Anonymous outage", "<script>private marker</script>", eligible);

        var detail = service.detail("responder", eligible);
        assertEquals(eligible, detail.id());
        assertEquals("Anonymous outage", detail.title());
        assertEquals("<script>private marker</script>", detail.description());
        assertEquals("IT", detail.category());
        assertEquals("Anonymous reporter", detail.reporterLabel());
        assertFalse(detail.reporterLabel().contains("reporter-private-marker"));
        assertThrowsAccessDenied("responder", outsideCategory);
        assertThrowsAccessDenied("responder", assignedToOther);
        assertThrowsAccessDenied("responder", UUID.fromString("55000000-0000-0000-0000-000000000099"));

        jdbc.update("DELETE FROM responder_category_access WHERE account_id = ? AND category = 'IT'", RESPONDER);
        assertThrowsAccessDenied("responder", eligible);
        var assignedDetail = service.detail("responder", assignedToActor);
        assertEquals(assignedToActor, assignedDetail.id());
        assertEquals("reporter-private-marker", assignedDetail.reporterLabel());

        List<String> fields = java.util.Arrays.stream(ResponderIncidentService.IncidentDetail.class.getRecordComponents())
                .map(component -> component.getName()).toList();
        assertTrue(fields.contains("reporterLabel"));
        assertFalse(fields.contains("reporterId"));
        assertFalse(fields.contains("anonymous"));
        assertFalse(fields.contains("assigneeId"));
    }

    @Test
    void detailDeniesCurrentDisabledOrDemotedResponder() {
        grant(RESPONDER, "IT");
        UUID eligible = incident("56000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", RESPONDER);
        assertThrowsAccessDenied("responder", eligible);
        jdbc.update("UPDATE accounts SET status = 'ENABLED', role = 'REPORTER' WHERE id = ?", RESPONDER);
        assertThrowsAccessDenied("responder", eligible);
    }

    @Test
    void detailIncludesPrivacySafeCommentsAndGeneratedAttachmentMetadata() {
        grant(RESPONDER, "IT");
        UUID eligible = incident("57000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        jdbc.update("UPDATE incidents SET anonymous = true WHERE id = ?", eligible);
        UUID attachmentId = UUID.fromString("57000000-0000-0000-0000-000000000002");
        jdbc.update("INSERT INTO incident_attachments (id, incident_id, media_type, size_bytes, created_at) "
                + "VALUES (?, ?, 'image/png', 4, ?)", attachmentId, eligible, Timestamp.from(NOW));
        jdbc.update("INSERT INTO incident_comments (id, incident_id, author_id, author_role, comment_type, text, created_at) "
                + "VALUES (?, ?, ?, 'REPORTER', 'REOPEN_EXPLANATION', 'Anonymous follow-up', ?)",
                UUID.randomUUID(), eligible, REPORTER, Timestamp.from(NOW));
        jdbc.update("INSERT INTO incident_comments (id, incident_id, author_id, author_role, comment_type, text, created_at) "
                + "VALUES (?, ?, ?, 'RESPONDER', 'ORDINARY', 'Responder note', ?)",
                UUID.randomUUID(), eligible, RESPONDER, Timestamp.from(NOW.plusSeconds(1)));
        jdbc.update("INSERT INTO audit_events (id, occurred_at, actor_account_id, actor_role, action, target_type, "
                + "target_identifier, outcome, details) VALUES (?, ?, ?, 'REPORTER', 'INCIDENT_SUBMITTED', 'INCIDENT', ?, "
                + "'SUCCESS', '{\"status\":{\"after\":\"SUBMITTED\"}}'::jsonb)", UUID.randomUUID(),
                Timestamp.from(NOW), REPORTER, eligible.toString());

        var detail = service.detail("responder", eligible);

        assertEquals(List.of("Anonymous reporter", "responder"),
                detail.comments().stream().map(ResponderIncidentService.IncidentCommentView::authorLabel).toList());
        assertEquals(List.of("Anonymous follow-up", "Responder note"),
                detail.comments().stream().map(ResponderIncidentService.IncidentCommentView::text).toList());
        assertEquals(attachmentId, detail.attachments().getFirst().id());
        assertEquals("image/png", detail.attachments().getFirst().mediaType());
        assertEquals("Attachment", detail.attachments().getFirst().displayName());
        assertEquals("INCIDENT_SUBMITTED", detail.lifecycleHistory().getFirst().action());
        assertEquals("SUBMITTED", detail.lifecycleHistory().getFirst().toStatus());
        String serialized = detail.toString();
        assertFalse(serialized.contains(REPORTER.toString()));
        assertFalse(serialized.contains("reporter-private-marker"));
    }

    @Test
    void databaseRejectsCommentsContainingOnlyAsciiOrUnicodeWhitespace() {
        UUID eligible = incident("57100000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        for (String whitespace : List.of(" \t\n\r", "\u2003\u3000")) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO incident_comments (id, incident_id, author_id, author_role, "
                            + "comment_type, text, created_at) VALUES (?, ?, ?, 'REPORTER', 'ORDINARY', ?, ?)",
                            UUID.randomUUID(), eligible, REPORTER, whitespace, Timestamp.from(NOW)));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incident_comments WHERE incident_id = ?",
                Integer.class, eligible));
    }

    @Test
    void attachmentReadReauthorizesIncidentBeforeOpeningPrivateObject() throws Exception {
        grant(RESPONDER, "IT");
        UUID eligible = incident("57500000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        UUID assignedToOther = incident("57500000-0000-0000-0000-000000000003", "IT", "ASSIGNED", OTHER_RESPONDER, NOW);
        UUID attachmentId = UUID.fromString("57500000-0000-0000-0000-000000000002");
        UUID otherAttachmentId = UUID.fromString("57500000-0000-0000-0000-000000000004");
        jdbc.update("INSERT INTO incident_attachments (id, incident_id, media_type, size_bytes, created_at) "
                + "VALUES (?, ?, 'image/jpeg', 3, ?)", attachmentId, eligible, Timestamp.from(NOW));
        jdbc.update("INSERT INTO incident_attachments (id, incident_id, media_type, size_bytes, created_at) "
                + "VALUES (?, ?, 'image/jpeg', 3, ?)", otherAttachmentId, assignedToOther, Timestamp.from(NOW));
        var storage = org.mockito.Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class);
        var response = software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().contentLength(3L).build();
        var content = software.amazon.awssdk.http.AbortableInputStream.create(
                new java.io.ByteArrayInputStream(new byte[] { 1, 2, 3 }));
        org.mockito.Mockito.when(storage.getObject(org.mockito.ArgumentMatchers.any(
                software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenReturn(new software.amazon.awssdk.core.ResponseInputStream<>(response, content));
        var attachmentService = new ResponderAttachmentService(
                new NamedParameterJdbcTemplate(testDataSource), storage, "private-bucket");

        try (var opened = attachmentService.open("responder", eligible, attachmentId)) {
            assertArrayEquals(new byte[] { 1, 2, 3 }, opened.content().readAllBytes());
        }
        var request = org.mockito.Mockito.mockingDetails(storage).getInvocations().iterator().next().getArgument(0);
        assertEquals("private-bucket", ((software.amazon.awssdk.services.s3.model.GetObjectRequest) request).bucket());
        assertEquals(attachmentId + ".jpg",
                ((software.amazon.awssdk.services.s3.model.GetObjectRequest) request).key());

        org.mockito.Mockito.clearInvocations(storage);
        jdbc.update("DELETE FROM responder_category_access WHERE account_id = ? AND category = 'IT'", RESPONDER);
        org.junit.jupiter.api.Assertions.assertThrows(ResponderIncidentService.ResponderAccessDeniedException.class,
                () -> attachmentService.open("responder", eligible, attachmentId));
        org.mockito.Mockito.verifyNoInteractions(storage);
        org.junit.jupiter.api.Assertions.assertThrows(ResponderIncidentService.ResponderAccessDeniedException.class,
                () -> attachmentService.open("responder", assignedToOther, otherAttachmentId));
        org.mockito.Mockito.verifyNoInteractions(storage);

        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", RESPONDER);
        org.junit.jupiter.api.Assertions.assertThrows(ResponderIncidentService.ResponderAccessDeniedException.class,
                () -> attachmentService.open("responder", eligible, attachmentId));
        jdbc.update("UPDATE accounts SET status = 'ENABLED', role = 'REPORTER' WHERE id = ?", RESPONDER);
        org.junit.jupiter.api.Assertions.assertThrows(ResponderIncidentService.ResponderAccessDeniedException.class,
                () -> attachmentService.open("responder", eligible, attachmentId));
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    @Test
    void claimChecksCurrentCategoryAndLifecycleAndAtomicallyWritesAudit() {
        grant(RESPONDER, "IT");
        UUID eligible = incident("60000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        UUID outsideCategory = incident("60000000-0000-0000-0000-000000000002", "FACILITIES", "SUBMITTED", null, NOW);
        UUID alreadyAssigned = incident("60000000-0000-0000-0000-000000000003", "IT", "ASSIGNED", OTHER_RESPONDER, NOW);
        UUID resolved = incident("60000000-0000-0000-0000-000000000004", "IT", "RESOLVED", null, NOW);
        UUID draft = incident("60000000-0000-0000-0000-000000000006", "IT", "DRAFT", null, NOW);

        assertTrue(service.claim("responder", eligible).claimed());
        assertFalse(service.claim("responder", eligible).claimed());
        assertFalse(service.claim("responder", outsideCategory).claimed());
        assertFalse(service.claim("responder", alreadyAssigned).claimed());
        assertFalse(service.claim("responder", resolved).claimed());
        assertFalse(service.claim("responder", UUID.fromString("60000000-0000-0000-0000-000000000099")).claimed());
        assertFalse(service.claim("other-responder", outsideCategory).claimed());
        grant(REPORTER, "IT");
        assertFalse(service.claim("reporter", draft).claimed());
        jdbc.update("DELETE FROM responder_category_access WHERE account_id = ? AND category = 'IT'", RESPONDER);
        UUID revoked = incident("60000000-0000-0000-0000-000000000005", "IT", "SUBMITTED", null, NOW);
        assertFalse(service.claim("responder", revoked).claimed());
        grant(RESPONDER, "IT");
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", RESPONDER);
        assertFalse(service.claim("responder", revoked).claimed());
        jdbc.update("UPDATE accounts SET status = 'ENABLED', role = 'REPORTER' WHERE id = ?", RESPONDER);
        assertFalse(service.claim("responder", revoked).claimed());

        assertEquals("ASSIGNED", jdbc.queryForObject("SELECT status FROM incidents WHERE id = ?", String.class, eligible));
        assertEquals(RESPONDER, jdbc.queryForObject("SELECT assignee_id FROM incidents WHERE id = ?", UUID.class, eligible));
        assertEquals(Timestamp.from(NOW.plusSeconds(30)), jdbc.queryForObject(
                "SELECT first_assigned_at FROM incidents WHERE id = ?", Timestamp.class, eligible));
        assertEquals(Timestamp.from(NOW.plusSeconds(30)), jdbc.queryForObject(
                "SELECT latest_assigned_at FROM incidents WHERE id = ?", Timestamp.class, eligible));
        assertEquals(Timestamp.from(NOW), jdbc.queryForObject(
                "SELECT queue_entered_at FROM incidents WHERE id = ?", Timestamp.class, eligible));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'INCIDENT_CLAIMED' AND outcome = 'SUCCESS'",
                Integer.class));
        assertEquals(RESPONDER, jdbc.queryForObject(
                "SELECT actor_account_id FROM audit_events WHERE action = 'INCIDENT_CLAIMED'", UUID.class));
        assertEquals("RESPONDER", jdbc.queryForObject(
                "SELECT actor_role FROM audit_events WHERE action = 'INCIDENT_CLAIMED'", String.class));
        assertEquals("60000000-0000-0000-0000-000000000001", jdbc.queryForObject(
                "SELECT target_identifier FROM audit_events WHERE action = 'INCIDENT_CLAIMED'", String.class));
        assertFalse(jdbc.queryForObject("SELECT details::text FROM audit_events WHERE action = 'INCIDENT_CLAIMED'",
                String.class).contains("reporter-private-marker"));
    }

    @Test
    void concurrentClaimsHaveOneWinnerAndOneSuccessAudit() throws Exception {
        grant(RESPONDER, "IT");
        grant(OTHER_RESPONDER, "IT");
        UUID eligible = incident("70000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimWhenReleased("responder", eligible, ready, start));
            var second = executor.submit(() -> claimWhenReleased("other-responder", eligible, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Boolean> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
            assertEquals("ASSIGNED", jdbc.queryForObject("SELECT status FROM incidents WHERE id = ?", String.class, eligible));
            assertTrue(List.of(RESPONDER, OTHER_RESPONDER).contains(jdbc.queryForObject(
                    "SELECT assignee_id FROM incidents WHERE id = ?", UUID.class, eligible)));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_events WHERE action = 'INCIDENT_CLAIMED' AND outcome = 'SUCCESS'",
                    Integer.class));
        }
    }

    @Test
    void auditFailureRollsBackTheClaim() {
        grant(RESPONDER, "IT");
        UUID eligible = incident("80000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        jdbc.execute("""
                CREATE FUNCTION reject_claim_audit() RETURNS trigger AS $$
                BEGIN
                    IF NEW.action = 'INCIDENT_CLAIMED' THEN RAISE EXCEPTION 'injected audit failure'; END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("CREATE TRIGGER reject_claim_audit BEFORE INSERT ON audit_events "
                + "FOR EACH ROW EXECUTE FUNCTION reject_claim_audit()");
        try {
            org.junit.jupiter.api.Assertions.assertThrows(ResponderIncidentService.ResponderDataAccessException.class,
                    () -> service.claim("responder", eligible));
            assertEquals("SUBMITTED", jdbc.queryForObject("SELECT status FROM incidents WHERE id = ?", String.class, eligible));
            assertNull(jdbc.queryForObject("SELECT assignee_id FROM incidents WHERE id = ?", UUID.class, eligible));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_events WHERE action = 'INCIDENT_CLAIMED'", Integer.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_claim_audit ON audit_events");
            jdbc.execute("DROP FUNCTION reject_claim_audit()");
        }
    }

    @Test
    void resolveRequiresCurrentEnabledAssigneeAndAtomicallyRecordsForwardHistoryAndAudit() {
        grant(RESPONDER, "IT");
        UUID assigned = incident("90000000-0000-0000-0000-000000000001", "IT", "ASSIGNED", RESPONDER, NOW);
        UUID otherAssigned = incident("90000000-0000-0000-0000-000000000002", "IT", "ASSIGNED", OTHER_RESPONDER, NOW);
        UUID submitted = incident("90000000-0000-0000-0000-000000000003", "IT", "SUBMITTED", null, NOW);
        String remarks = "  fixed after the service restart  ";
        jdbc.update("DELETE FROM responder_category_access WHERE account_id = ? AND category = 'IT'", RESPONDER);

        assertTrue(service.resolve("responder", assigned, remarks).resolved());
        assertFalse(service.resolve("responder", assigned, "duplicate attempt").resolved());
        assertFalse(service.resolve("other-responder", assigned, "wrong actor").resolved());
        assertFalse(service.resolve("responder", otherAssigned, "not assigned to actor").resolved());
        assertFalse(service.resolve("responder", submitted, "not assigned").resolved());
        assertFalse(service.resolve("responder", UUID.fromString("90000000-0000-0000-0000-000000000099"),
                "missing").resolved());
        assertFalse(service.resolve("reporter", assigned, "wrong role").resolved());

        assertEquals("RESOLVED", jdbc.queryForObject("SELECT status FROM incidents WHERE id = ?", String.class, assigned));
        assertNull(jdbc.queryForObject("SELECT assignee_id FROM incidents WHERE id = ?", UUID.class, assigned));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM incidents WHERE id = ?", Long.class, assigned));
        assertEquals(remarks, jdbc.queryForObject(
                "SELECT remarks FROM incident_resolutions WHERE incident_id = ? AND cycle_number = 1", String.class, assigned));
        assertEquals(RESPONDER, jdbc.queryForObject(
                "SELECT resolved_by FROM incident_resolutions WHERE incident_id = ?", UUID.class, assigned));
        assertEquals(Timestamp.from(NOW.plusSeconds(30)), jdbc.queryForObject(
                "SELECT resolved_at FROM incident_resolutions WHERE incident_id = ?", Timestamp.class, assigned));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'INCIDENT_RESOLVED' AND outcome = 'SUCCESS'",
                Integer.class));
        assertEquals(RESPONDER, jdbc.queryForObject(
                "SELECT actor_account_id FROM audit_events WHERE action = 'INCIDENT_RESOLVED'", UUID.class));
        assertEquals("RESPONDER", jdbc.queryForObject(
                "SELECT actor_role FROM audit_events WHERE action = 'INCIDENT_RESOLVED'", String.class));
        assertEquals(Timestamp.from(NOW.plusSeconds(30)), jdbc.queryForObject(
                "SELECT occurred_at FROM audit_events WHERE action = 'INCIDENT_RESOLVED'", Timestamp.class));
        String auditDetails = jdbc.queryForObject(
                "SELECT details::text FROM audit_events WHERE action = 'INCIDENT_RESOLVED'", String.class);
        assertTrue(auditDetails.contains("resolutionId"));
        assertFalse(auditDetails.contains(remarks));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resolutions WHERE incident_id = ?", Integer.class, assigned));
    }

    @Test
    void resolutionHistoryAppendsLaterCycleWithoutReplacingPriorRemarks() {
        UUID assigned = incident("91000000-0000-0000-0000-000000000001", "IT", "ASSIGNED", RESPONDER, NOW);

        var first = service.resolve("responder", assigned, "first cycle");
        jdbc.update("UPDATE incidents SET status = 'ASSIGNED', assignee_id = ?, version = version + 1 WHERE id = ?",
                RESPONDER, assigned);
        var second = service.resolve("responder", assigned, "second cycle");

        assertTrue(first.resolved());
        assertEquals(1, first.cycleNumber());
        assertTrue(second.resolved());
        assertEquals(2, second.cycleNumber());
        assertEquals(List.of("first cycle", "second cycle"), jdbc.query(
                "SELECT remarks FROM incident_resolutions WHERE incident_id = ? ORDER BY cycle_number", (result, row) ->
                        result.getString("remarks"), assigned));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'INCIDENT_RESOLVED'", Integer.class));
        jdbc.update("UPDATE incidents SET status = 'ASSIGNED', assignee_id = ? WHERE id = ?", RESPONDER, assigned);
        assertEquals(List.of("first cycle", "second cycle"), service.detail("responder", assigned)
                .resolutionHistory().stream().map(ResponderIncidentService.ResolutionHistoryEntry::remarks).toList());
    }

    @Test
    void retryAfterLostResolutionResponseReturnsExistingCycleWithoutDuplicatingHistoryOrAudit() {
        UUID assigned = incident("91500000-0000-0000-0000-000000000001", "IT", "ASSIGNED", RESPONDER, NOW);
        UUID secondAssigned = incident("91500000-0000-0000-0000-000000000002", "IT", "ASSIGNED", RESPONDER, NOW);
        UUID requestId = UUID.fromString("91500000-0000-0000-0000-000000000099");

        var first = service.resolve("responder", assigned, requestId, "  resolved safely  ");
        var retry = service.resolve("responder", assigned, requestId, "  resolved safely  ");
        var changedBodyRetry = service.resolve("responder", assigned, requestId, "different remarks");
        var changedIncidentRetry = service.resolve("responder", secondAssigned, requestId, "  resolved safely  ");
        var changedActorRetry = service.resolve("other-responder", assigned, requestId, "  resolved safely  ");

        assertTrue(first.resolved());
        assertTrue(retry.resolved());
        assertEquals(first.cycleNumber(), retry.cycleNumber());
        assertFalse(changedBodyRetry.resolved());
        assertFalse(changedIncidentRetry.resolved());
        assertFalse(changedActorRetry.resolved());
        assertEquals("ASSIGNED", jdbc.queryForObject(
                "SELECT status FROM incidents WHERE id = ?", String.class, secondAssigned));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resolutions WHERE incident_id = ?", Integer.class, assigned));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'INCIDENT_RESOLVED'", Integer.class));
    }

    @Test
    void concurrentRequestsReusingOneIdForDifferentIncidentsHaveOneSafeConflict() throws Exception {
        UUID firstIncident = incident("91900000-0000-0000-0000-000000000001", "IT", "ASSIGNED", RESPONDER, NOW);
        UUID secondIncident = incident("91900000-0000-0000-0000-000000000002", "IT", "ASSIGNED", RESPONDER,
                NOW.plusSeconds(1));
        UUID requestId = UUID.fromString("91900000-0000-0000-0000-000000000099");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> resolveWhenReleased(
                    firstIncident, requestId, "same key", ready, start));
            var second = executor.submit(() -> resolveWhenReleased(
                    secondIncident, requestId, "same key", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Boolean> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
            assertEquals(1, outcomes.stream().filter(outcome -> !outcome).count());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM incident_resolutions WHERE request_id = ?",
                    Integer.class, requestId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_events WHERE action = 'INCIDENT_RESOLVED'", Integer.class));
            assertEquals(1, List.of(firstIncident, secondIncident).stream()
                    .filter(id -> "RESOLVED".equals(jdbc.queryForObject(
                            "SELECT status FROM incidents WHERE id = ?", String.class, id))).count());
        }
    }

    @Test
    void databaseRejectsRemarksContainingOnlyJavaWhitespaceCharacters() {
        UUID assigned = incident("91600000-0000-0000-0000-000000000001", "IT", "ASSIGNED", RESPONDER, NOW);
        for (String remarks : List.of(" \t\n\r", "\u2003\u3000")) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("""
                            INSERT INTO incident_resolutions (
                                id, request_id, incident_id, cycle_number, resolved_by, resolved_at, remarks
                            ) VALUES (?, ?, ?, 1, ?, ?, ?)
                            """, UUID.randomUUID(), UUID.randomUUID(), assigned, RESPONDER,
                            Timestamp.from(NOW), remarks));
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resolutions WHERE incident_id = ?", Integer.class, assigned));
    }

    @Test
    void disabledOrDemotedAssignedResponderCannotResolve() {
        UUID assigned = incident("91700000-0000-0000-0000-000000000001", "IT", "ASSIGNED", RESPONDER, NOW);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", RESPONDER);
        assertFalse(service.resolve("responder", assigned, "disabled actor").resolved());
        jdbc.update("UPDATE accounts SET status = 'ENABLED', role = 'REPORTER' WHERE id = ?", RESPONDER);
        assertFalse(service.resolve("responder", assigned, "demoted actor").resolved());
        assertEquals("ASSIGNED", jdbc.queryForObject(
                "SELECT status FROM incidents WHERE id = ?", String.class, assigned));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resolutions WHERE incident_id = ?", Integer.class, assigned));
    }

    @Test
    void concurrentResolutionRequestsHaveOneWinnerAndOneHistoryAndAudit() throws Exception {
        UUID assigned = incident("92000000-0000-0000-0000-000000000001", "IT", "ASSIGNED", RESPONDER, NOW);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> resolveWhenReleased(assigned, "first request", ready, start));
            var second = executor.submit(() -> resolveWhenReleased(assigned, "second request", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Boolean> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
            assertEquals("RESOLVED", jdbc.queryForObject("SELECT status FROM incidents WHERE id = ?", String.class, assigned));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incident_resolutions WHERE incident_id = ?", Integer.class, assigned));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_events WHERE action = 'INCIDENT_RESOLVED'", Integer.class));
        }
    }

    @Test
    void resolutionHistoryAndAuditFailuresRollBackTheIncidentMutation() {
        UUID historyFailure = incident("93000000-0000-0000-0000-000000000001", "IT", "ASSIGNED", RESPONDER, NOW);
        jdbc.execute("CREATE FUNCTION reject_resolution_history() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'injected history failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER reject_resolution_history BEFORE INSERT ON incident_resolutions "
                + "FOR EACH ROW EXECUTE FUNCTION reject_resolution_history()");
        try {
            org.junit.jupiter.api.Assertions.assertThrows(ResponderIncidentService.ResponderDataAccessException.class,
                    () -> service.resolve("responder", historyFailure, "will roll back"));
            assertResolutionRolledBack(historyFailure);
        } finally {
            jdbc.execute("DROP TRIGGER reject_resolution_history ON incident_resolutions");
            jdbc.execute("DROP FUNCTION reject_resolution_history()");
        }

        UUID auditFailure = incident("93000000-0000-0000-0000-000000000002", "IT", "ASSIGNED", RESPONDER, NOW);
        jdbc.execute("CREATE FUNCTION reject_resolution_audit() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.action = 'INCIDENT_RESOLVED' THEN RAISE EXCEPTION 'injected audit failure'; END IF; "
                + "RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER reject_resolution_audit BEFORE INSERT ON audit_events "
                + "FOR EACH ROW EXECUTE FUNCTION reject_resolution_audit()");
        try {
            org.junit.jupiter.api.Assertions.assertThrows(ResponderIncidentService.ResponderDataAccessException.class,
                    () -> service.resolve("responder", auditFailure, "will roll back"));
            assertResolutionRolledBack(auditFailure);
        } finally {
            jdbc.execute("DROP TRIGGER reject_resolution_audit ON audit_events");
            jdbc.execute("DROP FUNCTION reject_resolution_audit()");
        }
    }

    private void assertResolutionRolledBack(UUID incidentId) {
        assertEquals("ASSIGNED", jdbc.queryForObject("SELECT status FROM incidents WHERE id = ?", String.class, incidentId));
        assertEquals(RESPONDER, jdbc.queryForObject("SELECT assignee_id FROM incidents WHERE id = ?", UUID.class, incidentId));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM incidents WHERE id = ?", Long.class, incidentId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resolutions WHERE incident_id = ?", Integer.class, incidentId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'INCIDENT_RESOLVED' AND target_identifier = ?",
                Integer.class, incidentId.toString()));
    }

    private static boolean resolveWhenReleased(
            UUID incidentId, String remarks, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("resolution race did not start");
        return service.resolve("responder", incidentId, remarks).resolved();
    }

    private static boolean resolveWhenReleased(
            UUID incidentId, UUID requestId, String remarks, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("resolution race did not start");
        return service.resolve("responder", incidentId, requestId, remarks).resolved();
    }

    private static boolean claimWhenReleased(
            String loginName, UUID incidentId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("claim race did not start");
        return service.claim(loginName, incidentId).claimed();
    }

    private static void assertThrowsAccessDenied(String loginName) {
        org.junit.jupiter.api.Assertions.assertThrows(
                ResponderIncidentService.ResponderAccessDeniedException.class,
                () -> service.queue(loginName, 0, 25));
    }

    private static void assertThrowsAccessDenied(String loginName, UUID incidentId) {
        org.junit.jupiter.api.Assertions.assertThrows(
                ResponderIncidentService.ResponderAccessDeniedException.class,
                () -> service.detail(loginName, incidentId));
    }

    private UUID incident(String id, String category, String status, UUID assignee, Instant queueEnteredAt) {
        UUID incidentId = UUID.fromString(id);
        jdbc.update("""
                INSERT INTO incidents (
                    id, reporter_id, title, description, category, status, anonymous,
                    created_at, submitted_at, queue_entered_at, assignee_id, version
                ) VALUES (?, ?, ?, ?, ?, ?, false, ?, ?, ?, ?, 0)
                """, incidentId, REPORTER, "Incident " + id.substring(id.length() - 1), "private incident body",
                category, status, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(queueEnteredAt), assignee);
        return incidentId;
    }

    private void account(UUID id, String loginName, String role) {
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, 'ENABLED')",
                id, loginName, role);
    }

    private void grant(UUID accountId, String category) {
        jdbc.update("INSERT INTO responder_category_access (account_id, category, granted_at) VALUES (?, ?, ?)",
                accountId, category, java.sql.Timestamp.from(NOW));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void requireIsolationConfirmation() {
        if (!"true".equalsIgnoreCase(requiredEnvironment("INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED"))) {
            throw new IllegalStateException("INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED must be true");
        }
    }
}
