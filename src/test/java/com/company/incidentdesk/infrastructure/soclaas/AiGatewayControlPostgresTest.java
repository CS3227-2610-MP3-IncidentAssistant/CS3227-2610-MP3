package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.company.incidentdesk.infrastructure.soclaas.AiUsageLedger.Feature;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Price;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Snapshot;
import com.company.incidentdesk.infrastructure.soclaas.SoclaasException.Category;

class AiGatewayControlPostgresTest extends AiGatewayPostgresFixture {
    private AiGatewayControl control;

    @BeforeEach
    void verifiedControl() {
        control = instance();
        publish(1);
    }

    @Test
    void admissionAndOutcomeAuditAreContentFreeAndIdempotent() {
        UUID actor = account("REPORTER");
        var ticket = admit(actor);
        assertEquals(1, audits("AI_REQUEST_ACCEPTED"));
        assertNull(control.finish(ticket, new SoclaasClient.Usage(3, 2, 5), null));
        assertNull(control.finish(ticket, null, new SoclaasException(Category.AI_REJECTED, 401, null)));
        assertEquals(1, audits("AI_REQUEST_FINISHED"));
        assertEquals(5L, jdbc.queryForObject("SELECT charged FROM ai_usage_requests", Long.class));
        assertEquals("CLOSED", circuit());
        assertEquals("REPORTER", jdbc.queryForObject("SELECT actor_role FROM audit_events LIMIT 1", String.class));
        assertEquals(actor, jdbc.queryForObject("SELECT actor_account_id FROM audit_events LIMIT 1", UUID.class));
        String details = jdbc.queryForObject("SELECT details::text FROM audit_events WHERE action='AI_REQUEST_FINISHED'", String.class);
        assertTrue(details.contains("SUCCESS"));
        assertTrue(details.contains("REPORTER_DESCRIPTION"));
        assertFalse(details.contains("prompt"));
        assertFalse(details.contains("response"));
    }

    @Test
    void roleIsRecheckedFromDatabaseAndRejectedWithoutAdmission() {
        UUID actor = account("REPORTER");
        assertEquals(AiUsageException.Category.AI_REJECTED, assertThrows(AiUsageException.class,
                () -> control.admit(actor, Feature.ADMINISTRATOR_TRENDS, "test", 10, 10, 1)).category());
        jdbc.update("UPDATE accounts SET status='DISABLED' WHERE id=?", actor);
        assertThrows(AiUsageException.class, () -> admit(actor));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_requests", Integer.class));
        assertEquals(0, audits("AI_REQUEST_ACCEPTED"));
    }

