package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.company.incidentdesk.infrastructure.soclaas.AiUsageLedger.Feature;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Price;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Snapshot;

class AiGatewayConcurrencyPostgresTest extends AiGatewayPostgresFixture {
    @Test
    void transactionClockIsSampledOnlyAfterTheSingletonLockIsAcquired() throws Exception {
        var reads = new AtomicInteger();
        var trackingClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { reads.incrementAndGet(); return NOW; }
        };
        var control = new AiGatewayControl(jdbc, manager, trackingClock,
                AiUsagePolicy.Limits.from(new MockEnvironment()));
        try (var holder = source.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (var statement = holder.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertTrue(row.next());
                holderPid = row.getInt(1);
            }
            try (var statement = holder.createStatement();
                    var row = statement.executeQuery("SELECT id FROM ai_usage_control WHERE id=1 FOR UPDATE")) {
                assertTrue(row.next());
            }
            try (var worker = Executors.newSingleThreadExecutor()) {
                var result = worker.submit(control::recover);
                try {
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
                    boolean waiting = false;
                    while (!waiting && System.nanoTime() < deadline) {
                        waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                                SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                                    WHERE wait_event_type='Lock'
                                        AND query LIKE '%ai_usage_control%FOR UPDATE%'
                                        AND ? = ANY(pg_blocking_pids(pid)))
                                """, Boolean.class, holderPid));
                        if (!waiting) { Thread.sleep(10); }
                    }
                    assertTrue(waiting, "The gateway transaction must be observed waiting for the singleton lock");
                    assertEquals(0, reads.get(), "Waiting for a row lock must not sample the transaction clock");
                } finally { holder.commit(); }
                assertEquals(0, result.get(3, TimeUnit.SECONDS));
                assertEquals(1, reads.get(), "Nested ledger work must reuse the captured transaction instant");
            }
        }
    }

    @Test
    void simultaneousInstancesClaimExactlyOneRefreshLease() throws Exception {
        var results = race(() -> instance().claimRefresh(1));
        assertEquals(1, results.stream().filter(result -> result != null).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_control WHERE refresh_owner IS NOT NULL", Integer.class));
    }

    @Test
    void simultaneousInstancesAdmitExactlyOneHalfOpenProbe() throws Exception {
        var control = instance();
        control.publish(control.claimRefresh(1), 1, new Snapshot(NOW, 30, 50000000, 500000000,
                null, 0, 0, 0, new Price("test", 1000000, 1000000)));
        var first = control.admit(account("REPORTER"), Feature.REPORTER_DESCRIPTION, "test", 10, 10, 1);
        control.finish(first, null, new SoclaasException(SoclaasException.Category.AI_RATE_LIMITED, 429, 1L));
        var actors = new ArrayList<UUID>();
        for (int index = 0; index < 8; index++) { actors.add(account("REPORTER")); }
        clock.now = NOW.plusSeconds(2);
        var barrier = new CyclicBarrier(8);
        var attempts = new ArrayList<Callable<Attempt>>();
        for (UUID actor : actors) {
            attempts.add(() -> {
                var independent = instance();
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return new Attempt(independent.admit(actor, Feature.REPORTER_DESCRIPTION, "test", 10, 10, 1), null);
                } catch (AiUsageException denied) { return new Attempt(null, denied.category()); }
            });
        }
        var results = execute(attempts);
        assertEquals(1, results.stream().filter(result -> result.ticket() != null).count());
        assertEquals(7, results.stream().filter(result -> result.denial() == AiUsageException.Category.AI_RATE_LIMITED).count());
        var probe = results.stream().filter(result -> result.ticket() != null).findFirst().orElseThrow().ticket();
        assertNull(instance().finish(probe, new SoclaasClient.Usage(1, 1, 2), null));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT circuit_kind FROM ai_usage_control", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_requests", Integer.class));
    }

    private <T> List<T> race(Callable<T> operation) throws Exception {
        var barrier = new CyclicBarrier(8);
        var tasks = new ArrayList<Callable<T>>();
        for (int index = 0; index < 8; index++) {
            tasks.add(() -> { barrier.await(5, TimeUnit.SECONDS); return operation.call(); });
        }
        return execute(tasks);
    }

    private <T> List<T> execute(List<Callable<T>> tasks) throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            var pending = new ArrayList<Future<T>>();
            for (var task : tasks) { pending.add(workers.submit(task)); }
            var results = new ArrayList<T>();
            for (var result : pending) { results.add(result.get(10, TimeUnit.SECONDS)); }
            return results;
        }
    }

    private AiGatewayControl instance() {
        return new AiGatewayControl(jdbc, manager, clock, AiUsagePolicy.Limits.from(new MockEnvironment()));
    }

    private record Attempt(AiGatewayControl.Ticket ticket, AiUsageException.Category denial) { }
}
