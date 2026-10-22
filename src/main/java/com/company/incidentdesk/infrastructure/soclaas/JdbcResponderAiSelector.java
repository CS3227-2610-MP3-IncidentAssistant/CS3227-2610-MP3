package com.company.incidentdesk.infrastructure.soclaas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.domain.incident.IncidentStatus;
import com.company.incidentdesk.infrastructure.soclaas.AiDataSelector.ResponderData.Stage;
import com.company.incidentdesk.infrastructure.soclaas.AiDataSelector.ResponderData.SloState;

/** Consistent, privacy-minimized read for the Responder's current detail-view access. */
@Repository
@ConditionalOnProperty(name = "AI_ENABLED", havingValue = "true")
class JdbcResponderAiSelector implements AiDataSelector {
    private static final String ACCESS = """
            ((i.status = 'SUBMITTED' AND i.assignee_id IS NULL AND EXISTS (
                SELECT 1 FROM responder_category_access access
                 WHERE access.account_id = :actor AND access.category = i.category))
             OR (i.status = 'ASSIGNED' AND i.assignee_id = :actor))
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    @Autowired
    JdbcResponderAiSelector(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, Clock.systemUTC());
    }

    JdbcResponderAiSelector(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Optional<ResponderData> responder(UUID actor, UUID incident) {
        Instant now = clock.instant();
        var transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> select(actor, incident, now));
    }

    private Optional<ResponderData> select(UUID actor, UUID incident, Instant now) {
        List<ResponderData> selected = jdbc.query("""
                SELECT i.id, i.category, i.title, i.description, i.status, i.created_at,
                       i.queue_entered_at, i.first_assigned_at,
                       (i.first_assigned_at IS NOT NULL) AS already_claimed,
                       target.average_time_to_claim_minutes AS claim_minutes,
                       target.average_time_in_progress_minutes AS progress_minutes
                  FROM incidents i
                  JOIN accounts responder ON responder.id = :actor
                   AND responder.role = 'RESPONDER' AND responder.status = 'ENABLED'
                  LEFT JOIN LATERAL (
                      SELECT average_time_to_claim_minutes, average_time_in_progress_minutes
                        FROM slo_target_versions version
                       WHERE version.category = i.category AND version.effective_at <= i.queue_entered_at
                       ORDER BY version.effective_at DESC, version.id ASC LIMIT 1
                  ) target ON TRUE
                 WHERE i.id = :incident AND """ + ACCESS,
                Map.of("actor", actor, "incident", incident), (row, index) -> {
                    IncidentStatus state = IncidentStatus.valueOf(row.getString("status"));
                    Stage stage = state == IncidentStatus.SUBMITTED ? Stage.QUEUE : Stage.IN_PROGRESS;
                    OffsetDateTime stamp = row.getObject(stage == Stage.QUEUE ? "queue_entered_at" : "first_assigned_at",
                            OffsetDateTime.class);
                    if (stamp == null || stamp.toInstant().isAfter(now)) { throw rejected(); }
                    long elapsed = Duration.between(stamp.toInstant(), now).getSeconds();
                    String targetColumn = stage == Stage.QUEUE ? "claim_minutes" : "progress_minutes";
                    Number minutes = (Number) row.getObject(targetColumn);
                    boolean claimed = row.getBoolean("already_claimed");
                    SloState slo = sloState(stage, claimed, minutes, elapsed);
                    return new ResponderData(row.getObject("id", UUID.class),
                            IncidentCategory.valueOf(row.getString("category")), row.getString("title"),
                            row.getString("description"), state,
                            row.getObject("created_at", OffsetDateTime.class).toInstant(), stage, elapsed, slo);
                });
        return selected.stream().findFirst();
    }

    @Override
    public boolean responderMayView(UUID actor, UUID incident) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM incidents i JOIN accounts responder ON responder.id = :actor
                    AND responder.role = 'RESPONDER' AND responder.status = 'ENABLED'
                    WHERE i.id = :incident AND """ + ACCESS + ")",
                Map.of("actor", actor, "incident", incident), Boolean.class));
    }

    private static SoclaasException rejected() {
        return new SoclaasException(SoclaasException.Category.AI_REJECTED);
    }

    private static SloState sloState(Stage stage, boolean claimed, Number targetMinutes, long elapsedSeconds) {
        if (targetMinutes == null || (stage == Stage.QUEUE && claimed)) return SloState.NOT_APPLICABLE;
        long elapsedMinutes = elapsedSeconds / 60;
        boolean overdue = elapsedMinutes > targetMinutes.longValue()
                || (elapsedMinutes == targetMinutes.longValue() && elapsedSeconds % 60 > 0);
        return overdue ? SloState.OVERDUE : SloState.WITHIN_TARGET;
    }
}
