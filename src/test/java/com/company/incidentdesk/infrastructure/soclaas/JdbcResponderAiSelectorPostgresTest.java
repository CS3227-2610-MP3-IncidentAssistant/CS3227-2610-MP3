package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.company.incidentdesk.infrastructure.soclaas.AiDataSelector.ResponderData.Stage;
import com.company.incidentdesk.infrastructure.soclaas.AiDataSelector.ResponderData.SloState;

import tools.jackson.databind.json.JsonMapper;

/** Uses the fixture's synthetic, isolated PostgreSQL schema; never connects to production. */
@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_URL", matches = ".+")
class JdbcResponderAiSelectorPostgresTest extends AiGatewayPostgresFixture {
    @Test
    void authorizationAndCurrentCycleStageUseProspectiveTargetAndNoIdentity() {
        UUID reporter = account("REPORTER");
        UUID responder = account("RESPONDER");
        UUID other = account("RESPONDER");
        UUID admin = account("ADMINISTRATOR");
        UUID incident = UUID.randomUUID();
        jdbc.update("INSERT INTO responder_category_access(account_id,category,granted_at) VALUES (?,'IT',?)",
                responder, Timestamp.from(NOW.minusSeconds(1000)));
        jdbc.update("""
                INSERT INTO incidents(id,reporter_id,title,description,category,status,anonymous,created_at,
                                      submitted_at,queue_entered_at)
                VALUES (?,?,'Synthetic title','Synthetic description','IT','SUBMITTED',true,?,?,?)
                """, incident, reporter, Timestamp.from(NOW.minusSeconds(700)),
                Timestamp.from(NOW.minusSeconds(600)), Timestamp.from(NOW.minusSeconds(600)));
        jdbc.update("""
                INSERT INTO slo_target_versions(id,category,average_time_to_claim_minutes,
                  average_time_in_progress_minutes,average_reopen_rate_percent,effective_at,changed_by)
                VALUES (?,'IT',5,2,10,?,?)
                """, UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(800)), admin);
        var selector = new JdbcResponderAiSelector(new NamedParameterJdbcTemplate(source), manager, clock);

        assertFalse(selector.responder(other, incident).isPresent());
        assertFalse(selector.responderMayView(other, incident));
        var queued = selector.responder(responder, incident).orElseThrow();
        assertEquals(Stage.QUEUE, queued.stage());
        assertEquals(600, queued.stageElapsedSeconds());
        assertEquals(SloState.OVERDUE, queued.sloState());
        var payload = JsonMapper.builder().build().readTree(AiPromptBuilder.prepare(
                new AiRequest.ResponderIncident(incident), responder, selector).data());
        assertEquals(9, payload.size());
        assertEquals("Synthetic description", payload.path("description").asString());
        assertFalse(payload.has("reporterId"));
        assertFalse(payload.has("assigneeId"));
        assertFalse(payload.has("comments"));

        jdbc.update("""
                INSERT INTO slo_target_versions(id,category,average_time_to_claim_minutes,
                  average_time_in_progress_minutes,average_reopen_rate_percent,effective_at,changed_by)
                VALUES (?,'IT',100,100,10,?,?)
                """, UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(300)), admin);
        assertEquals(SloState.OVERDUE, selector.responder(responder, incident).orElseThrow().sloState());

        jdbc.update("UPDATE incidents SET status='ASSIGNED',assignee_id=?,first_assigned_at=? WHERE id=?",
                responder, Timestamp.from(NOW.minusSeconds(180)), incident);
        jdbc.update("DELETE FROM responder_category_access WHERE account_id=?", responder);
        assertTrue(selector.responderMayView(responder, incident));
        var assigned = selector.responder(responder, incident).orElseThrow();
        assertEquals(Stage.IN_PROGRESS, assigned.stage());
        assertEquals(180, assigned.stageElapsedSeconds());
        assertEquals(SloState.OVERDUE, assigned.sloState());

        jdbc.update("UPDATE incidents SET status='SUBMITTED',assignee_id=NULL WHERE id=?", incident);
        jdbc.update("INSERT INTO responder_category_access(account_id,category,granted_at) VALUES (?,'IT',?)",
                responder, Timestamp.from(NOW));
        assertEquals(SloState.NOT_APPLICABLE, selector.responder(responder, incident).orElseThrow().sloState());
        jdbc.update("UPDATE incidents SET queue_entered_at=? WHERE id=?", Timestamp.from(NOW.plusSeconds(1)), incident);
        assertThrows(SoclaasException.class, () -> selector.responder(responder, incident));

        jdbc.update("UPDATE incidents SET queue_entered_at=?,first_assigned_at=NULL WHERE id=?",
                Timestamp.from(NOW.minusSeconds(100)), incident);
        var reopened = selector.responder(responder, incident).orElseThrow();
        assertEquals(SloState.WITHIN_TARGET, reopened.sloState());
        assertEquals(100, reopened.stageElapsedSeconds());
    }
}
