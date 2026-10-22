package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
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

import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class AdministratorAccountPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");
    private static final String SCHEMA = "admin_account_test_" + UUID.randomUUID().toString().replace("-", "");

    private static DataSource administrativeDataSource;
    private static DataSource testDataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void createIsolatedSchema() {
        requireIsolationConfirmation();
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
        jdbc.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbc.update("DELETE FROM SPRING_SESSION");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM account_credentials");
        jdbc.update("DELETE FROM responder_category_access");
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void categoryReplacementCommitsAccessSessionInvalidationAndAuditAtomically() {
        UUID administrator = account("admin", "ADMINISTRATOR");
        UUID responder = account("responder", "RESPONDER");
        session("responder");
        jdbc.update("INSERT INTO responder_category_access (account_id, category, granted_at) VALUES (?, 'IT', ?)",
                responder, Timestamp.from(NOW));

        ResponderCategoryAccessOutcome outcome = store().replaceResponderCategories(
                "admin", responder, 0, Set.of(IncidentCategory.FACILITIES, IncidentCategory.HUMAN_RELATIONS),
                UUID.randomUUID(), NOW);

        assertEquals(ResponderCategoryAccessOutcome.UPDATED, outcome);
        assertEquals(List.of("FACILITIES", "HUMAN_RELATIONS"), jdbc.queryForList("""
                SELECT category FROM responder_category_access
                 WHERE account_id = ? ORDER BY category
                """, String.class, responder));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Long.class, responder));
        assertEquals(0, count("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'responder'"));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM audit_events
                 WHERE actor_account_id = ? AND action = 'RESPONDER_ACCESS_CHANGED'
                """, administrator));
        assertEquals("IT", value("""
                SELECT details -> 'changes' -> 0 ->> 'before' FROM audit_events
                 WHERE action = 'RESPONDER_ACCESS_CHANGED'
                """));
        assertEquals("HUMAN_RELATIONS,FACILITIES", value("""
                SELECT details -> 'changes' -> 0 ->> 'after' FROM audit_events
                 WHERE action = 'RESPONDER_ACCESS_CHANGED'
                """));
    }

    @Test
    void emptyAndUnchangedCategorySetsAreSupportedWithoutMisleadingAudit() {
        account("admin", "ADMINISTRATOR");
        UUID responder = account("responder", "RESPONDER");
        session("responder");
        jdbc.update("INSERT INTO responder_category_access (account_id, category, granted_at) VALUES (?, 'IT', ?)",
                responder, Timestamp.from(NOW));

        assertEquals(ResponderCategoryAccessOutcome.UPDATED,
                store().replaceResponderCategories("admin", responder, 0, Set.of(), UUID.randomUUID(), NOW));
        session("responder");
        assertEquals(ResponderCategoryAccessOutcome.UNCHANGED,
                store().replaceResponderCategories("admin", responder, 1, Set.of(), UUID.randomUUID(), NOW));

        assertEquals(0, count("SELECT COUNT(*) FROM responder_category_access WHERE account_id = ?", responder));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Long.class, responder));
        assertEquals(1, count("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'responder'"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_ACCESS_CHANGED'"));
    }

    @Test
    void categoryReplacementReauthorizesActorAndEnabledResponderInsideTransaction() {
        account("reporter-actor", "REPORTER");
        UUID responder = account("responder", "RESPONDER");
        account("admin", "ADMINISTRATOR");
        UUID reporterTarget = account("reporter-target", "REPORTER");

        assertEquals(ResponderCategoryAccessOutcome.RESOURCE_UNAVAILABLE,
                store().replaceResponderCategories("reporter-actor", responder,
                        0, Set.of(IncidentCategory.IT), UUID.randomUUID(), NOW));
        assertEquals(ResponderCategoryAccessOutcome.RESOURCE_UNAVAILABLE,
                store().replaceResponderCategories("admin", reporterTarget,
                        0, Set.of(IncidentCategory.IT), UUID.randomUUID(), NOW));
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", responder);
        assertEquals(ResponderCategoryAccessOutcome.RESOURCE_UNAVAILABLE,
                store().replaceResponderCategories("admin", responder,
                        0, Set.of(IncidentCategory.IT), UUID.randomUUID(), NOW));

        assertEquals(0, count("SELECT COUNT(*) FROM responder_category_access"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events"));
    }

    @Test
    void categoryAuditFailureRollsBackAccessVersionAndSessionInvalidation() {
        account("admin", "ADMINISTRATOR");
        UUID responder = account("responder", "RESPONDER");
        session("responder");
        jdbc.update("INSERT INTO responder_category_access (account_id, category, granted_at) VALUES (?, 'IT', ?)",
                responder, Timestamp.from(NOW));
        UUID duplicateAudit = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_events (id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details)
                VALUES (?, ?, NULL, 'SYSTEM', 'TEST_SETUP', 'APPLICATION_DATA', 'setup', 'SUCCESS', '{}'::jsonb)
                """, duplicateAudit, Timestamp.from(NOW));

        ResponderCategoryAccessOutcome outcome = store().replaceResponderCategories(
                "admin", responder, 0, Set.of(IncidentCategory.FACILITIES), duplicateAudit, NOW);

        assertEquals(ResponderCategoryAccessOutcome.PERSISTENCE_FAILURE, outcome);
        assertEquals(List.of("IT"), jdbc.queryForList(
                "SELECT category FROM responder_category_access WHERE account_id = ?", String.class, responder));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Long.class, responder));
        assertEquals(1, count("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'responder'"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_ACCESS_CHANGED'"));
    }

    @Test
    void concurrentCategoryReplacementsFromTheSameVersionHaveOneWinner() throws Exception {
        account("admin", "ADMINISTRATOR");
        UUID responder = account("responder", "RESPONDER");
        CyclicBarrier start = new CyclicBarrier(2);

        List<ResponderCategoryAccessOutcome> outcomes;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResponderCategoryAccessOutcome> first = executor.submit(() -> {
                await(start);
                return store().replaceResponderCategories("admin", responder, 0,
                        Set.of(IncidentCategory.IT), UUID.randomUUID(), NOW);
            });
            Future<ResponderCategoryAccessOutcome> second = executor.submit(() -> {
                await(start);
                return store().replaceResponderCategories("admin", responder, 0,
                        Set.of(IncidentCategory.FACILITIES), UUID.randomUUID(), NOW);
            });
            outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }

        assertTrue(outcomes.contains(ResponderCategoryAccessOutcome.UPDATED));
        assertTrue(outcomes.contains(ResponderCategoryAccessOutcome.CONFLICT));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Long.class, responder));
        assertEquals(1, count("SELECT COUNT(*) FROM responder_category_access WHERE account_id = ?", responder));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_ACCESS_CHANGED'"));
    }

    @Test
    void tombstoneCommitsCredentialSessionAccessAndAuditAtomically() {
        UUID administrator = account("admin", "ADMINISTRATOR");
        UUID responder = account("responder", "RESPONDER");
        credential(responder);
        session("responder");
        jdbc.update("INSERT INTO responder_category_access (account_id, category, granted_at) VALUES (?, 'IT', ?)",
                responder, Timestamp.from(NOW));

        AccountTombstoneOutcome outcome = store().tombstone("admin", responder, UUID.randomUUID(), NOW);

        assertEquals(AccountTombstoneOutcome.TOMBSTONED, outcome);
        assertEquals("DELETED", value("SELECT status FROM accounts WHERE id = ?", responder));
        assertEquals("deleted:" + responder, value("SELECT login_name FROM accounts WHERE id = ?", responder));
        assertEquals(0, count("SELECT COUNT(*) FROM account_credentials WHERE account_id = ?", responder));
        assertEquals(0, count("SELECT COUNT(*) FROM responder_category_access WHERE account_id = ?", responder));
        assertEquals(0, count("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'responder'"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE actor_account_id = ? AND action = 'ACCOUNT_DELETED'",
                administrator));
    }

    @Test
    void duplicateAuditRollsBackTheEntireTombstone() {
        account("admin", "ADMINISTRATOR");
        UUID reporter = account("reporter", "REPORTER");
        credential(reporter);
        session("reporter");
        UUID duplicateAudit = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_events (id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details)
                VALUES (?, ?, NULL, 'SYSTEM', 'TEST_SETUP', 'APPLICATION_DATA', 'setup', 'SUCCESS', '{}'::jsonb)
                """, duplicateAudit, Timestamp.from(NOW));

        AccountTombstoneOutcome outcome = store().tombstone("admin", reporter, duplicateAudit, NOW);

        assertEquals(AccountTombstoneOutcome.PERSISTENCE_FAILURE, outcome);
        assertEquals("ENABLED", value("SELECT status FROM accounts WHERE id = ?", reporter));
        assertEquals("reporter", value("SELECT login_name FROM accounts WHERE id = ?", reporter));
        assertEquals(1, count("SELECT COUNT(*) FROM account_credentials WHERE account_id = ?", reporter));
        assertEquals(1, count("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'reporter'"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE action = 'ACCOUNT_DELETED'"));
    }

    @Test
    void reciprocalAdministratorTombstonesCannotBothCommitAndRetryReauthorizes() throws Exception {
        account("admin-a", "ADMINISTRATOR");
        UUID adminB = account("admin-b", "ADMINISTRATOR");
        UUID adminA = jdbc.queryForObject("SELECT id FROM accounts WHERE login_name = 'admin-a'", UUID.class);
        CyclicBarrier actorLocks = new CyclicBarrier(2);
        JdbcAdministratorAccountStore racingStore = new JdbcAdministratorAccountStore(
                jdbc, transactions, () -> await(actorLocks));

        List<AccountTombstoneOutcome> outcomes;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AccountTombstoneOutcome> first = executor.submit(() ->
                    racingStore.tombstone("admin-a", adminB, UUID.randomUUID(), NOW));
            Future<AccountTombstoneOutcome> second = executor.submit(() ->
                    racingStore.tombstone("admin-b", adminA, UUID.randomUUID(), NOW));
            outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }

        assertTrue(outcomes.contains(AccountTombstoneOutcome.TOMBSTONED));
        assertTrue(outcomes.contains(AccountTombstoneOutcome.CONFLICT));
        assertEquals(1, count("SELECT COUNT(*) FROM accounts WHERE role = 'ADMINISTRATOR' AND status = 'ENABLED'"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'ACCOUNT_DELETED'"));

        String deletedLogin = "DELETED".equals(value("SELECT status FROM accounts WHERE id = ?", adminA))
                ? "admin-a" : "admin-b";
        UUID enabledTarget = "admin-a".equals(deletedLogin) ? adminB : adminA;
        assertEquals(AccountTombstoneOutcome.RESOURCE_UNAVAILABLE,
                store().tombstone(deletedLogin, enabledTarget, UUID.randomUUID(), NOW));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'ACCOUNT_DELETED'"));
    }

    private static JdbcAdministratorAccountStore store() {
        return new JdbcAdministratorAccountStore(jdbc, transactions);
    }

    private static UUID account(String loginName, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, 'ENABLED')",
                id, loginName, role);
        return id;
    }

    private static void credential(UUID accountId) {
        jdbc.update("INSERT INTO account_credentials (account_id, password_hash) VALUES (?, 'synthetic-hash')",
                accountId);
    }

    private static void session(String principalName) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO SPRING_SESSION (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME,
                    MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)
                VALUES (?, ?, 1, 1, 3600, 3600001, ?)
                """, id, id, principalName);
    }

    private static int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private static String value(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("timed out synchronizing actor locks", exception);
        }
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
