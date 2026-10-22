package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPeriodResolver;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlanParser;
import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class AdministratorInsightPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");
    private static final String SCHEMA = "admin_insight_test_" + UUID.randomUUID().toString().replace("-", "");
    private static DataSource administrativeDataSource;
    private static DataSource testDataSource;
    private static JdbcTemplate jdbc;

    private UUID administrator;
    private UUID responder;
    private UUID responderWithoutData;

    @BeforeAll
    static void createSchema() {
        NeonDatabaseUrl database = NeonDatabaseUrl.parse(required("INCIDENT_DESK_TEST_DATABASE_URL"));
        administrativeDataSource = PostgresTestDataSource.administrative(database.jdbcUrl(), database.username(), database.password());
        new JdbcTemplate(administrativeDataSource).execute("CREATE SCHEMA " + SCHEMA);
        testDataSource = PostgresTestDataSource.isolated(database.jdbcUrl(), database.username(), database.password(), SCHEMA);
        Flyway.configure().dataSource(testDataSource).locations("classpath:db/migration")
                .defaultSchema(SCHEMA).schemas(SCHEMA).load().migrate();
        jdbc = new JdbcTemplate(testDataSource);
    }

    @AfterAll
    static void dropSchema() {
        if (administrativeDataSource != null) {
            new JdbcTemplate(administrativeDataSource).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM incident_resolutions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM slo_target_versions");
        jdbc.update("DELETE FROM accounts");
        administrator = account("admin", "ADMINISTRATOR");
        responder = account("responder", "RESPONDER");
        responderWithoutData = account("responder-without-data", "RESPONDER");
        UUID reporter = account("reporter", "REPORTER");
        jdbc.update("""
                INSERT INTO slo_target_versions(id,category,average_time_to_claim_minutes,
                    average_time_in_progress_minutes,average_reopen_rate_percent,effective_at,changed_by)
                VALUES (?,'IT',30,60,20,?,?)
                """, UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(60L * 86400)), administrator);
        Instant queued = NOW.minusSeconds(10L * 86400);
        UUID incident = incident(reporter, "IT", "RESOLVED", queued, null);
        jdbc.update("""
                INSERT INTO incident_resolutions(id,request_id,incident_id,cycle_number,resolved_by,resolved_at,
                    remarks,queue_entered_at,first_assigned_at,responder_at_resolution,reopened_at)
                VALUES (?,?,?,1,?,?,'done',?,?,?,?)
                """, UUID.randomUUID(), UUID.randomUUID(), incident, responder,
                Timestamp.from(queued.plusSeconds(7200)), Timestamp.from(queued), Timestamp.from(queued.plusSeconds(600)),
                responder, Timestamp.from(queued.plusSeconds(10800)));
        incident(reporter, "FACILITIES", "SUBMITTED", NOW.minusSeconds(3L * 86400), null);
    }

    @Test
    void executesRankBacklogTrendAndSloHandlersFromPersistedAggregates() {
        JdbcAdministratorInsightAnalytics analytics = analytics();
        var rank = analytics.execute("admin", query("""
                {"outcome":"QUERY","queryType":"RANK_RESPONDERS","metric":"AVERAGE_TIME_IN_PROGRESS",
                 "period":"ROLLING_30_DAYS","direction":"DESCENDING","limit":10}
                """, Set.of("IT"), Set.of()));
        assertEquals(1, rank.values().size());
        assertEquals("responder", rank.values().getFirst().label());
        assertEquals(1, rank.values().getFirst().sampleSize());

        var backlog = analytics.execute("admin", query("""
                {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT","metric":"UNASSIGNED_BACKLOG_COUNT",
                 "period":"ROLLING_30_DAYS"}
                """, Set.of("FACILITIES"), Set.of()));
        assertEquals(2, backlog.values().size());
        assertTrue(backlog.values().stream().anyMatch(value -> value.numericValue() == 1));

        var trend = analytics.execute("admin", query("""
                {"outcome":"QUERY","queryType":"CATEGORY_TREND","metric":"INCIDENT_COUNT",
                 "period":"ROLLING_30_DAYS"}
                """, Set.of("IT"), Set.of()));
        assertEquals(30, trend.values().size());
        assertEquals(1d, trend.values().stream().mapToDouble(AdministratorInsightAnalytics.Value::numericValue).sum());

        var slo = analytics.execute("admin", query("""
                {"outcome":"QUERY","queryType":"SLO_TARGET_COMPARISON","metric":"AVERAGE_TIME_IN_PROGRESS",
                 "period":"ROLLING_30_DAYS"}
                """, Set.of("IT"), Set.of()));
        assertEquals(1, slo.values().size());
        assertEquals(3_000_000d, slo.values().getFirst().numericValue());
    }

    @Test
    void selectedComparisonRetainsRespondersWithoutQualifyingCycles() {
        var comparison = analytics().execute("admin", query("""
                {"outcome":"QUERY","queryType":"COMPARE_SELECTED_RESPONDERS",
                 "metric":"AVERAGE_TIME_IN_PROGRESS","period":"ROLLING_30_DAYS"}
                """, Set.of("IT"), Set.of(responder, responderWithoutData)));

        assertEquals(2, comparison.values().size());
        assertEquals("responder", comparison.values().getFirst().label());
        assertEquals(1, comparison.values().getFirst().sampleSize());
        assertEquals("responder-without-data", comparison.values().getLast().label());
        assertEquals(0, comparison.values().getLast().sampleSize());
        assertNull(comparison.values().getLast().numericValue());
    }

    @Test
    void reauthorizesImmediatelyBeforeAnalytics() {
        JdbcAdministratorInsightAnalytics analytics = analytics();
        jdbc.update("UPDATE accounts SET status='DISABLED' WHERE id=?", administrator);
        assertThrows(JdbcAdministratorInsightAnalytics.AnalyticsUnavailableException.class,
                () -> analytics.execute("admin", query("""
                        {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT","metric":"UNASSIGNED_BACKLOG_COUNT",
                         "period":"ROLLING_30_DAYS"}
                        """, Set.of(), Set.of())));
    }

    private static AdministratorInsightAnalytics.Query query(String json, Set<String> categories, Set<UUID> responders) {
        var plan = new AdministratorInsightPlanParser().parse(json);
        var periods = new AdministratorInsightPeriodResolver(Clock.fixed(NOW, ZoneOffset.UTC)).resolve(plan);
        return new AdministratorInsightAnalytics.Query(plan, periods, categories, responders);
    }

    private static JdbcAdministratorInsightAnalytics analytics() {
        var manager = new DataSourceTransactionManager(testDataSource);
        var named = new NamedParameterJdbcTemplate(testDataSource);
        return new JdbcAdministratorInsightAnalytics(named,
                new JdbcAdministratorStatisticsStore(named, manager), manager);
    }

    private static UUID account(String login, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,login_name,role,status) VALUES (?,?,?,'ENABLED')", id, login, role);
        return id;
    }

    private static UUID incident(UUID reporter, String category, String status, Instant queued, UUID assignee) {
        UUID id = UUID.randomUUID();
        Instant assigned = status.equals("SUBMITTED") ? null : queued.plusSeconds(600);
        jdbc.update("""
                INSERT INTO incidents(id,reporter_id,title,description,category,status,anonymous,created_at,
                    submitted_at,queue_entered_at,first_assigned_at,latest_assigned_at,assignee_id)
                VALUES (?,?,'title','description',?,?,false,?,?,?,?,?,?)
                """, id, reporter, category, status, Timestamp.from(queued), Timestamp.from(queued),
                Timestamp.from(queued), assigned == null ? null : Timestamp.from(assigned),
                assigned == null ? null : Timestamp.from(assigned), assignee);
        return id;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
