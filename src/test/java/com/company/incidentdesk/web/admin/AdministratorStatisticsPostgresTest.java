package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
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

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class AdministratorStatisticsPostgresTest {
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SCHEMA = "admin_statistics_test_" + UUID.randomUUID().toString().replace("-", "");

    private static DataSource administrativeDataSource;
    private static DataSource testDataSource;
    private static JdbcTemplate jdbc;

    private UUID administrator;
    private UUID visibleReporter;
    private UUID anonymousOnlyReporter;
    private UUID firstResponder;
    private UUID secondResponder;

    @BeforeAll
    static void createIsolatedSchema() {
        NeonDatabaseUrl database = NeonDatabaseUrl.parse(requiredEnvironment("INCIDENT_DESK_TEST_DATABASE_URL"));
        administrativeDataSource = PostgresTestDataSource.administrative(
                database.jdbcUrl(), database.username(), database.password());
        new JdbcTemplate(administrativeDataSource).execute("CREATE SCHEMA " + SCHEMA);
        testDataSource = PostgresTestDataSource.isolated(
                database.jdbcUrl(), database.username(), database.password(), SCHEMA);
        Flyway.configure().dataSource(testDataSource).locations("classpath:db/migration")
                .defaultSchema(SCHEMA).schemas(SCHEMA).load().migrate();
        jdbc = new JdbcTemplate(testDataSource);
    }

    @AfterAll
    static void dropIsolatedSchema() {
        if (administrativeDataSource != null) {
            new JdbcTemplate(administrativeDataSource).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM incident_resolutions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM accounts");

        administrator = account("admin", "ADMINISTRATOR");
        visibleReporter = account("visible-reporter", "REPORTER");
        anonymousOnlyReporter = account("anonymous-only", "REPORTER");
        firstResponder = account("first-responder", "RESPONDER");
        secondResponder = account("second-responder", "RESPONDER");

        UUID visible = incident(visibleReporter, "IT", false, "RESOLVED", null,
                BASE, BASE.plusSeconds(10));
        resolution(visible, 1, firstResponder, firstResponder,
                BASE, BASE.plusSeconds(10), BASE.plusSeconds(40), BASE.plusSeconds(50));
        resolution(visible, 2, administrator, secondResponder,
                BASE.plusSeconds(50), BASE.plusSeconds(70), BASE.plusSeconds(100), null);

        UUID anonymous = incident(anonymousOnlyReporter, "HUMAN_RELATIONS", true, "RESOLVED", null,
                BASE.plusSeconds(5), BASE.plusSeconds(10));
        resolution(anonymous, 1, firstResponder, firstResponder,
                BASE.plusSeconds(5), BASE.plusSeconds(10), BASE.plusSeconds(30), null);

        incident(visibleReporter, "FACILITIES", false, "ASSIGNED", secondResponder,
                BASE.plusSeconds(190), BASE.plusSeconds(200));
    }

    @Test
    void aggregatesPersistedCyclesAndExposesOnlyPrivacySafeFilterOptions() {
        AdministratorStatisticsStore.ReadResult result = store().read(
                "admin", filter(Set.of(), null, null, null, null));

        assertEquals(AdministratorStatisticsStore.ReadOutcome.AVAILABLE, result.outcome());
        assertEquals(2, result.summary().resolvedIncidentCount());
        assertEquals(3, result.summary().completedCycleCount());
        assertEquals(26_666L, result.summary().averageTimeInProgressMillis());
        assertEquals(4, result.summary().claimedCycleCount());
        assertEquals(11_250L, result.summary().averageTimeToClaimMillis());
        assertEquals(1, result.summary().reopenedIncidentCount());
        assertEquals(0.5, result.summary().reopenRate());
        assertEquals(1, result.summary().reopenEventCount());
        assertEquals(1, result.summary().administratorResolvedCount());
        assertEquals(2, result.responders().size());
        assertEquals(1, result.filterOptions().reporters().size());
        assertEquals(visibleReporter, result.filterOptions().reporters().getFirst().id());
    }

    @Test
    void appliesResponderReporterCategoryAndInclusivePeriodFilters() {
        AdministratorStatisticsStore.ReadResult responder = store().read(
                "admin", filter(Set.of(), secondResponder, null, null, null));
        assertEquals(AdministratorStatisticsStore.ReadOutcome.AVAILABLE, responder.outcome());
        assertEquals(1, responder.summary().completedCycleCount());
        assertEquals(1, responder.summary().administratorResolvedCount());
        assertEquals(1, responder.responders().size());
        assertEquals(secondResponder, responder.responders().getFirst().responderId());
        assertEquals(0, responder.summary().claimedCycleCount());
        assertNull(responder.summary().averageTimeToClaimMillis());

        AdministratorStatisticsStore.ReadResult reporter = store().read(
                "admin", filter(Set.of(), null, visibleReporter, null, null));
        assertEquals(1, reporter.summary().resolvedIncidentCount());
        assertEquals(2, reporter.summary().completedCycleCount());
        assertEquals(1.0, reporter.summary().reopenRate());

        AdministratorStatisticsStore.ReadResult category = store().read(
                "admin", filter(Set.of("HUMAN_RELATIONS"), null, null, null, null));
        assertEquals(1, category.summary().resolvedIncidentCount());
        assertEquals(1, category.summary().completedCycleCount());

        Instant exact = BASE.plusSeconds(100);
        AdministratorStatisticsStore.ReadResult period = store().read(
                "admin", filter(Set.of(), null, null, exact, exact));
        assertEquals(1, period.summary().completedCycleCount());
    }

    @Test
    void reauthorizesTheAdministratorBeforeEveryRead() {
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", administrator);
        assertEquals(AdministratorStatisticsStore.ReadOutcome.RESOURCE_UNAVAILABLE,
                store().read("admin", filter(Set.of(), null, null, null, null)).outcome());

        jdbc.update("UPDATE accounts SET status = 'ENABLED', role = 'REPORTER' WHERE id = ?", administrator);
        assertEquals(AdministratorStatisticsStore.ReadOutcome.RESOURCE_UNAVAILABLE,
                store().read("admin", filter(Set.of(), null, null, null, null)).outcome());
    }

    private static JdbcAdministratorStatisticsStore store() {
        return new JdbcAdministratorStatisticsStore(
                new NamedParameterJdbcTemplate(testDataSource),
                new DataSourceTransactionManager(testDataSource));
    }

    private static AdministratorStatisticsStore.StatisticsFilter filter(
            Set<String> categories,
            UUID responderId,
            UUID reporterId,
            Instant from,
            Instant through) {
        return new AdministratorStatisticsStore.StatisticsFilter(
                categories, responderId, reporterId, from, through);
    }

    private static UUID account(String loginName, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, 'ENABLED')",
                id, loginName, role);
        return id;
    }

    private static UUID incident(
            UUID reporterId,
            String category,
            boolean anonymous,
            String status,
            UUID assigneeId,
            Instant queueEnteredAt,
            Instant firstAssignedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incidents (
                    id, reporter_id, title, description, category, status, anonymous, created_at,
                    submitted_at, queue_entered_at, first_assigned_at, latest_assigned_at, assignee_id
                ) VALUES (?, ?, 'Test incident', 'Test description', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, reporterId, category, status, anonymous, Timestamp.from(queueEnteredAt),
                Timestamp.from(queueEnteredAt), Timestamp.from(queueEnteredAt), Timestamp.from(firstAssignedAt),
                Timestamp.from(firstAssignedAt), assigneeId);
        return id;
    }

    private static void resolution(
            UUID incidentId,
            int cycle,
            UUID resolvedBy,
            UUID responderAtResolution,
            Instant queueEnteredAt,
            Instant firstAssignedAt,
            Instant resolvedAt,
            Instant reopenedAt) {
        jdbc.update("""
                INSERT INTO incident_resolutions (
                    id, request_id, incident_id, cycle_number, resolved_by, resolved_at, remarks,
                    queue_entered_at, first_assigned_at, responder_at_resolution, reopened_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'Fixed', ?, ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), incidentId, cycle, resolvedBy,
                Timestamp.from(resolvedAt), Timestamp.from(queueEnteredAt), Timestamp.from(firstAssignedAt),
                responderAtResolution, reopenedAt == null ? null : Timestamp.from(reopenedAt));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
