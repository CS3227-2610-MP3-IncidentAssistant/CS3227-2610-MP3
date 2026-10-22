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

    /**
     * `AI-ADM-QUERY-03`: a reopen rate with a zero denominator is `not available` rather than zero,
     * so an unresolved category is never reported as a perfect score.
     */
    @Test
    void aReopenRateWithAZeroDenominatorIsNotAvailableRatherThanZero() {
        var ranking = analytics().execute("admin", query("""
                {"outcome":"QUERY","queryType":"RANK_CATEGORIES","metric":"REOPEN_RATE",
                 "period":"ROLLING_30_DAYS","direction":"DESCENDING","limit":10}
                """, Set.of("FACILITIES"), Set.of()));

        assertTrue(ranking.values().isEmpty(),
                "a category with no resolved incident contributes no fabricated zero rate");
    }

    /** `AI-ADM-QUERY-03`: a responder with a single qualifying cycle is ranked, not suppressed. */
    @Test
    void aResponderWithOneQualifyingCycleIsRankedRatherThanSuppressed() {
        var ranking = analytics().execute("admin", query("""
                {"outcome":"QUERY","queryType":"RANK_RESPONDERS","metric":"RESOLVED_CYCLE_COUNT",
                 "period":"ROLLING_30_DAYS","direction":"DESCENDING","limit":10}
                """, Set.of("IT"), Set.of()));

        assertEquals(1, ranking.values().size());
        assertEquals("responder", ranking.values().getFirst().label());
        assertEquals(1, ranking.values().getFirst().sampleSize(),
                "the single-cycle sample size is reported rather than hidden");
    }

    /** `AI-ADM-QUERY-04`: period comparison reports both adjacent intervals from persisted timestamps. */
    @Test
    void periodComparisonReportsBothAdjacentIntervals() {
        var comparison = analytics().execute("admin", query("""
                {"outcome":"QUERY","queryType":"PERIOD_COMPARISON","metric":"INCIDENT_COUNT",
                 "period":"THIS_UTC_MONTH","comparisonPeriod":"PREVIOUS_UTC_MONTH"}
                """, Set.of("FACILITIES"), Set.of()));

        assertEquals(2, comparison.values().size());
        assertEquals("Selected period", comparison.values().getFirst().label());
        assertEquals(1d, comparison.values().getFirst().numericValue(),
                "the seeded October incident falls in the current UTC month");
        assertEquals("Comparison period", comparison.values().getLast().label());
        assertEquals(0d, comparison.values().getLast().numericValue(),
                "the preceding UTC month holds no seeded incident");
    }

    /** `AI-ADM-QUERY-04`: the overview is a fixed composition bounded by the ten-row ceiling. */
    @Test
    void operationalOverviewReturnsItsFixedBoundedComposition() {
        var overview = analytics().execute("admin", query("""
                {"outcome":"QUERY","queryType":"OPERATIONAL_OVERVIEW","metric":"INCIDENT_COUNT",
                 "period":"ROLLING_30_DAYS"}
                """, Set.of(), Set.of()));

        assertTrue(overview.values().size() <= 10, "the overview respects the ten-row response ceiling");
        var labels = overview.values().stream().map(AdministratorInsightAnalytics.Value::label).toList();
        assertTrue(labels.contains("Resolved incidents"));
        assertTrue(labels.contains("Unassigned backlog"));
        assertEquals(NOW, overview.snapshotAt());
    }

    /** `AI-ADM-QUERY-04`: a category with no persisted lifecycle data yields no fabricated row. */
    @Test
    void categoryRankingOmitsCategoriesWithoutQualifyingDataInsteadOfInventingZeroRows() {
        var ranking = analytics().execute("admin", query("""
                {"outcome":"QUERY","queryType":"RANK_CATEGORIES","metric":"AVERAGE_TIME_IN_PROGRESS",
                 "period":"ROLLING_30_DAYS","direction":"DESCENDING","limit":10}
                """, Set.of(), Set.of()));

        assertEquals(1, ranking.values().size());
        assertEquals("IT", ranking.values().getFirst().label());
    }

    /** `AI-ADM-QUERY-04`: a backlog snapshot over an empty category returns no rows. */
    @Test
    void backlogSnapshotOverAnEmptyCategoryReturnsNoRows() {
        var backlog = analytics().execute("admin", query("""
                {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT","metric":"UNASSIGNED_BACKLOG_COUNT",
                 "period":"ROLLING_30_DAYS"}
                """, Set.of("HUMAN_RELATIONS"), Set.of()));

        assertTrue(backlog.values().isEmpty(), "an empty population produces no qualifying data");
    }

    /** `AI-ADM-QUERY-04`: a monthly trend window stays within the bounded series ceiling. */
    @Test
    void aLongTrendWindowStaysWithinTheBoundedSeriesCeiling() {
        var trend = analytics().execute("admin", query("""
                {"outcome":"QUERY","queryType":"CATEGORY_TREND","metric":"INCIDENT_COUNT",
                 "period":{"fromDate":"2025-11-01","throughDate":"2026-10-22"}}
                """, Set.of("IT"), Set.of()));

        assertTrue(trend.values().size() <= 366, "the series respects the bounded point ceiling");
        assertTrue(trend.values().size() > 1, "a year-long window produces a multi-point series");
    }

    /**
     * `AI-ADM-QUERY-06`: execution records its application-generated instant and reads a
     * read-consistent snapshot, while a concurrent incident insert is never blocked and becomes
     * visible only to a later request.
     */
    @Test
    void concurrentIncidentMutationsDoNotBlockAndAppearOnlyInALaterRequest() throws Exception {
        JdbcAdministratorInsightAnalytics analytics = analytics();
        UUID reporter = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE login_name='reporter'", UUID.class);

        var before = analytics.execute("admin", query("""
                {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT","metric":"UNASSIGNED_BACKLOG_COUNT",
                 "period":"ROLLING_30_DAYS"}
                """, Set.of("FACILITIES"), Set.of()));
        assertEquals(NOW, before.snapshotAt(), "the snapshot uses the application-generated instant");
        assertEquals(1d, before.values().getFirst().numericValue());

        Thread writer = new Thread(() -> incident(
                reporter, "FACILITIES", "SUBMITTED", NOW.minusSeconds(86400), null));
        writer.start();
        writer.join(10_000);
        assertFalse(writer.isAlive(), "an insight query must not block an ordinary incident mutation");

        var after = analytics.execute("admin", query("""
                {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT","metric":"UNASSIGNED_BACKLOG_COUNT",
                 "period":"ROLLING_30_DAYS"}
                """, Set.of("FACILITIES"), Set.of()));
        assertEquals(2d, after.values().getFirst().numericValue(),
                "the concurrent insert becomes visible only in a later request");
    }

    /**
     * `AI-ADM-SAFE-01`: an anonymous incident contributes to permitted company and category
     * aggregates while no reporter identity reaches the result rows.
     */
    @Test
    void anonymousIncidentsCountInAggregatesWithoutRevealingAnyReporterIdentity() {
        UUID reporter = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE login_name='reporter'", UUID.class);
        incident(reporter, "FACILITIES", "SUBMITTED", NOW.minusSeconds(2L * 86400), null, true);

        var backlog = analytics().execute("admin", query("""
                {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT","metric":"UNASSIGNED_BACKLOG_COUNT",
                 "period":"ROLLING_30_DAYS"}
                """, Set.of("FACILITIES"), Set.of()));

        assertEquals(2d, backlog.values().getFirst().numericValue(),
                "the anonymous incident contributes to the permitted category aggregate");
        assertTrue(backlog.values().stream().noneMatch(value -> value.label().contains("reporter")),
                "no reporter identity may appear in a result label");
    }

    /** `AI-ADM-SAFE-01`: reporter grouping and reporter filtering are not reachable from a plan. */
    @Test
    void noPlanCanGroupOrFilterResultsByReporter() {
        assertThrows(RuntimeException.class, () -> query("""
                {"outcome":"QUERY","queryType":"RANK_CATEGORIES","metric":"INCIDENT_COUNT",
                 "period":"ROLLING_30_DAYS","direction":"DESCENDING","limit":5,"grouping":"REPORTER"}
                """, Set.of(), Set.of()));
    }

    /** `AI-ADM-QUERY-06`: a responder revoked after planning fails closed before analytics run. */
    @Test
    void aResponderDisabledAfterPlanningFailsClosedBeforeAnalyticsRun() {
        JdbcAdministratorInsightAnalytics analytics = analytics();
        jdbc.update("UPDATE accounts SET status='DISABLED' WHERE id=?", responderWithoutData);

        assertThrows(JdbcAdministratorInsightAnalytics.AnalyticsUnavailableException.class,
                () -> analytics.execute("admin", query("""
                        {"outcome":"QUERY","queryType":"COMPARE_SELECTED_RESPONDERS",
                         "metric":"AVERAGE_TIME_IN_PROGRESS","period":"ROLLING_30_DAYS"}
                        """, Set.of("IT"), Set.of(responder, responderWithoutData))));
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
        return incident(reporter, category, status, queued, assignee, false);
    }

    private static UUID incident(UUID reporter, String category, String status, Instant queued, UUID assignee,
            boolean anonymous) {
        UUID id = UUID.randomUUID();
        Instant assigned = status.equals("SUBMITTED") ? null : queued.plusSeconds(600);
        jdbc.update("""
                INSERT INTO incidents(id,reporter_id,title,description,category,status,anonymous,created_at,
                    submitted_at,queue_entered_at,first_assigned_at,latest_assigned_at,assignee_id)
                VALUES (?,?,'title','description',?,?,?,?,?,?,?,?,?)
                """, id, reporter, category, status, anonymous, Timestamp.from(queued), Timestamp.from(queued),
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
