package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.company.incidentdesk.infrastructure.soclaas.AiUsageException.Category;
import com.company.incidentdesk.infrastructure.soclaas.AiUsageLedger.Feature;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Limits;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Price;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Snapshot;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_URL", matches = ".+")
class AiUsageLedgerPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");
    private static final String SCHEMA = "ai_usage_test_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private final MutableClock clock = new MutableClock(NOW);
    private AiUsageLedger ledger;

    @BeforeAll
    static void migrateIsolatedSchema() {
        assertEquals("true", System.getenv("INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED"),
                "Explicit isolated database confirmation is required");
        var database = NeonDatabaseUrl.parse(System.getenv("INCIDENT_DESK_TEST_DATABASE_URL"));
        var adminSource = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        admin = new JdbcTemplate(adminSource);
        admin.execute("CREATE SCHEMA " + SCHEMA);
        String url = database.jdbcUrl() + (database.jdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        var source = new DriverManagerDataSource(url, database.username(), database.password());
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas(SCHEMA).defaultSchema(SCHEMA).load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
    }

    @AfterAll
    static void dropOnlyOwnedSchema() {
        if (admin != null) {
            admin.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM ai_usage_requests");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM ai_usage_control");
        jdbc.update("INSERT INTO ai_usage_control (id) VALUES (1)");
        ledger = service(new MockEnvironment());
        refresh(1000000, 10000000, null, 30);
    }

    @Test
    void accountHasOneInflightAcrossFeaturesAndNewInstances() {
        UUID actor = account();
        var reservation = admit(actor);
        denied(Category.AI_RATE_LIMITED, () -> service(new MockEnvironment())
                .admit(actor, Feature.RESPONDER_INCIDENT, "test", 10, 10));
        ledger.finish(reservation.id(), new SoclaasClient.Usage(3, 2, 5));
        assertNotNull(ledger.admit(actor, Feature.ADMINISTRATOR_TRENDS, "test", 10, 10));
        assertEquals(2, rows());
        assertEquals(5L, charge(reservation.id()));
    }

    @Test
    void accountMinuteBoundaryIsExactAndAllFeaturesShareCount() {
        UUID actor = account();
        for (int i = 0; i < 5; i++) {
            ledger.finish(admit(actor).id(), null);
        }
        clock.now = NOW.plusSeconds(59);
        denied(Category.AI_RATE_LIMITED, () -> admit(actor));
        clock.now = NOW.plusSeconds(60);
        assertNotNull(admit(actor));
    }

    @Test
    void accountHourBoundaryIsExact() {
        UUID actor = account();
        for (int i = 0; i < 30; i++) {
            clock.now = NOW.plusSeconds(i * 61L);
            refresh(1000000, 10000000, null, 30);
            ledger.finish(admit(actor).id(), null);
        }
        clock.now = NOW.plusSeconds(3599);
        refresh(1000000, 10000000, null, 30);
        denied(Category.AI_RATE_LIMITED, () -> admit(actor));
        clock.now = NOW.plusSeconds(3600);
        assertNotNull(admit(actor));
    }

    @Test
    void accountDayResetsAtUtcMidnight() {
        UUID actor = account();
        clock.now = Instant.parse("2026-10-19T00:00:00Z");
        jdbc.update("UPDATE ai_usage_control SET last_seen = NULL, verified_at = NULL");
        for (int i = 0; i < 100; i++) {
            clock.now = Instant.parse("2026-10-19T00:00:00Z").plusSeconds(i * 360L);
            refresh(1000000, 10000000, null, 30);
            ledger.finish(admit(actor).id(), null);
        }
        clock.now = Instant.parse("2026-10-19T23:59:59Z");
        refresh(1000000, 10000000, null, 30);
        denied(Category.AI_RATE_LIMITED, () -> admit(actor));
        clock.now = Instant.parse("2026-10-20T00:00:00Z");
        refresh(1000000, 10000000, null, 30);
        assertNotNull(admit(actor));
    }

    @Test
    void verifiedProviderRateReservesOneThirdAndIsAtomicAcrossInstances() throws Exception {
        int accepted = 0;
        for (int batch = 0; batch < 4; batch++) { accepted += compete(7, true); }
        assertEquals(20, accepted);
        assertEquals(20, rows());
        clock.now = NOW.plusSeconds(60);
        assertNotNull(admit(account()));
    }

    @Test
    void globalConcurrencyHasExactlyEightWinners() throws Exception {
        assertEquals(8, compete(16, false));
        assertEquals(8, rows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"daily", "monthly", "lifetime"})
    void simultaneousBudgetReservationsCannotSpendSameRemainingCapacity(String window) throws Exception {
        refresh(window.equals("daily") ? 27 : 1000000, window.equals("monthly") ? 27 : 10000000,
                window.equals("lifetime") ? 27L : null, 30); // 75% =20, one reservation costs20.
        assertEquals(1, compete(12, false));
        assertEquals(20L, jdbc.queryForObject("SELECT SUM(charged) FROM ai_usage_requests", Long.class));
    }

    @Test
    void monthlyAndLifetimeLimitsHaveIndependentDenialsAndZeroMeansNoCredit() {
        refresh(1000000, 27, null, 30);
        ledger.finish(admit(account()).id(), null);
        denied(Category.AI_BUDGET_EXHAUSTED, () -> admit(account()));
        clock.now = NOW.plusSeconds(1);
        refresh(1000000, 10000000, 0L, 30);
        denied(Category.AI_BUDGET_EXHAUSTED, () -> admit(account()));
        clock.now = NOW.plusSeconds(2);
        refresh(1000000, 10000000, 54L, 30);
        ledger.finish(admit(account()).id(), null);
        denied(Category.AI_BUDGET_EXHAUSTED, () -> admit(account()));
    }

    @Test
    void configuredLowerLimitsAreSharedEvenWhenAnotherInstanceHasDefaults() {
        var lower = service(new MockEnvironment().withProperty("AI_GLOBAL_REQUESTS_PER_MINUTE", "1"));
        lower.finish(lower.admit(account(), Feature.REPORTER_DESCRIPTION, "test", 10, 10).id(), null);
        denied(Category.AI_RATE_LIMITED, () -> admit(account()));
        assertEquals(1, rows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AI_DAILY_MICRODOLLARS", "AI_MONTHLY_MICRODOLLARS", "AI_LIFETIME_MICRODOLLARS"})
    void configuredDailyMonthlyAndLifetimeBudgetsWinOverProviderAllowance(String setting) {
        var lower = service(new MockEnvironment().withProperty(setting, "19"));
        denied(Category.AI_BUDGET_EXHAUSTED,
                () -> lower.admit(account(), Feature.REPORTER_DESCRIPTION, "test", 10, 10));
        denied(Category.AI_BUDGET_EXHAUSTED, () -> admit(account()));
        assertEquals(0, rows());
    }

    @Test
    void defaultGlobalRateCapsAtSixtyForProviderNinety() throws Exception {
        refresh(1000000, 10000000, null, 90);
        int accepted = 0;
        for (int batch = 0; batch < 9; batch++) { accepted += compete(7, true); }
        assertEquals(60, accepted);
        assertEquals(60, rows());
    }

    @Test
    void lockContentionReturnsSafeUnavailableWithinBoundedDeadline() throws Exception {
        UUID actor = account();
        try (var connection = manager.getDataSource().getConnection();
                var workers = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT id FROM ai_usage_control WHERE id = 1 FOR UPDATE");
            }
            var result = workers.submit(() -> assertThrows(AiUsageException.class, () -> admit(actor)));
            try {
                var failure = result.get(6, TimeUnit.SECONDS);
                assertEquals(Category.AI_UNAVAILABLE, failure.category());
                assertNull(failure.getCause());
                assertEquals("AI_UNAVAILABLE", failure.getMessage());
            } finally {
                connection.rollback();
            }
        }
        assertEquals(0, rows());
        assertNotNull(admit(actor));
    }

    @Test
    void databaseRejectsInvalidFeatureAndUnprovedRefund() {
        var reservation = admit(account());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE ai_usage_requests SET feature = 'arbitrary text' WHERE id = ?", reservation.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE ai_usage_requests SET charged = 0 WHERE id = ?", reservation.id()));
        assertEquals(20L, charge(reservation.id()));
    }

    @Test
    void missingStaleFutureOrWrongModelInputsFailClosedWithoutReservation() {
        jdbc.update("UPDATE ai_usage_control SET verified_at = NULL");
        denied(Category.AI_UNAVAILABLE, () -> admit(account()));
        refresh(1000000, 10000000, null, 30);
        clock.now = NOW.plusSeconds(900);
        denied(Category.AI_UNAVAILABLE, () -> admit(account()));
        denied(Category.AI_UNAVAILABLE, () -> ledger.refresh(new Snapshot(clock.now.plusSeconds(1),
                30, 100, 1000, null, 0, 0, 0, new Price("test", 1, 1))));
        assertEquals(0, rows());
    }

    @Test
    void modelMismatchDeniesWithoutReservation() {
        denied(Category.AI_UNAVAILABLE,
                () -> ledger.admit(account(), Feature.REPORTER_DESCRIPTION, "another-model", 10, 10));
        assertEquals(0, rows());
    }

    @Test
    void backwardClockFailsClosedAcrossInstances() {
        ledger.finish(admit(account()).id(), null);
        clock.now = NOW.minusSeconds(1);
        denied(Category.AI_UNAVAILABLE, () -> admit(account()));
    }

    @Test
    void ambiguityExcessUsageAndDuplicateSettlementNeverRefund() {
        for (SoclaasClient.Usage usage : new SoclaasClient.Usage[]{null,
                new SoclaasClient.Usage(11, 1, 12), new SoclaasClient.Usage(-1, 1, 0),
                new SoclaasClient.Usage(1, 1, 9), new SoclaasClient.Usage(1, 11, 12)}) {
            var reservation = admit(account());
            ledger.finish(reservation.id(), usage);
            ledger.finish(reservation.id(), new SoclaasClient.Usage(0, 0, 0));
            assertEquals(20L, charge(reservation.id()));
        }
        var good = admit(account());
        ledger.finish(good.id(), new SoclaasClient.Usage(3, 2, 5));
        ledger.finish(good.id(), new SoclaasClient.Usage(0, 0, 0));
        assertEquals(5L, charge(good.id()));
    }

    @Test
    void leaseRecoveryReleasesConcurrencyButNotBudgetAndLateSettlementIsInert() {
        UUID actor = account();
        var reservation = admit(actor);
        clock.now = NOW.plusSeconds(44);
        assertEquals(0, ledger.recoverExpired());
        clock.now = NOW.plusSeconds(45);
        assertEquals(1, service(new MockEnvironment()).recoverExpired());
        assertEquals(0, ledger.recoverExpired());
        ledger.finish(reservation.id(), new SoclaasClient.Usage(0, 0, 0));
        assertEquals(20L, charge(reservation.id()));
        assertNotNull(admit(actor));
    }

    @Test
    void utcSpanningReservationChargesBothDaysAndMonths() {
        clock.now = Instant.parse("2026-10-31T23:59:50Z");
        refresh(27, 27, null, 30);
        var reservation = admit(account());
        clock.now = Instant.parse("2026-11-01T00:00:00Z");
        denied(Category.AI_UNAVAILABLE, () -> admit(account()));
        refresh(27, 27, null, 30);
        denied(Category.AI_BUDGET_EXHAUSTED, () -> admit(account()));
        ledger.finish(reservation.id(), new SoclaasClient.Usage(10, 10, 20));
        denied(Category.AI_BUDGET_EXHAUSTED, () -> admit(account()));
        clock.now = Instant.parse("2026-11-02T00:00:00Z");
        refresh(27, 27, null, 30);
        denied(Category.AI_BUDGET_EXHAUSTED, () -> admit(account())); // November charge persists.
        clock.now = Instant.parse("2026-12-01T00:00:00Z");
        refresh(27, 27, null, 30);
        assertNotNull(admit(account()));
    }

    @Test
    void snapshotSpendIsConservativeAndRefreshCannotEraseLocalCharges() {
        ledger.finish(admit(account()).id(), null);
        clock.now = NOW.plusSeconds(1);
        ledger.refresh(new Snapshot(clock.now, 30, 54, 1000, null, 21, 21, 0,
                new Price("test", 1000000, 1000000)));
        denied(Category.AI_BUDGET_EXHAUSTED, () -> admit(account()));
        assertEquals(20L, jdbc.queryForObject("SELECT SUM(charged) FROM ai_usage_requests", Long.class));
    }

    @Test
    void failureRollsBackReservationAndRecoveryTogether() {
        var existing = admit(account());
        clock.now = NOW.plusSeconds(45);
        var duplicate = new AiUsageLedger(jdbc, manager, clock, Limits.from(new MockEnvironment()), existing::id);
        denied(Category.AI_UNAVAILABLE,
                () -> duplicate.admit(account(), Feature.REPORTER_DESCRIPTION, "test", 10, 10));
        assertEquals(1, rows());
        assertEquals("IN_FLIGHT", jdbc.queryForObject(
                "SELECT outcome FROM ai_usage_requests WHERE id = ?", String.class, existing.id()));
        assertEquals(1, ledger.recoverExpired());
    }

    @Test
    void admissionCommitsIndependentlyOfCallerRollback() {
        UUID actor = account();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            admit(actor);
            status.setRollbackOnly();
        });
        assertEquals(1, rows());
    }

    @Test
    void storedPricesCannotChangeAfterAdmissionAndLedgerHasNoContentColumns() {
        var reservation = admit(account());
        clock.now = NOW.plusSeconds(1);
        ledger.refresh(new Snapshot(clock.now, 30, 1000000, 10000000, null, 0, 0, 0,
                new Price("next-model", 1, 1)));
        ledger.finish(reservation.id(), new SoclaasClient.Usage(3, 2, 5));
        assertEquals(5L, charge(reservation.id()));
        var columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name LIKE 'ai_usage_%'", String.class, SCHEMA);
        assertTrue(columns.stream().noneMatch(name -> name.matches(".*(prompt|response|secret|content|key|name).*")));
        assertEquals(NOW, jdbc.queryForObject("SELECT admitted_at FROM ai_usage_requests", Timestamp.class).toInstant());
    }

    private int compete(int count, boolean finish) throws Exception {
        List<UUID> actors = new ArrayList<>();
        for (int i = 0; i < count; i++) { actors.add(account()); }
        var start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(count)) {
            for (UUID actor : actors) {
                futures.add(workers.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    var instance = service(new MockEnvironment());
                    try {
                        var reservation = instance.admit(actor, Feature.REPORTER_DESCRIPTION, "test", 10, 10);
                        if (finish) { instance.finish(reservation.id(), null); }
                        return true;
                    } catch (AiUsageException failure) {
                        assertTrue(failure.category() == Category.AI_RATE_LIMITED
                                || failure.category() == Category.AI_BUDGET_EXHAUSTED);
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : futures) { if (future.get(20, TimeUnit.SECONDS)) { accepted++; } }
            return accepted;
        }
    }

    private void refresh(long daily, long monthly, Long lifetime, int rpm) {
        ledger.refresh(new Snapshot(clock.instant(), rpm, daily, monthly, lifetime, 0, 0, 0,
                new Price("test", 1000000, 1000000)));
    }

    private AiUsageLedger service(MockEnvironment env) {
        return new AiUsageLedger(jdbc, manager, clock, Limits.from(env));
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, 'REPORTER', 'ENABLED')",
                id, id.toString());
        return id;
    }

    private AiUsageLedger.Reservation admit(UUID actor) {
        return ledger.admit(actor, Feature.REPORTER_DESCRIPTION, "test", 10, 10);
    }

    private long charge(UUID id) {
        return jdbc.queryForObject("SELECT charged FROM ai_usage_requests WHERE id = ?", Long.class, id);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_requests", Integer.class);
    }

    private static void denied(Category category, Runnable action) {
        assertEquals(category, assertThrows(AiUsageException.class, action::run).category());
    }

    private static final class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