    @Test
    void failedAdmissionAuditRollsBackReservation() {
        UUID actor = account("REPORTER");
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT reject_test_accept CHECK(action <> 'AI_REQUEST_ACCEPTED')");
        try {
            assertThrows(AiUsageException.class, () -> admit(actor));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_requests", Integer.class));
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT reject_test_accept"); }
    }

    @Test
    void failedOutcomeAuditRollsBackSettlementAndCircuitTogether() {
        var ticket = admit(account("REPORTER"));
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT reject_test_finish CHECK(action <> 'AI_REQUEST_FINISHED')");
        try {
            assertThrows(AiUsageException.class,
                    () -> control.finish(ticket, null, new SoclaasException(Category.AI_REJECTED, 401, null)));
            assertEquals("IN_FLIGHT", jdbc.queryForObject("SELECT outcome FROM ai_usage_requests", String.class));
            assertEquals("CLOSED", circuit());
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT reject_test_finish"); }
    }

    @Test
    void keyRejectionBlocksAcrossInstancesUntilHigherVerifiedRevision() {
        control.finish(admit(account("REPORTER")), null, new SoclaasException(Category.AI_REJECTED, 403, null));
        assertEquals("BLOCKED", circuit());
        clock.now = NOW.plusSeconds(2000);
        assertNull(instance().claimRefresh(1));
        assertThrows(AiUsageException.class, () -> admit(account("REPORTER")));
        publish(2);
        assertEquals("CLOSED", circuit());
        assertThrows(AiUsageException.class, () -> admit(account("REPORTER")));
        assertNotNull(control.admit(account("REPORTER"), Feature.REPORTER_DESCRIPTION, "test", 10, 10, 2));
    }

    @Test
    void rateCircuitUsesBoundedDelayAndOnlyOneHalfOpenProbe() {
        control.finish(admit(account("REPORTER")), null, new SoclaasException(Category.AI_RATE_LIMITED, 429, 7L));
        clock.now = NOW.plusSeconds(6);
        assertThrows(AiUsageException.class, () -> instance().admit(account("REPORTER"), Feature.REPORTER_DESCRIPTION,
                "test", 10, 10, 1));
        clock.now = NOW.plusSeconds(7);
        var probe = admit(account("REPORTER"));
        assertThrows(AiUsageException.class, () -> admit(account("REPORTER")));
        control.finish(probe, new SoclaasClient.Usage(1, 1, 2), null);
        assertEquals("CLOSED", circuit());
        assertNotNull(admit(account("REPORTER")));
    }

    @Test
    void zeroRetryAfterFallsBackToFiveMinutes() {
        control.finish(admit(account("REPORTER")), null, new SoclaasException(Category.AI_RATE_LIMITED, 429, 0L));
        clock.now = NOW.plusSeconds(299);
        assertThrows(AiUsageException.class, () -> admit(account("REPORTER")));
        clock.now = NOW.plusSeconds(300);
        assertNotNull(admit(account("REPORTER")));
    }

    @Test
    void olderShorterRateFailureCannotShortenAnOpenCircuit() {
        var first = admit(account("REPORTER"));
        var second = admit(account("REPORTER"));
        control.finish(first, null, new SoclaasException(Category.AI_RATE_LIMITED, 429, 10L));
        control.finish(second, null, new SoclaasException(Category.AI_RATE_LIMITED, 429, 1L));
        clock.now = NOW.plusSeconds(1);
        assertThrows(AiUsageException.class, () -> admit(account("REPORTER")));
    }

    @Test
    void failedRevisionRepairDoesNotAutomaticallyRetryThatBlockedRevision() {
        control.finish(admit(account("REPORTER")), null, new SoclaasException(Category.AI_REJECTED, 401, null));
        UUID owner = control.claimRefresh(2);
        assertNotNull(owner);
        control.failRefresh(owner, new SoclaasException(Category.AI_REJECTED, 403, null));
        assertNull(control.claimRefresh(2));
        assertNotNull(control.claimRefresh(3));
    }

    @Test
    void olderFailureCannotReleaseAnActiveHalfOpenProbe() {
        var first = admit(account("REPORTER"));
        var older = admit(account("REPORTER"));
        control.finish(first, null, new SoclaasException(Category.AI_RATE_LIMITED, 429, 1L));
        clock.now = NOW.plusSeconds(1);
        var probe = admit(account("REPORTER"));
        clock.now = NOW.plusSeconds(2);
        control.finish(older, null, new SoclaasException(Category.AI_RATE_LIMITED, 429, 1L));
        clock.now = NOW.plusSeconds(3);
        assertThrows(AiUsageException.class, () -> admit(account("REPORTER")));
        control.finish(probe, new SoclaasClient.Usage(1, 1, 2), null);
        assertNotNull(admit(account("REPORTER")));
    }

    @Test
    void recoveredTimeoutsAlsoTripCircuitAndAuditFailureRollsBackRecovery() {
        for (int i = 0; i < 3; i++) { admit(account("REPORTER")); }
        clock.now = NOW.plusSeconds(45);
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT reject_test_recovery CHECK(action <> 'AI_REQUEST_FINISHED')");
        try {
            assertThrows(AiUsageException.class, () -> control.recover());
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_requests WHERE outcome='IN_FLIGHT'", Integer.class));
            assertEquals("CLOSED", circuit());
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT reject_test_recovery"); }
        assertEquals(3, control.recover());
        assertEquals("TRANSIENT", circuit());
        assertEquals(3, audits("AI_REQUEST_FINISHED"));
    }

    @Test
    void transientFailuresOpenCircuitAndOlderSuccessCannotCloseIt() {
        var old = admit(account("REPORTER"));
        for (int i = 0; i < 3; i++) {
            control.finish(admit(account("REPORTER")), null, new SoclaasException(Category.AI_UNAVAILABLE, 503, null));
        }
        control.finish(old, new SoclaasClient.Usage(1, 1, 2), null);
        assertEquals("TRANSIENT", circuit());
        assertThrows(AiUsageException.class, () -> admit(account("REPORTER")));
        clock.now = NOW.plusSeconds(30);
        var probe = admit(account("REPORTER"));
        assertThrows(AiUsageException.class, () -> admit(account("REPORTER")));
        control.finish(probe, new SoclaasClient.Usage(1, 1, 2), null);
        assertEquals("CLOSED", circuit());
    }

    @Test
    void refreshLeaseIsSharedAndExpiredPublisherCannotOverwriteNewOwner() {
        clock.now = NOW.plusSeconds(300);
        UUID owner = control.claimRefresh(1);
        assertNotNull(owner);
        assertNull(instance().claimRefresh(1));
        clock.now = clock.now.plusSeconds(60);
        UUID replacement = instance().claimRefresh(1);
        assertNotNull(replacement);
        assertThrows(AiUsageException.class, () -> control.publish(owner, 1, snapshot()));
        instance().publish(replacement, 1, snapshot());
        assertNull(control.claimRefresh(1));
    }

    @Test
    void expiredOutcomeIsAuditedOnceWithoutRefund() {
        var ticket = admit(account("REPORTER"));
        clock.now = NOW.plusSeconds(45);
        assertEquals(1, instance().recover());
        assertEquals(0, control.recover());
        assertEquals(Category.AI_TIMEOUT, control.finish(ticket, new SoclaasClient.Usage(0, 0, 0), null));
        assertEquals(1, audits("AI_REQUEST_FINISHED"));
        assertEquals(20L, jdbc.queryForObject("SELECT charged FROM ai_usage_requests", Long.class));
    }

    @Test
    void nestedLedgerSettlementUsesTheSameCapturedTransactionInstant() {
        var ticket = admit(account("REPORTER"));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var boundary = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() {
                return NOW.plusSeconds(reads.getAndIncrement() == 0 ? 44 : 45);
            }
        };
        var independent = new AiGatewayControl(jdbc, manager, boundary, AiUsagePolicy.Limits.from(new MockEnvironment()));
        assertNull(independent.finish(ticket, new SoclaasClient.Usage(1, 1, 2), null));
        assertEquals(1, reads.get());
        assertEquals(0, jdbc.queryForObject("SELECT transient_failures FROM ai_usage_control", Integer.class));
        assertEquals(2L, jdbc.queryForObject("SELECT charged FROM ai_usage_requests", Long.class));
    }

    @Test
    void metadataFailureInvalidatesAdmissionWithoutBlockingDatabaseWork() {
        clock.now = NOW.plusSeconds(300);
        UUID owner = control.claimRefresh(1);
        control.failRefresh(owner, new SoclaasException(Category.AI_UNAVAILABLE));
        assertThrows(AiUsageException.class, () -> admit(account("REPORTER")));
        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
    }

    private AiGatewayControl instance() {
        return new AiGatewayControl(jdbc, manager, clock, AiUsagePolicy.Limits.from(new MockEnvironment()));
    }

    private void publish(long revision) {
        UUID owner = control.claimRefresh(revision);
        assertNotNull(owner);
        control.publish(owner, revision, snapshot());
    }

    private Snapshot snapshot() {
        return new Snapshot(clock.instant(), 90, 1000000, 10000000, null, 0, 0, 0,
                new Price("test", 1000000, 1000000));
    }

    private AiGatewayControl.Ticket admit(UUID account) {
        return control.admit(account, Feature.REPORTER_DESCRIPTION, "test", 10, 10, 1);
    }

    private String circuit() {
        return jdbc.queryForObject("SELECT circuit_kind FROM ai_usage_control", String.class);
    }

    private int audits(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action=?", Integer.class, action);
    }
}
