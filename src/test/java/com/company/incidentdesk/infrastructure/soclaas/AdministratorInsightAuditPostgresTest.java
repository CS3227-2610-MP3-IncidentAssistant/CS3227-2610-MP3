package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.company.incidentdesk.infrastructure.soclaas.AiUsageLedger.Feature;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Price;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Snapshot;

/**
 * AI-ADM-SAFE-04 (audit and ledger half): an admitted Administrator insight request writes exactly
 * one content-free audit outcome and one bounded ledger row. Neither carries the question, plan,
 * result, selection, prompt, or provider response.
 */
class AdministratorInsightAuditPostgresTest extends AiGatewayPostgresFixture {
    private AiGatewayControl control;

    @BeforeEach
    void verifiedControl() {
        control = new AiGatewayControl(jdbc, manager, clock, AiUsagePolicy.Limits.from(new MockEnvironment()));
        UUID owner = control.claimRefresh(1);
        assertNotNull(owner);
        // Token prices are small enough that one admission stays inside the reserve ceiling.
        control.publish(owner, 1, new Snapshot(clock.instant(), 90, 1_000_000, 10_000_000, null, 0, 0, 0,
                new Price("test", 1000, 1000)));
    }

    @Test
    void administratorInsightAuditAndLedgerRowsAreContentFreeAndBounded() {
        UUID actor = account("ADMINISTRATOR");

        var ticket = control.admit(actor, Feature.ADMINISTRATOR_INSIGHTS, "test", 10, 10, 1);
        assertNull(control.finish(ticket, new SoclaasClient.Usage(3, 2, 5), null));

        assertEquals(1, audits("AI_REQUEST_ACCEPTED"), "exactly one admission audit");
        assertEquals(1, audits("AI_REQUEST_FINISHED"), "exactly one outcome audit");

        String details = jdbc.queryForObject(
                "SELECT details::text FROM audit_events WHERE action='AI_REQUEST_FINISHED'", String.class);
        assertTrue(details.contains("ADMINISTRATOR_INSIGHTS"), "the audit names the insight feature");
        assertTrue(details.contains("SUCCESS"), "the audit records the outcome category");
        for (String forbidden : new String[] {"question", "prompt", "response", "plan", "result",
            "responder", "metric", "period", "backlog"}) {
            assertFalse(details.toLowerCase(Locale.ROOT).contains(forbidden),
                    "the AI audit row must not contain " + forbidden);
        }

        assertEquals("ADMINISTRATOR", jdbc.queryForObject(
                "SELECT actor_role FROM audit_events WHERE action='AI_REQUEST_FINISHED'", String.class));
        assertEquals("AI_REQUEST", jdbc.queryForObject(
                "SELECT target_type FROM audit_events WHERE action='AI_REQUEST_FINISHED'", String.class));

        assertEquals("ADMINISTRATOR_INSIGHTS", jdbc.queryForObject(
                "SELECT feature FROM ai_usage_requests", String.class));

        // The ledger schema has no column able to hold insight content in the first place.
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_name = 'ai_usage_requests'
                   AND column_name IN ('question','prompt','response','plan','result','answer','details')
                """, Integer.class));
    }

    @Test
    void aFailedInsightOutcomeIsAuditedOnceWithoutProviderContent() {
        UUID actor = account("ADMINISTRATOR");

        var ticket = control.admit(actor, Feature.ADMINISTRATOR_INSIGHTS, "test", 10, 10, 1);
        control.finish(ticket, null, new SoclaasException(SoclaasException.Category.AI_INVALID_RESPONSE, 502, null));

        assertEquals(1, audits("AI_REQUEST_FINISHED"), "a failure is audited exactly once");
        String details = jdbc.queryForObject(
                "SELECT details::text FROM audit_events WHERE action='AI_REQUEST_FINISHED'", String.class);
        assertTrue(details.contains("AI_INVALID_RESPONSE"), "the audit records the failure category");
        assertFalse(details.toLowerCase(Locale.ROOT).contains("response\":"),
                "the audit must not embed a provider response body");
        assertEquals("FAILURE", jdbc.queryForObject(
                "SELECT outcome FROM audit_events WHERE action='AI_REQUEST_FINISHED'", String.class));
    }

    private int audits(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action=?", Integer.class, action);
    }
}
