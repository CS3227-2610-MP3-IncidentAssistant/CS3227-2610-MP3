package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
@ExtendWith(OutputCaptureExtension.class)
class AdministratorBootstrapPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private static final String SCHEMA = "admin_bootstrap_test_"
            + UUID.randomUUID().toString().replace("-", "");

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
    void clearBootstrapTables() {
        jdbc.update("DELETE FROM application_settings");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM account_credentials");
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void createsOnlyHashedCredentialMarkerAndSuccessAudit(CapturedOutput output) {
        String password = "synthetic-bootstrap-secret";
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

        bootstrap("initial-admin", password, encoder).run(arguments());

        UUID accountId = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE login_name = 'initial-admin'", UUID.class);
        String passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM account_credentials WHERE account_id = ?", String.class, accountId);
        assertTrue(encoder.matches(password, passwordHash));
        assertFalse(passwordHash.contains(password));
        assertEquals(accountId.toString(), jdbc.queryForObject(
                "SELECT setting_value FROM application_settings WHERE setting_key = ?",
                String.class, "administrator-bootstrap-completed"));
        assertEquals(1, auditCount("ADMINISTRATOR_BOOTSTRAPPED", "SUCCESS"));
        assertSystemActor("ADMINISTRATOR_BOOTSTRAPPED", "SUCCESS");
        assertAuditTarget("ADMINISTRATOR_BOOTSTRAPPED", "SUCCESS", "ACCOUNT", accountId.toString());
        assertFalse(auditText().contains(password));
        assertFalse(output.getAll().contains(password));
    }

    @Test
    void rejectsReplayWithoutAnotherAccountAndAuditsWithoutCredentials(CapturedOutput output) {
        String password = "synthetic-bootstrap-secret";
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        bootstrap("initial-admin", password, encoder).run(arguments());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bootstrap("replacement-admin", password, encoder).run(arguments()));

        assertFalse(failure.getMessage().contains(password));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE role = 'ADMINISTRATOR'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM application_settings", Integer.class));
        assertEquals(1, auditCount("ADMINISTRATOR_BOOTSTRAPPED", "SUCCESS"));
        assertEquals(1, auditCount("ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE"));
        assertSystemActor("ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE");
        assertAuditTarget("ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE",
                "APPLICATION_DATA", "administrator-bootstrap");
        assertFalse(auditText().contains(password));
        assertFalse(auditText().contains("replacement-admin"));
        assertFalse(output.getAll().contains(password));
    }

    @Test
    void refusesBootstrapWhenAnAdministratorExistsWithoutMarker() {
        UUID existingId = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, 'ADMINISTRATOR', 'ENABLED')",
                existingId, "existing-admin");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bootstrap("replacement-admin", "synthetic-bootstrap-secret",
                        new BCryptPasswordEncoder(4)).run(arguments()));

        assertFalse(failure.getMessage().contains("synthetic-bootstrap-secret"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE role = 'ADMINISTRATOR'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM application_settings", Integer.class));
        assertEquals(0, auditCount("ADMINISTRATOR_BOOTSTRAPPED", "SUCCESS"));
        assertEquals(1, auditCount("ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE"));
        assertSystemActor("ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE");
        assertAuditTarget("ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE",
                "APPLICATION_DATA", "administrator-bootstrap");
    }

    @Test
    void rollsBackAccountCredentialMarkerAndSuccessAuditWhenAuditWriteFails(CapturedOutput output) {
        jdbc.execute("""
                CREATE FUNCTION reject_bootstrap_audit() RETURNS trigger AS $$
                BEGIN
                    IF NEW.action = 'ADMINISTRATOR_BOOTSTRAPPED' THEN
                        RAISE EXCEPTION 'test-only audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_bootstrap_audit_before_insert
                BEFORE INSERT ON audit_events
                FOR EACH ROW EXECUTE FUNCTION reject_bootstrap_audit()
                """);

        try {
            String password = "synthetic-bootstrap-secret";
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> bootstrap("rollback-admin", password,
                            new BCryptPasswordEncoder(4)).run(arguments()));

            assertFalse(failure.toString().contains(password));
            assertFalse(output.getAll().contains(password));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM accounts WHERE login_name = 'rollback-admin'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_credentials", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM application_settings", Integer.class));
            assertEquals(0, auditCount("ADMINISTRATOR_BOOTSTRAPPED", "SUCCESS"));
        } finally {
            jdbc.execute("DROP TRIGGER reject_bootstrap_audit_before_insert ON audit_events");
            jdbc.execute("DROP FUNCTION reject_bootstrap_audit()");
        }
    }

    @Test
    void concurrentStartsCreateOneAdministratorAndAuditTheRejectedStart() throws Exception {
        int attempts = 2;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                int runner = index;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        bootstrap("parallel-admin-" + runner, "synthetic-bootstrap-secret",
                                new BCryptPasswordEncoder(4)).run(arguments());
                        return true;
                    } catch (IllegalStateException rejected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
        }

        assertEquals(1, results.stream().map(AdministratorBootstrapPostgresTest::get)
                .filter(Boolean::booleanValue).count());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE role = 'ADMINISTRATOR'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM account_credentials", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM application_settings", Integer.class));
        assertEquals(1, auditCount("ADMINISTRATOR_BOOTSTRAPPED", "SUCCESS"));
        assertEquals(1, auditCount("ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE"));
        assertSystemActor("ADMINISTRATOR_BOOTSTRAPPED", "SUCCESS");
        assertSystemActor("ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE");
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'ADMINISTRATOR_BOOTSTRAPPED'
                  AND target_type = 'ACCOUNT'
                  AND target_identifier = (SELECT id::text FROM accounts WHERE role = 'ADMINISTRATOR')
                """, Integer.class));
        assertFalse(auditText().contains("synthetic-bootstrap-secret"));
    }

    private static AdministratorBootstrap bootstrap(
            String login,
            String password,
            BCryptPasswordEncoder encoder) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN", login)
                .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD", password);
        return new AdministratorBootstrap(
                environment,
                new JdbcTemplate(testDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(testDataSource)),
                encoder,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private int auditCount(String action, String outcome) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = ? AND outcome = ?",
                Integer.class, action, outcome);
    }

    private void assertSystemActor(String action, String outcome) {
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = ? AND outcome = ?
                  AND actor_account_id IS NULL AND actor_role = 'SYSTEM'
                """, Integer.class, action, outcome));
    }

    private void assertAuditTarget(String action, String outcome, String targetType, String targetIdentifier) {
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = ? AND outcome = ? AND target_type = ? AND target_identifier = ?
                """, Integer.class, action, outcome, targetType, targetIdentifier));
    }

    private String auditText() {
        return jdbc.queryForObject("SELECT COALESCE(string_agg(CAST(details AS text), ' '), '') FROM audit_events",
                String.class);
    }

    private static DefaultApplicationArguments arguments() {
        return new DefaultApplicationArguments(new String[0]);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static void requireIsolationConfirmation() {
        if (!"true".equalsIgnoreCase(requiredEnvironment("INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED"))) {
            throw new IllegalStateException(
                    "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED must be true");
        }
    }

    private static boolean get(Future<Boolean> result) {
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Bootstrap runner was interrupted", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new AssertionError("Bootstrap runner failed unexpectedly", failure);
        }
    }
}
