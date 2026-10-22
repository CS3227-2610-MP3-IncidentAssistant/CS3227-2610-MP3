package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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

import com.company.incidentdesk.application.account.PromotionRequestService;
import com.company.incidentdesk.application.audit.AuditEventFactory;
import com.company.incidentdesk.application.authorization.AccountAuthorizationPolicy;
import com.company.incidentdesk.application.session.SessionProvider;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.PromotionRequestStatus;
import com.company.incidentdesk.domain.account.ResponderAccess;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.audit.AuditAction;
import com.company.incidentdesk.domain.audit.AuditEventId;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.support.PostgresTestDataSource;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class PromotionWorkflowPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-19T06:00:00Z");
    private static final String SCHEMA = "promotion_test_" + UUID.randomUUID().toString().replace("-", "");

    private static DataSource administrativeDataSource;
    private static DataSource testDataSource;
    private static JdbcTemplate jdbc;
    private static JdbcPromotionWorkflowStore store;

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
        store = new JdbcPromotionWorkflowStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(testDataSource)));
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
        jdbc.update("DELETE FROM responder_category_access");
        jdbc.update("DELETE FROM responder_promotion_requests");
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void migrationPersistsSubsetAndAuditAndInvalidatesEveryReporterSession() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4'", Integer.class));
        Account reporter = account(Role.REPORTER, "subset-reporter");
        Account administrator = account(Role.ADMINISTRATOR, "subset-admin");
        store.create(reporter);
        store.create(administrator);
        ResponderPromotionRequest request = submit(reporter,
                Set.of(IncidentCategory.IT, IncidentCategory.FACILITIES));
        addSession(reporter.loginName());
        addSession(reporter.loginName());
        addSession(administrator.loginName());

        var result = service(UUID::randomUUID).decideAs(administrator, request.id(), true,
                Set.of(IncidentCategory.IT));

        assertTrue(result.isSuccess());
        assertEquals(Role.RESPONDER, store.findById(reporter.id()).orElseThrow().role());
        assertEquals(Set.of(IncidentCategory.IT), store.findById(reporter.id()).orElseThrow()
                .responderAccess().categories());
        assertEquals(Set.of(IncidentCategory.IT), store.findPromotionRequest(request.id()).orElseThrow()
                .approvedCategories().orElseThrow());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class, reporter.loginName()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class, administrator.loginName()));
    }

    @Test
    void reporterHistoryQueryReturnsOnlyTheCurrentReportersRows() {
        Account reporter = account(Role.REPORTER, "history-reporter");
        Account otherReporter = account(Role.REPORTER, "other-history-reporter");
        store.create(reporter);
        store.create(otherReporter);
        ResponderPromotionRequest owned = submit(reporter, Set.of(IncidentCategory.IT));
        ResponderPromotionRequest other = service(UUID::randomUUID).submitAs(otherReporter,
                Set.of(IncidentCategory.FACILITIES), Optional.of("private other-reporter detail"))
                .value().orElseThrow();

        List<ResponderPromotionRequest> history = service(UUID::randomUUID).visibleHistoryAs(reporter);

        assertEquals(List.of(owned.id()), history.stream().map(ResponderPromotionRequest::id).toList());
        assertFalse(history.stream().anyMatch(request -> request.id().equals(other.id())
                || request.comments().filter(comment -> comment.contains("private other-reporter detail")).isPresent()));
    }

    @Test
    void reporterHistoryPaginationIsNewestFirstAndReportsTheOwnedCount() {
        Account reporter = account(Role.REPORTER, "paged-history-reporter");
        Account administrator = account(Role.ADMINISTRATOR, "paged-history-admin");
        store.create(reporter);
        store.create(administrator);
        ResponderPromotionRequest oldest = submit(reporter, Set.of(IncidentCategory.IT));
        assertTrue(service(UUID::randomUUID).decideAs(
                administrator, oldest.id(), false, Set.of()).isSuccess());
        ResponderPromotionRequest middle = submit(reporter, Set.of(IncidentCategory.FACILITIES));
        assertTrue(service(UUID::randomUUID).decideAs(
                administrator, middle.id(), false, Set.of()).isSuccess());
        ResponderPromotionRequest newest = submit(reporter, Set.of(IncidentCategory.HUMAN_RELATIONS));
        jdbc.update("UPDATE responder_promotion_requests SET requested_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(2)), oldest.id().value());
        jdbc.update("UPDATE responder_promotion_requests SET requested_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)), middle.id().value());

        assertEquals(List.of(newest.id()), store.findPromotionRequestsByRequester(reporter.id(), 0, 1)
                .stream().map(ResponderPromotionRequest::id).toList());
        assertEquals(List.of(middle.id()), store.findPromotionRequestsByRequester(reporter.id(), 1, 1)
                .stream().map(ResponderPromotionRequest::id).toList());
        assertEquals(3, store.countPromotionRequestsByRequester(reporter.id()));
    }

    @Test
    void concurrentDuplicateSubmissionsCreateOnePendingRequestAndOneSuccessAudit() throws Exception {
        Account reporter = account(Role.REPORTER, "concurrent-requester");
        store.create(reporter);
        int attempts = 2;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service(UUID::randomUUID).submitAs(
                            reporter, Set.of(IncidentCategory.IT), Optional.empty()).isSuccess();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
        }

        assertEquals(1, results.stream().filter(PromotionWorkflowPostgresTest::get).count());
        assertEquals(1, store.countPendingPromotionRequests());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_PROMOTION_REQUESTED'",
                Integer.class));
    }

    @Test
    void concurrentReporterDisableWinsBeforeRequestCommit() throws Exception {
        Account reporter = account(Role.REPORTER, "stale-requester");
        store.create(reporter);
        CountDownLatch disableLocked = new CountDownLatch(1);
        CountDownLatch allowDisableCommit = new CountDownLatch(1);
        CountDownLatch submissionStarted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(testDataSource));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", reporter.id().value());
                disableLocked.countDown();
                await(allowDisableCommit);
            }));
            assertTrue(disableLocked.await(10, TimeUnit.SECONDS));
            Future<Boolean> submission = executor.submit(() -> {
                submissionStarted.countDown();
                return service(UUID::randomUUID).submitAs(
                        reporter, Set.of(IncidentCategory.IT), Optional.empty()).isSuccess();
            });
            assertTrue(submissionStarted.await(10, TimeUnit.SECONDS));
            allowDisableCommit.countDown();
            disable.get(10, TimeUnit.SECONDS);

            assertFalse(submission.get(10, TimeUnit.SECONDS));
            assertEquals(0, store.countPromotionRequestsByRequester(reporter.id()));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_PROMOTION_REQUESTED'",
                    Integer.class));
        } finally {
            allowDisableCommit.countDown();
        }
    }

    @Test
    void requestAuditFailureRollsBackThePendingRequest() {
        Account reporter = account(Role.REPORTER, "request-rollback-reporter");
        store.create(reporter);
        UUID conflictingAuditId = UUID.randomUUID();
        insertAuditWithId(conflictingAuditId, reporter);

        var result = service(() -> conflictingAuditId).submitAs(
                reporter, Set.of(IncidentCategory.FACILITIES), Optional.of("not retained after rollback"));

        assertFalse(result.isSuccess());
        assertEquals(0, store.countPromotionRequestsByRequester(reporter.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class));
    }

    @Test
    void rejectionRetainsReporterRoleAccessAndSessionsWithOneDecisionAudit() {
        Account reporter = account(Role.REPORTER, "rejected-reporter");
        Account administrator = account(Role.ADMINISTRATOR, "rejecting-admin");
        store.create(reporter);
        store.create(administrator);
        ResponderPromotionRequest pending = submit(reporter, Set.of(IncidentCategory.IT));
        addSession(reporter.loginName());
        addSession(reporter.loginName());

        var result = service(UUID::randomUUID).decideAs(administrator, pending.id(), false, Set.of());

        assertTrue(result.isSuccess());
        assertEquals(PromotionRequestStatus.REJECTED,
                store.findPromotionRequest(pending.id()).orElseThrow().status());
        assertEquals(Role.REPORTER, store.findById(reporter.id()).orElseThrow().role());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM responder_category_access WHERE account_id = ?",
                Integer.class, reporter.id().value()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class, reporter.loginName()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_PROMOTION_REJECTED'",
                Integer.class));
    }

    @Test
    void concurrentAdministratorRevocationWinsBeforeDecisionCommit() throws Exception {
        Account reporter = account(Role.REPORTER, "stale-reporter");
        Account administrator = account(Role.ADMINISTRATOR, "stale-admin");
        store.create(reporter);
        store.create(administrator);
        ResponderPromotionRequest pending = submit(reporter, Set.of(IncidentCategory.IT));
        CountDownLatch revocationLocked = new CountDownLatch(1);
        CountDownLatch allowRevocationCommit = new CountDownLatch(1);
        CountDownLatch decisionStarted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(testDataSource));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> revocation = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", administrator.id().value());
                revocationLocked.countDown();
                await(allowRevocationCommit);
            }));
            assertTrue(revocationLocked.await(10, TimeUnit.SECONDS));
            Future<Boolean> decision = executor.submit(() -> {
                decisionStarted.countDown();
                return service(UUID::randomUUID).decideAs(administrator, pending.id(), true,
                        Set.of(IncidentCategory.IT)).isSuccess();
            });
            assertTrue(decisionStarted.await(10, TimeUnit.SECONDS));
            allowRevocationCommit.countDown();
            revocation.get(10, TimeUnit.SECONDS);

            assertFalse(decision.get(10, TimeUnit.SECONDS));
            assertEquals("DISABLED", jdbc.queryForObject(
                    "SELECT status FROM accounts WHERE id = ?", String.class, administrator.id().value()));
            assertEquals(PromotionRequestStatus.PENDING,
                    store.findPromotionRequest(pending.id()).orElseThrow().status());
            assertEquals(Role.REPORTER, store.findById(reporter.id()).orElseThrow().role());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM responder_category_access WHERE account_id = ?",
                    Integer.class, reporter.id().value()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_PROMOTION_REQUESTED'",
                    Integer.class));
        } finally {
            allowRevocationCommit.countDown();
        }
    }

    @Test
    void concurrentRequesterDisableWinsBeforeDecisionCommit() throws Exception {
        Account reporter = account(Role.REPORTER, "ineligible-requester");
        Account administrator = account(Role.ADMINISTRATOR, "eligibility-admin");
        store.create(reporter);
        store.create(administrator);
        ResponderPromotionRequest pending = submit(reporter, Set.of(IncidentCategory.IT));
        CountDownLatch disableLocked = new CountDownLatch(1);
        CountDownLatch allowDisableCommit = new CountDownLatch(1);
        CountDownLatch decisionStarted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(testDataSource));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", reporter.id().value());
                disableLocked.countDown();
                await(allowDisableCommit);
            }));
            assertTrue(disableLocked.await(10, TimeUnit.SECONDS));
            Future<Boolean> decision = executor.submit(() -> {
                decisionStarted.countDown();
                return service(UUID::randomUUID).decideAs(
                        administrator, pending.id(), true, Set.of(IncidentCategory.IT)).isSuccess();
            });
            assertTrue(decisionStarted.await(10, TimeUnit.SECONDS));
            allowDisableCommit.countDown();
            disable.get(10, TimeUnit.SECONDS);

            assertFalse(decision.get(10, TimeUnit.SECONDS));
            assertEquals(PromotionRequestStatus.PENDING,
                    store.findPromotionRequest(pending.id()).orElseThrow().status());
            assertEquals("DISABLED", jdbc.queryForObject(
                    "SELECT status FROM accounts WHERE id = ?", String.class, reporter.id().value()));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_PROMOTION_APPROVED'",
                    Integer.class));
        } finally {
            allowDisableCommit.countDown();
        }
    }

    @Test
    void concurrentDecisionAndAuditFailureCannotCreatePartialPromotion() throws Exception {
        Account reporter = account(Role.REPORTER, "race-reporter");
        Account administrator = account(Role.ADMINISTRATOR, "race-admin");
        store.create(reporter);
        store.create(administrator);
        ResponderPromotionRequest pending = submit(reporter, Set.of(IncidentCategory.IT));
        int attempts = 2;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service(UUID::randomUUID).decideAs(administrator, pending.id(), true,
                            Set.of(IncidentCategory.IT)).isSuccess();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
        }
        assertEquals(1, results.stream().filter(future -> get(future)).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_PROMOTION_APPROVED'",
                Integer.class));

        Account reporter2 = account(Role.REPORTER, "rollback-reporter");
        Account administrator2 = account(Role.ADMINISTRATOR, "rollback-admin");
        store.create(reporter2);
        store.create(administrator2);
        ResponderPromotionRequest request2 = submit(reporter2, Set.of(IncidentCategory.FACILITIES));
        UUID conflictingAuditId = UUID.randomUUID();
        insertAuditWithId(conflictingAuditId, administrator2);
        Supplier<UUID> ids = () -> conflictingAuditId;
        var failed = service(ids).decideAs(administrator2, request2.id(), true,
                Set.of(IncidentCategory.FACILITIES));

        assertFalse(failed.isSuccess());
        assertEquals(PromotionRequestStatus.PENDING, store.findPromotionRequest(request2.id()).orElseThrow().status());
        assertEquals(Role.REPORTER, store.findById(reporter2.id()).orElseThrow().role());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM responder_category_access WHERE account_id = ?",
                Integer.class, reporter2.id().value()));
    }

    private ResponderPromotionRequest submit(Account reporter, Set<IncidentCategory> categories) {
        var result = service(UUID::randomUUID).submitAs(reporter, categories, Optional.empty());
        assertTrue(result.isSuccess());
        return result.value().orElseThrow();
    }

    private PromotionRequestService service(Supplier<UUID> identifiers) {
        SessionProvider sessions = new SessionProvider() {
            @Override public Optional<com.company.incidentdesk.application.session.AuthenticatedSession> currentSession() {
                return Optional.empty();
            }
            @Override public Optional<Account> currentAccount() { return Optional.empty(); }
        };
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new PromotionRequestService(sessions, new AccountAuthorizationPolicy(sessions), store, store,
                new AuditEventFactory(clock, () -> new AuditEventId(identifiers.get())), clock,
                () -> new PromotionRequestId(UUID.randomUUID()), ignored -> { });
    }

    private void addSession(String principal) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO SPRING_SESSION (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME,
                    MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)
                VALUES (?, ?, 1, 1, 3600, 3600001, ?)
                """, id, id, principal);
    }

    private void insertAuditWithId(UUID id, Account actor) {
        jdbc.update("""
                INSERT INTO audit_events (id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details)
                VALUES (?, ?, ?, ?, 'TEST_SETUP', 'ACCOUNT', 'setup', 'SUCCESS', '{}'::jsonb)
                """, id, java.sql.Timestamp.from(NOW), actor.id().value(), actor.role().name());
    }

    private static Account account(Role role, String loginName) {
        return new Account(new AccountId(UUID.randomUUID()), loginName, role, AccountStatus.ENABLED,
                ResponderAccess.NONE);
    }

    private static boolean get(Future<Boolean> future) {
        try { return future.get(10, TimeUnit.SECONDS); }
        catch (Exception exception) { throw new AssertionError("concurrent decision failed", exception); }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for test latch");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread was interrupted", exception);
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
