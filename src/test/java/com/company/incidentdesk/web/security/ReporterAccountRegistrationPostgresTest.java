package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;
import com.company.incidentdesk.web.security.ReporterAccountRegistrationService.RegistrationOutcome;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class ReporterAccountRegistrationPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private static final String VALID_PASSWORD = "database-secret";
    private static final String SCHEMA = "registration_test_" + UUID.randomUUID().toString().replace("-", "");

    private static DataSource administrativeDataSource;
    private static DataSource testDataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void createIsolatedSchema() {
        requireIsolationConfirmation();
        NeonDatabaseUrl database = NeonDatabaseUrl.parse(
                requiredEnvironment("INCIDENT_DESK_TEST_DATABASE_URL"));
        administrativeDataSource = PostgresTestDataSource.administrative(
                database.jdbcUrl(), database.username(), database.password());
        new JdbcTemplate(administrativeDataSource).execute("CREATE SCHEMA " + SCHEMA);

        testDataSource = PostgresTestDataSource.isolated(
                database.jdbcUrl(), database.username(), database.password(), SCHEMA);
        Flyway.configure()
                .dataSource(testDataSource)
                .locations("classpath:db/migration")
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(testDataSource);
    }

    @AfterAll
    static void dropIsolatedSchema() {
        if (administrativeDataSource != null) {
            new JdbcTemplate(administrativeDataSource).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void clearRegistrationTables() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM account_credentials");
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void commitsAccountHashedCredentialAndAuditTogether() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        UUID accountId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID auditId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        ReporterAccountRegistrationService service = service(encoder, accountId, auditId);

        assertEquals(RegistrationOutcome.CREATED,
                service.register("CaseSensitive", VALID_PASSWORD, VALID_PASSWORD));

        assertEquals("REPORTER", jdbc.queryForObject(
                "SELECT role FROM accounts WHERE login_name = 'CaseSensitive'", String.class));
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM account_credentials WHERE account_id = ?", String.class, accountId);
        assertTrue(encoder.matches(VALID_PASSWORD, hash));
        assertFalse(hash.contains(VALID_PASSWORD));
        assertEquals("ACCOUNT_REGISTERED", jdbc.queryForObject(
                "SELECT action FROM audit_events WHERE id = ?", String.class, auditId));
        String auditDetails = jdbc.queryForObject(
                "SELECT CAST(details AS text) FROM audit_events WHERE id = ?", String.class, auditId);
        assertFalse(auditDetails.contains(VALID_PASSWORD));
    }

    @Test
    void treatsNamesAsCaseSensitiveAndAllowsExactlyOneConcurrentExactDuplicate() throws Exception {
        assertEquals(RegistrationOutcome.CREATED,
                service(new BCryptPasswordEncoder(4), UUID.randomUUID(), UUID.randomUUID())
                        .register("ConcurrentName", VALID_PASSWORD, VALID_PASSWORD));
        assertEquals(RegistrationOutcome.CREATED,
                service(new BCryptPasswordEncoder(4), UUID.randomUUID(), UUID.randomUUID())
                        .register("concurrentname", VALID_PASSWORD, VALID_PASSWORD));

        int attempts = 6;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegistrationOutcome>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service(new BCryptPasswordEncoder(4), UUID.randomUUID(), UUID.randomUUID())
                            .register("ExactDuplicate", VALID_PASSWORD, VALID_PASSWORD);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
        }

        List<RegistrationOutcome> outcomes = new ArrayList<>();
        for (Future<RegistrationOutcome> future : futures) {
            outcomes.add(future.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, outcomes.stream().filter(RegistrationOutcome.CREATED::equals).count());
        assertEquals(attempts - 1L, outcomes.stream().filter(RegistrationOutcome.DUPLICATE::equals).count());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE login_name = 'ExactDuplicate'", Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM audit_events audit
                JOIN accounts account ON account.id = audit.actor_account_id
                WHERE account.login_name = 'ExactDuplicate'
                """, Integer.class));
    }

    @Test
    void rollsBackAccountAndCredentialWhenAuditInsertFails() {
        UUID conflictingAuditId = UUID.fromString("20000000-0000-0000-0000-000000000010");
        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, NULL, 'REPORTER', 'TEST_SETUP', 'ACCOUNT', 'setup', 'SUCCESS', '{}'::jsonb)
                """, conflictingAuditId, Timestamp.from(NOW));

        UUID accountId = UUID.fromString("20000000-0000-0000-0000-000000000011");
        RegistrationOutcome outcome = service(new BCryptPasswordEncoder(4), accountId, conflictingAuditId)
                .register("rollback-user", VALID_PASSWORD, VALID_PASSWORD);

        assertEquals(RegistrationOutcome.UNAVAILABLE, outcome);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, accountId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_credentials WHERE account_id = ?", Integer.class, accountId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE id = ?", Integer.class, conflictingAuditId));
    }

    private static ReporterAccountRegistrationService service(
            BCryptPasswordEncoder encoder,
            UUID accountId,
            UUID auditId) {
        Deque<UUID> identifiers = new ArrayDeque<>(List.of(accountId, auditId));
        JdbcReporterAccountRegistrationStore store = new JdbcReporterAccountRegistrationStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(testDataSource)));
        return new ReporterAccountRegistrationService(
                store,
                encoder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                identifiers::removeFirst);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static void requireIsolationConfirmation() {
        if (!"true".equalsIgnoreCase(
                requiredEnvironment("INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED"))) {
            throw new IllegalStateException(
                    "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED must be true");
        }
    }
}
