package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                new DataSourceTransactionManager(testDataSource));
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
    void disabledOrDemotedCurrentAccountCannotReadEvenWithAnExistingLoginPrincipal() {
        grant(RESPONDER, "IT");
        incident("30000000-0000-0000-0000-000000000001", "IT", "SUBMITTED", null, NOW);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", RESPONDER);

        assertThrowsAccessDenied("responder");

        jdbc.update("UPDATE accounts SET status = 'ENABLED', role = 'REPORTER' WHERE id = ?", RESPONDER);
        assertThrowsAccessDenied("responder");
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
