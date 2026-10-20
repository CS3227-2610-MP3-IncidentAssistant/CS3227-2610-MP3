package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ListCriteria;

/** Isolated-schema PostgreSQL checks for current Responder category and assignment visibility. */
@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_URL", matches = ".+")
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
        administrativeDataSource = dataSource(database.jdbcUrl(), database.username(), database.password());
        new JdbcTemplate(administrativeDataSource).execute("CREATE SCHEMA " + SCHEMA);
        testDataSource = dataSource(withCurrentSchema(database.jdbcUrl(), SCHEMA),
                database.username(), database.password());
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

    private static DataSource dataSource(String url, String username, String password) {
        return new DriverManagerDataSource(url, username, password);
    }

    private static String withCurrentSchema(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
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
