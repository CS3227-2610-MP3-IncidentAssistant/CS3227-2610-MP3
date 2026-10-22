package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class AdministratorSloPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final String SCHEMA = "admin_slo_test_" + UUID.randomUUID().toString().replace("-", "");

    private static DataSource administrativeDataSource;
    private static DataSource testDataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

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
        transactions = new TransactionTemplate(new DataSourceTransactionManager(testDataSource));
    }

    @AfterAll
    static void dropIsolatedSchema() {
        if (administrativeDataSource != null) {
            new JdbcTemplate(administrativeDataSource).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM slo_target_versions");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void changeAppendsVersionAndAuditAndReadReauthorizes() {
        UUID administrator = account("admin", "ADMINISTRATOR", "ENABLED");
        UUID version = UUID.randomUUID();

        AdministratorSloStore.ChangeOutcome outcome = store().change(
                "admin", "IT", null, 10, 20, new BigDecimal("5.00"), version, UUID.randomUUID(), NOW);

        assertEquals(AdministratorSloStore.ChangeOutcome.CHANGED, outcome);
        assertEquals(1, count("SELECT COUNT(*) FROM slo_target_versions WHERE id = ? AND changed_by = ?", version, administrator));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'SLO_CONFIGURATION_CHANGED' AND actor_account_id = ?", administrator));
        assertEquals(1, store().readAll("admin").versions().size());

        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", administrator);
        assertEquals(AdministratorSloStore.ReadOutcome.RESOURCE_UNAVAILABLE, store().readAll("admin").outcome());
        jdbc.update("UPDATE accounts SET status = 'ENABLED', role = 'REPORTER' WHERE id = ?", administrator);
        assertEquals(AdministratorSloStore.ReadOutcome.RESOURCE_UNAVAILABLE, store().readAll("admin").outcome());
        assertTrue(store().readAll("missing").versions().isEmpty());
    }

    @Test
    void unchangedStaleAndAuditFailureCreateNoMisleadingVersion() {
        account("admin", "ADMINISTRATOR", "ENABLED");
        UUID current = UUID.randomUUID();
        assertEquals(AdministratorSloStore.ChangeOutcome.CHANGED, store().change(
                "admin", "IT", null, 10, 20, new BigDecimal("5.00"), current, UUID.randomUUID(), NOW));

        assertEquals(AdministratorSloStore.ChangeOutcome.UNCHANGED, store().change(
                "admin", "IT", current, 10, 20, new BigDecimal("5.0"), UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(1)));
        assertEquals(AdministratorSloStore.ChangeOutcome.CONFLICT, store().change(
                "admin", "IT", UUID.randomUUID(), 11, 20, new BigDecimal("5.00"), UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(2)));

        UUID duplicateAudit = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_events (id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details)
                VALUES (?, ?, NULL, 'SYSTEM', 'TEST_SETUP', 'APPLICATION_DATA', 'setup', 'SUCCESS', '{}'::jsonb)
                """, duplicateAudit, Timestamp.from(NOW));
        assertEquals(AdministratorSloStore.ChangeOutcome.PERSISTENCE_FAILURE, store().change(
                "admin", "IT", current, 30, 40, new BigDecimal("6.00"), UUID.randomUUID(), duplicateAudit, NOW.plusSeconds(3)));

        assertEquals(1, count("SELECT COUNT(*) FROM slo_target_versions"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'SLO_CONFIGURATION_CHANGED'"));
    }

    @Test
    void concurrentChangesFromSameVersionHaveOneWinner() throws Exception {
        account("admin", "ADMINISTRATOR", "ENABLED");
        UUID current = UUID.randomUUID();
        store().change("admin", "FACILITIES", null, 10, 20, new BigDecimal("5.00"),
                current, UUID.randomUUID(), NOW);

        List<AdministratorSloStore.ChangeOutcome> outcomes;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AdministratorSloStore.ChangeOutcome> first = executor.submit(() -> store().change(
                    "admin", "FACILITIES", current, 11, 21, new BigDecimal("6.00"),
                    UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(1)));
            Future<AdministratorSloStore.ChangeOutcome> second = executor.submit(() -> store().change(
                    "admin", "FACILITIES", current, 12, 22, new BigDecimal("7.00"),
                    UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(2)));
            outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }

        assertTrue(outcomes.contains(AdministratorSloStore.ChangeOutcome.CHANGED));
        assertTrue(outcomes.contains(AdministratorSloStore.ChangeOutcome.CONFLICT));
        assertEquals(2, count("SELECT COUNT(*) FROM slo_target_versions WHERE category = 'FACILITIES'"));
        assertEquals(2, count("SELECT COUNT(*) FROM audit_events WHERE action = 'SLO_CONFIGURATION_CHANGED'"));
    }

    private static JdbcAdministratorSloStore store() {
        return new JdbcAdministratorSloStore(jdbc, transactions);
    }

    private static UUID account(String loginName, String role, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, ?)", id, loginName, role, status);
        return id;
    }

    private static int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
