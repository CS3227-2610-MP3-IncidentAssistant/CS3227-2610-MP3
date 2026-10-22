package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class AdministratorStatisticsMigrationUpgradePostgresTest {
    private static final String SCHEMA = "admin_statistics_upgrade_"
            + UUID.randomUUID().toString().replace("-", "");
    private static DataSource administrativeDataSource;
    private static DataSource testDataSource;

    @BeforeAll
    static void createIsolatedSchema() {
        NeonDatabaseUrl database = NeonDatabaseUrl.parse(requiredEnvironment("INCIDENT_DESK_TEST_DATABASE_URL"));
        administrativeDataSource = PostgresTestDataSource.administrative(
                database.jdbcUrl(), database.username(), database.password());
        new JdbcTemplate(administrativeDataSource).execute("CREATE SCHEMA " + SCHEMA);
        testDataSource = PostgresTestDataSource.isolated(
                database.jdbcUrl(), database.username(), database.password(), SCHEMA);
    }

    @AfterAll
    static void dropIsolatedSchema() {
        if (administrativeDataSource != null) {
            new JdbcTemplate(administrativeDataSource).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void v8ResolutionRowsGainHistoricalStatisticsSnapshotsDuringUpgrade() {
        Flyway.configure().dataSource(testDataSource).locations("classpath:db/migration")
                .defaultSchema(SCHEMA).schemas(SCHEMA).target("8").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(testDataSource);
        UUID reporter = account(jdbc, "reporter", "REPORTER");
        UUID responder = account(jdbc, "responder", "RESPONDER");
        UUID incident = UUID.randomUUID();
        Instant queuedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant assignedAt = queuedAt.plusSeconds(10);
        Instant resolvedAt = assignedAt.plusSeconds(20);
        jdbc.update("""
                INSERT INTO incidents (
                    id, reporter_id, title, description, category, status, anonymous,
                    created_at, submitted_at, queue_entered_at, first_assigned_at, latest_assigned_at
                ) VALUES (?, ?, 'Existing incident', 'Existing description', 'IT', 'RESOLVED', false,
                          ?, ?, ?, ?, ?)
                """, incident, reporter, Timestamp.from(queuedAt), Timestamp.from(queuedAt),
                Timestamp.from(queuedAt), Timestamp.from(assignedAt), Timestamp.from(assignedAt));
        UUID resolution = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incident_resolutions (
                    id, request_id, incident_id, cycle_number, resolved_by, resolved_at, remarks
                ) VALUES (?, ?, ?, 1, ?, ?, 'Fixed before V9')
                """, resolution, UUID.randomUUID(), incident, responder, Timestamp.from(resolvedAt));

        Flyway latest = Flyway.configure().dataSource(testDataSource).locations("classpath:db/migration")
                .defaultSchema(SCHEMA).schemas(SCHEMA).load();
        latest.migrate();
        latest.validate();

        assertEquals(queuedAt, jdbc.queryForObject(
                "SELECT queue_entered_at FROM incident_resolutions WHERE id = ?",
                Timestamp.class, resolution).toInstant());
        assertEquals(assignedAt, jdbc.queryForObject(
                "SELECT first_assigned_at FROM incident_resolutions WHERE id = ?",
                Timestamp.class, resolution).toInstant());
        assertEquals(responder, jdbc.queryForObject(
                "SELECT responder_at_resolution FROM incident_resolutions WHERE id = ?",
                UUID.class, resolution));
        assertEquals(11, jdbc.queryForObject(
                "SELECT MAX(CAST(version AS integer)) FROM flyway_schema_history WHERE success",
                Integer.class));
    }

    private static UUID account(JdbcTemplate jdbc, String loginName, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, 'ENABLED')",
                id, loginName, role);
        return id;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
