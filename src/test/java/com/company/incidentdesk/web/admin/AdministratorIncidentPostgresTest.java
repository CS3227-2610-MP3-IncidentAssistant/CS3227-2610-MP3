package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Isolated PostgreSQL evidence for Administrator incident detail and mutation atomicity. */
@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class AdministratorIncidentPostgresTest {
    private static final String SCHEMA = "admin_incident_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
    private static final UUID ADMIN = UUID.fromString("72000000-0000-0000-0000-000000000001");
    private static final UUID REPORTER = UUID.fromString("72000000-0000-0000-0000-000000000002");
    private static final UUID RESPONDER = UUID.fromString("72000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_RESPONDER = UUID.fromString("72000000-0000-0000-0000-000000000004");

    private static DataSource administrativeDataSource;
    private static DataSource testDataSource;
    private static JdbcTemplate jdbc;
    private static AdministratorIncidentService service;

    @BeforeAll
    static void createSchema() {
        NeonDatabaseUrl database = NeonDatabaseUrl.parse(required("INCIDENT_DESK_TEST_DATABASE_URL"));
        administrativeDataSource = PostgresTestDataSource.administrative(
                database.jdbcUrl(), database.username(), database.password());
        new JdbcTemplate(administrativeDataSource).execute("CREATE SCHEMA " + SCHEMA);
        testDataSource = PostgresTestDataSource.isolated(
                database.jdbcUrl(), database.username(), database.password(), SCHEMA);
        Flyway.configure().dataSource(testDataSource).locations("classpath:db/migration")
                .defaultSchema(SCHEMA).schemas(SCHEMA).load().migrate();
        jdbc = new JdbcTemplate(testDataSource);
        var store = new JdbcAdministratorIncidentStore(new NamedParameterJdbcTemplate(testDataSource),
                new DataSourceTransactionManager(testDataSource), Clock.fixed(NOW.plusSeconds(300), ZoneOffset.UTC),
                UUID::randomUUID);
        service = new AdministratorIncidentService(store);
    }

    @AfterAll
    static void dropSchema() {
        if (administrativeDataSource != null) {
            new JdbcTemplate(administrativeDataSource).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM incident_comments");
        jdbc.update("DELETE FROM incident_attachments");
        jdbc.update("DELETE FROM incident_resolutions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM slo_target_versions");
        jdbc.update("DELETE FROM responder_category_access");
        jdbc.update("DELETE FROM accounts");
        account(ADMIN, "administrator", "ADMINISTRATOR", "ENABLED");
        account(REPORTER, "private-reporter", "REPORTER", "ENABLED");
        account(RESPONDER, "responder-a", "RESPONDER", "ENABLED");
        account(OTHER_RESPONDER, "responder-b", "RESPONDER", "ENABLED");
        grant(RESPONDER, "IT");
        grant(OTHER_RESPONDER, "IT");
    }

    @Test
    void detailIsCompletePrivacySafeAndRechecksThePersistedAdministrator() {
        UUID incident = incident("72000000-0000-0000-0000-000000000101", "SUBMITTED", null, true);
        jdbc.update("INSERT INTO incident_comments (id, incident_id, author_id, author_role, comment_type, text, created_at) "
                + "VALUES (?, ?, ?, 'REPORTER', 'ORDINARY', 'private comment', ?)",
                UUID.randomUUID(), incident, REPORTER, Timestamp.from(NOW.plusSeconds(5)));
        jdbc.update("INSERT INTO incident_attachments (id, incident_id, media_type, size_bytes, created_at) "
                + "VALUES (?, ?, 'image/png', 10, ?)", UUID.randomUUID(), incident, Timestamp.from(NOW));
        jdbc.update("INSERT INTO slo_target_versions (id, category, average_time_to_claim_minutes, "
                + "average_time_in_progress_minutes, average_reopen_rate_percent, effective_at, changed_by) "
                + "VALUES (?, 'IT', 10, 20, 5, ?, ?)", UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(60)), ADMIN);

        var detail = service.detail("administrator", incident);

        assertEquals("Anonymous reporter", detail.reporterLabel());
        assertTrue(detail.anonymous());
        assertEquals("Anonymous reporter", detail.comments().getFirst().authorLabel());
        assertEquals("Attachment", detail.attachments().getFirst().displayName());
        assertEquals(2, detail.eligibleResponders().size());
        assertEquals("WITHIN_TARGET", detail.slo().claimState());
        assertFalse(detail.toString().contains("private-reporter"));

        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", ADMIN);
        org.junit.jupiter.api.Assertions.assertThrows(
                AdministratorIncidentService.IncidentUnavailableException.class,
                () -> service.detail("administrator", incident));
        org.junit.jupiter.api.Assertions.assertThrows(
                AdministratorIncidentService.IncidentUnavailableException.class,
                () -> service.detail("administrator", UUID.randomUUID()));
    }

    @Test
    void assignmentReassignmentAndUnassignmentUseVersionedAtomicAuditedTransitions() {
        UUID incident = incident("72000000-0000-0000-0000-000000000201", "SUBMITTED", null, false);
        Instant queueEnteredAt = value(
                "SELECT queue_entered_at FROM incidents WHERE id = ?", OffsetDateTimeMapper.INSTANCE, incident);

        assertTrue(service.assign("administrator", incident, 0, RESPONDER).changed());
        assertFalse(service.assign("administrator", incident, 0, OTHER_RESPONDER).changed());
        assertTrue(service.assign("administrator", incident, 1, OTHER_RESPONDER).changed());
        assertTrue(service.unassign("administrator", incident, 2).changed());

        assertEquals("SUBMITTED", value("SELECT status FROM incidents WHERE id = ?", String.class, incident));
        assertNull(value("SELECT assignee_id FROM incidents WHERE id = ?", UUID.class, incident));
        assertEquals(3L, value("SELECT version FROM incidents WHERE id = ?", Long.class, incident));
        assertEquals(queueEnteredAt, value("SELECT queue_entered_at FROM incidents WHERE id = ?",
                OffsetDateTimeMapper.INSTANCE, incident));
        assertEquals(1, count("INCIDENT_ASSIGNED", incident));
        assertEquals(1, count("INCIDENT_REASSIGNED", incident));
        assertEquals(1, count("INCIDENT_HANDED_OFF", incident));
    }

    @Test
    void concurrentAssignmentHasOneWinnerAndAuditFailureRollsBack() throws Exception {
        UUID incident = incident("72000000-0000-0000-0000-000000000301", "SUBMITTED", null, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> assignWhenReleased(incident, RESPONDER, ready, start));
            var second = executor.submit(() -> assignWhenReleased(incident, OTHER_RESPONDER, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, count("INCIDENT_ASSIGNED", incident));

        UUID rollback = incident("72000000-0000-0000-0000-000000000302", "SUBMITTED", null, false);
        jdbc.execute("CREATE FUNCTION reject_admin_incident_audit() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.action = 'INCIDENT_ASSIGNED' THEN RAISE EXCEPTION 'injected audit failure'; END IF; "
                + "RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER reject_admin_incident_audit BEFORE INSERT ON audit_events "
                + "FOR EACH ROW EXECUTE FUNCTION reject_admin_incident_audit()");
        try {
            org.junit.jupiter.api.Assertions.assertThrows(AdministratorIncidentService.IncidentDataAccessException.class,
                    () -> service.assign("administrator", rollback, 0, RESPONDER));
            assertEquals("SUBMITTED", value("SELECT status FROM incidents WHERE id = ?", String.class, rollback));
            assertEquals(0L, value("SELECT version FROM incidents WHERE id = ?", Long.class, rollback));
        } finally {
            jdbc.execute("DROP TRIGGER reject_admin_incident_audit ON audit_events");
            jdbc.execute("DROP FUNCTION reject_admin_incident_audit()");
        }
    }

    @Test
    void commentsAndResolutionAreIdempotentValidatedAndAudited() {
        UUID submitted = incident("72000000-0000-0000-0000-000000000401", "SUBMITTED", null, true);
        UUID commentRequest = UUID.randomUUID();
        var firstComment = service.addComment("administrator", submitted, commentRequest, "  status update  ");
        var retriedComment = service.addComment("administrator", submitted, commentRequest, "  status update  ");
        assertEquals(firstComment.commentId(), retriedComment.commentId());
        assertEquals(1, value("SELECT COUNT(*) FROM incident_comments WHERE incident_id = ?", Integer.class, submitted));
        assertEquals(1, count("INCIDENT_COMMENT_ADDED", submitted));

        UUID assigned = incident("72000000-0000-0000-0000-000000000402", "ASSIGNED", RESPONDER, false);
        UUID resolutionRequest = UUID.randomUUID();
        var resolution = service.resolve("administrator", assigned, 0, resolutionRequest, "  verified fix  ");
        var retry = service.resolve("administrator", assigned, 0, resolutionRequest, "  verified fix  ");
        assertTrue(resolution.resolved());
        assertEquals(resolution.cycleNumber(), retry.cycleNumber());
        assertEquals("RESOLVED", value("SELECT status FROM incidents WHERE id = ?", String.class, assigned));
        assertEquals(ADMIN, value("SELECT resolved_by FROM incident_resolutions WHERE incident_id = ?", UUID.class, assigned));
        assertEquals(RESPONDER, value("SELECT responder_at_resolution FROM incident_resolutions WHERE incident_id = ?",
                UUID.class, assigned));
        assertEquals(1, count("INCIDENT_RESOLVED", assigned));
        assertFalse(service.resolve("administrator", assigned, 0, UUID.randomUUID(), "again").resolved());
    }

    private static boolean assignWhenReleased(
            UUID incidentId, UUID responderId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race did not start");
        return service.assign("administrator", incidentId, 0, responderId).changed();
    }

    private UUID incident(String id, String status, UUID assignee, boolean anonymous) {
        UUID incidentId = UUID.fromString(id);
        Timestamp assignedAt = assignee == null ? null : Timestamp.from(NOW.plusSeconds(30));
        jdbc.update("""
                INSERT INTO incidents (
                    id, reporter_id, title, description, category, status, anonymous, created_at,
                    submitted_at, queue_entered_at, first_assigned_at, latest_assigned_at, assignee_id, version
                ) VALUES (?, ?, 'Incident title', 'Incident description', 'IT', ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, incidentId, REPORTER, status, anonymous, Timestamp.from(NOW), Timestamp.from(NOW),
                Timestamp.from(NOW), assignedAt, assignedAt, assignee);
        return incidentId;
    }

    private void account(UUID id, String loginName, String role, String status) {
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, ?)",
                id, loginName, role, status);
    }

    private void grant(UUID accountId, String category) {
        jdbc.update("INSERT INTO responder_category_access (account_id, category, granted_at) VALUES (?, ?, ?)",
                accountId, category, Timestamp.from(NOW));
    }

    private int count(String action, UUID incidentId) {
        return value("SELECT COUNT(*) FROM audit_events WHERE action = ? AND target_identifier = ?",
                Integer.class, action, incidentId.toString());
    }

    private <T> T value(String sql, Class<T> type, Object... values) {
        return jdbc.queryForObject(sql, type, values);
    }

    private Instant value(String sql, OffsetDateTimeMapper ignored, Object... values) {
        return jdbc.queryForObject(sql, (result, row) -> result.getObject(1, java.time.OffsetDateTime.class).toInstant(), values);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private enum OffsetDateTimeMapper { INSTANCE }
}
