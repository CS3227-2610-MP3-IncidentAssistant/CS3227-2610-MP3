package com.company.incidentdesk.web.admin;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.Role;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Serializes each category update and commits its append-only version and audit atomically. */
@Repository
@ConditionalOnBean(DataSource.class)
class JdbcAdministratorSloStore implements AdministratorSloStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcAdministratorSloStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public ReadResult readAll(String administratorLoginName) {
        try {
            ReadResult result = transactions.execute(status -> {
                if (enabledAdministrator(administratorLoginName).isEmpty()) return ReadResult.unavailable();
                return new ReadResult(ReadOutcome.AVAILABLE, jdbc.query("""
                        SELECT id, category, average_time_to_claim_minutes,
                               average_time_in_progress_minutes, average_reopen_rate_percent,
                               effective_at, changed_by
                          FROM slo_target_versions
                         ORDER BY category ASC, effective_at ASC, id ASC
                        """, (row, index) -> mapVersion(row)));
            });
            return result == null ? ReadResult.persistenceFailure() : result;
        } catch (DataAccessException exception) {
            return ReadResult.persistenceFailure();
        }
    }

    @Override
    public ChangeOutcome change(
            String administratorLoginName,
            String category,
            UUID expectedVersionId,
            long timeToClaimMinutes,
            long timeInProgressMinutes,
            BigDecimal reopenRatePercent,
            UUID versionId,
            UUID auditEventId,
            Instant effectiveAt) {
        try {
            ChangeOutcome result = transactions.execute(status -> changeInTransaction(
                    administratorLoginName, category, expectedVersionId, timeToClaimMinutes,
                    timeInProgressMinutes, reopenRatePercent, versionId, auditEventId, effectiveAt));
            return result == null ? ChangeOutcome.PERSISTENCE_FAILURE : result;
        } catch (DataAccessException exception) {
            return ChangeOutcome.PERSISTENCE_FAILURE;
        }
    }

    private ChangeOutcome changeInTransaction(
            String administratorLoginName,
            String category,
            UUID expectedVersionId,
            long timeToClaimMinutes,
            long timeInProgressMinutes,
            BigDecimal reopenRatePercent,
            UUID versionId,
            UUID auditEventId,
            Instant effectiveAt) {
        List<ActorState> actors = enabledAdministrator(administratorLoginName);
        if (actors.size() != 1) return ChangeOutcome.RESOURCE_UNAVAILABLE;
        ActorState actor = actors.getFirst();

        lockCategory(category);
        SloVersion current = currentVersion(category);
        if (!matchesExpectedVersion(current, expectedVersionId)) return ChangeOutcome.CONFLICT;
        if (hasSameTarget(current, timeToClaimMinutes, timeInProgressMinutes, reopenRatePercent)) {
            return ChangeOutcome.UNCHANGED;
        }

        appendVersion(category, timeToClaimMinutes, timeInProgressMinutes, reopenRatePercent,
                versionId, effectiveAt, actor.id());
        appendAudit(category, current, versionId, auditEventId, effectiveAt, actor.id());
        return ChangeOutcome.CHANGED;
    }

    private void lockCategory(String category) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtext(?))")) {
                statement.setString(1, "slo:" + category);
                statement.execute();
            }
            return null;
        });
    }

    private SloVersion currentVersion(String category) {
        List<SloVersion> rows = jdbc.query("""
                SELECT id, category, average_time_to_claim_minutes,
                       average_time_in_progress_minutes, average_reopen_rate_percent,
                       effective_at, changed_by
                  FROM slo_target_versions
                 WHERE category = ?
                 ORDER BY effective_at DESC, id DESC
                 LIMIT 1
                """, (row, index) -> mapVersion(row), category);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static boolean matchesExpectedVersion(SloVersion current, UUID expectedVersionId) {
        return current == null ? expectedVersionId == null : current.id().equals(expectedVersionId);
    }

    private static boolean hasSameTarget(
            SloVersion current,
            long timeToClaimMinutes,
            long timeInProgressMinutes,
            BigDecimal reopenRatePercent) {
        return current != null
                && current.timeToClaimMinutes() == timeToClaimMinutes
                && current.timeInProgressMinutes() == timeInProgressMinutes
                && current.reopenRatePercent().compareTo(reopenRatePercent) == 0;
    }

    private void appendVersion(
            String category,
            long timeToClaimMinutes,
            long timeInProgressMinutes,
            BigDecimal reopenRatePercent,
            UUID versionId,
            Instant effectiveAt,
            UUID actorId) {
        jdbc.update("""
                INSERT INTO slo_target_versions (
                    id, category, average_time_to_claim_minutes,
                    average_time_in_progress_minutes, average_reopen_rate_percent,
                    effective_at, changed_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, versionId, category, timeToClaimMinutes, timeInProgressMinutes,
                reopenRatePercent, Timestamp.from(effectiveAt), actorId);
    }

    private void appendAudit(
            String category,
            SloVersion current,
            UUID versionId,
            UUID auditEventId,
            Instant effectiveAt,
            UUID actorId) {
        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, ?, 'ADMINISTRATOR', 'SLO_CONFIGURATION_CHANGED',
                    'SLO_CONFIGURATION', ?, 'SUCCESS',
                    jsonb_build_object('changes', jsonb_build_array(jsonb_build_object(
                        'field', 'SLO_VERSION', 'before', CAST(? AS text), 'after', CAST(? AS text)))))
                """, auditEventId, Timestamp.from(effectiveAt), actorId, category,
                current == null ? null : current.id().toString(), versionId.toString());
    }

    private List<ActorState> enabledAdministrator(String loginName) {
        return jdbc.query("""
                SELECT id, role, status
                  FROM accounts
                 WHERE login_name = ?
                 FOR SHARE
                """, (row, index) -> new ActorState(
                row.getObject("id", UUID.class), row.getString("role"), row.getString("status")), loginName)
                .stream().filter(ActorState::isEnabledAdministrator).toList();
    }

    private static SloVersion mapVersion(java.sql.ResultSet row) throws java.sql.SQLException {
        return new SloVersion(
                row.getObject("id", UUID.class), row.getString("category"),
                row.getLong("average_time_to_claim_minutes"),
                row.getLong("average_time_in_progress_minutes"),
                row.getBigDecimal("average_reopen_rate_percent"),
                row.getObject("effective_at", OffsetDateTime.class).toInstant(),
                row.getObject("changed_by", UUID.class));
    }

    private record ActorState(UUID id, String role, String status) {
        boolean isEnabledAdministrator() {
            return Role.ADMINISTRATOR.name().equals(role) && AccountStatus.ENABLED.name().equals(status);
        }
    }
}
