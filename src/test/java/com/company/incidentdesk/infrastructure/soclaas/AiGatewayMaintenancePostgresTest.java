package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiGatewayMaintenancePostgresTest extends AiGatewayPostgresFixture {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    @org.junit.jupiter.api.AfterEach void closeMetrics() { metrics.close(); }
    @Test
    void refreshesVerifiedModelAndBudgetOnceAcrossInstancesThenAtFiveMinutesAndUtcRollover() throws Exception {
        var settings = settings(1);
        var control = new AiGatewayControl(jdbc, manager, clock, settings.usageLimits());
        AtomicInteger reads = new AtomicInteger();
        try (var provider = new AiGatewayFakeProvider(); var client = new SoclaasClient(settings, provider.origin());
                var first = new AiGatewayMaintenance(settings, control, client, () -> {
                    reads.incrementAndGet(); return budget();
                }, clock, metrics);
                var second = new AiGatewayMaintenance(settings, control, client, () -> {
                    reads.incrementAndGet(); return budget();
                }, clock, metrics)) {
            first.runOnce();
            second.runOnce();
            assertEquals(1, reads.get());
            assertEquals(30, jdbc.queryForObject("SELECT provider_minute FROM ai_usage_control", Integer.class));
            assertEquals(50000000, jdbc.queryForObject("SELECT daily_allowance FROM ai_usage_control", Long.class));
            clock.now = NOW.plusSeconds(300);
            second.runOnce();
            assertEquals(2, reads.get());
            clock.now = java.time.Instant.parse("2026-10-20T00:00:00Z");
            first.runOnce();
            assertEquals(3, reads.get());
            assertEquals(clock.now, jdbc.queryForObject("SELECT verified_at FROM ai_usage_control", java.sql.Timestamp.class).toInstant());
        }
    }

    @Test
    void failedRefreshDisablesAdmissionsAndRevokedKeyRequiresHigherVerifiedRevision() throws Exception {
        var settings = settings(1);
        var control = new AiGatewayControl(jdbc, manager, clock, settings.usageLimits());
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<SoclaasException> error = new AtomicReference<>(new SoclaasException(SoclaasException.Category.AI_REJECTED, 403, null));
        try (var provider = new AiGatewayFakeProvider(); var client = new SoclaasClient(settings, provider.origin());
                var first = new AiGatewayMaintenance(settings, control, client, () -> {
                    reads.incrementAndGet(); if (error.get() != null) { throw error.get(); } return budget();
                }, clock, metrics);
                var repaired = new AiGatewayMaintenance(settings(2), control, client, this::budget, clock, metrics)) {
            first.runOnce();
            assertNull(jdbc.queryForObject("SELECT verified_at FROM ai_usage_control", java.sql.Timestamp.class));
            assertEquals("BLOCKED", jdbc.queryForObject("SELECT circuit_kind FROM ai_usage_control", String.class));
            first.runOnce();
            assertEquals(1, reads.get());
            error.set(null);
            repaired.runOnce();
            assertEquals("CLOSED", jdbc.queryForObject("SELECT circuit_kind FROM ai_usage_control", String.class));
            assertEquals(2L, jdbc.queryForObject("SELECT configuration_revision FROM ai_usage_control", Long.class));
            assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
        }
    }

    @Test
    void malformedMetadataIsContainedAndDoesNotKillLaterRefresh() throws Exception {
        var settings = settings(1);
        var control = new AiGatewayControl(jdbc, manager, clock, settings.usageLimits());
        AtomicInteger reads = new AtomicInteger();
        try (var provider = new AiGatewayFakeProvider(); var client = new SoclaasClient(settings, provider.origin());
                var task = new AiGatewayMaintenance(settings, control, client, () -> {
                    if (reads.getAndIncrement() == 0) { throw new IllegalArgumentException("synthetic-sensitive-provider-body"); }
                    return budget();
                }, clock, metrics)) {
            assertDoesNotThrow(task::runOnce);
            assertEquals("TRANSIENT", jdbc.queryForObject("SELECT circuit_kind FROM ai_usage_control", String.class));
            assertNull(jdbc.queryForObject("SELECT verified_at FROM ai_usage_control", java.sql.Timestamp.class));
            clock.now = NOW.plusSeconds(31);
            task.runOnce();
            assertNotNull(jdbc.queryForObject("SELECT verified_at FROM ai_usage_control", java.sql.Timestamp.class));
            assertEquals(2, reads.get());
        }
    }

    private SoclaasBudgetClient.Budget budget() {
        return new SoclaasBudgetClient.Budget(clock.instant(), 30, 50000000, 500000000, java.util.OptionalLong.empty(), 36, 36);
    }
    private SoclaasSettings settings(int revision) {
        return SoclaasSettings.from(new MockEnvironment().withProperty("AI_ENABLED", "true")
                .withProperty("SOCLAAS_API_KEY", "synthetic-secret").withProperty("SOCLAAS_MODEL", "test")
                .withProperty("AI_CONFIGURATION_REVISION", Integer.toString(revision)));
    }
}
