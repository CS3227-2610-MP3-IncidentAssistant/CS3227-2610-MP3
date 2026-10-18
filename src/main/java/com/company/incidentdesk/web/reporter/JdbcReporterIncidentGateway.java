package com.company.incidentdesk.web.reporter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.incident.Incident;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.IncidentPage;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.IncidentRow;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.ListCriteria;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL implementation that scopes every query to the current Reporter login. */
@Repository
@ConditionalOnBean(DataSource.class)
public class JdbcReporterIncidentGateway implements ReporterIncidentGateway {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transactions;

    public JdbcReporterIncidentGateway(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.transactions = transactions;
    }

    @Override
    public Optional<AccountId> findEnabledReporter(String loginName) {
        try {
            return jdbc.query("""
                    SELECT id
                      FROM accounts
                     WHERE login_name = ? AND role = 'REPORTER' AND status = 'ENABLED'
                    """, (result, row) -> new AccountId(result.getObject("id", UUID.class)), loginName)
                    .stream().findFirst();
        } catch (DataAccessException exception) {
            throw new ReporterDataAccessException(exception);
        }
    }

    @Override
    public IncidentPage findOwned(String loginName, ListCriteria criteria) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("loginName", loginName);
        parameters.put("limit", criteria.size());
        parameters.put("offset", Math.multiplyExact(criteria.page(), criteria.size()));
        String searchPredicate = "";
        if (!criteria.search().isEmpty()) {
            searchPredicate = " AND (i.title ILIKE :search OR i.description ILIKE :search OR CAST(i.id AS text) ILIKE :search)";
            parameters.put("search", "%" + criteria.search() + "%");
        }
        String ownerJoin = """
                 FROM incidents i
                 JOIN accounts reporter ON reporter.id = i.reporter_id
                """;
        String ownerPredicate = """
                WHERE reporter.login_name = :loginName
                  AND reporter.role = 'REPORTER'
                  AND reporter.status = 'ENABLED'
                """ + searchPredicate;
        try {
            Long count = namedJdbc.queryForObject(
                    "SELECT COUNT(*)" + ownerJoin + ownerPredicate, parameters, Long.class);
            var rows = namedJdbc.query("""
                    SELECT i.id, i.title, i.category, i.status, i.anonymous,
                           i.created_at, i.submitted_at, assignee.login_name AS assignee_login,
                           i.version
                    """ + ownerJoin + """
                     LEFT JOIN accounts assignee ON assignee.id = i.assignee_id
                    """ + ownerPredicate + """
                     ORDER BY i.created_at DESC, i.id ASC
                     LIMIT :limit OFFSET :offset
                    """, parameters, (result, row) -> mapRow(result));
            long total = count == null ? 0 : count;
            long totalPages = Math.max(1, (total + criteria.size() - 1) / criteria.size());
            return new IncidentPage(rows, criteria.page(), criteria.size(), total, totalPages);
        } catch (DataAccessException exception) {
            throw new ReporterDataAccessException(exception);
        }
    }

    @Override
    public SubmissionResult submit(
            String loginName,
            UUID submissionKey,
            Incident incident,
            UUID auditEventId) {
        try {
            return Objects.requireNonNull(
                    transactions.execute(status -> persist(loginName, submissionKey, incident, auditEventId)),
                    "transaction result");
        } catch (DataAccessException ignored) {
            return SubmissionResult.unavailable();
        }
    }

    private SubmissionResult persist(
            String loginName,
            UUID submissionKey,
            Incident incident,
            UUID auditEventId) {
        Integer authorized = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM accounts
                 WHERE id = ? AND login_name = ? AND role = 'REPORTER' AND status = 'ENABLED'
                """, Integer.class, incident.reporterId().value(), loginName);
        if (authorized == null || authorized != 1) {
            return SubmissionResult.denied();
        }

        int inserted = jdbc.update("""
                INSERT INTO incidents (
                    id, reporter_id, title, description, category, status, anonymous,
                    created_at, submitted_at, queue_entered_at, version, submission_key
                ) VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?, ?, 0, ?)
                ON CONFLICT (reporter_id, submission_key) WHERE submission_key IS NOT NULL DO NOTHING
                """,
                incident.id().value(), incident.reporterId().value(), incident.title(), incident.description(),
                incident.category().name(), incident.anonymous(), Timestamp.from(incident.createdAt()),
                Timestamp.from(incident.submittedAt().orElseThrow()),
                Timestamp.from(incident.currentCycle().orElseThrow().queueEnteredAt()), submissionKey);
        if (inserted == 0) {
            return SubmissionResult.duplicate(findBySubmissionKey(incident.reporterId(), submissionKey));
        }

        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, ?, 'REPORTER', 'INCIDENT_SUBMITTED', 'INCIDENT', ?, 'SUCCESS',
                          jsonb_build_object('status', 'SUBMITTED', 'anonymous', ?))
                """,
                auditEventId, Timestamp.from(incident.submittedAt().orElseThrow()), incident.reporterId().value(),
                incident.id().value().toString(), incident.anonymous());
        return SubmissionResult.created(toRow(incident));
    }

    private IncidentRow findBySubmissionKey(AccountId reporterId, UUID submissionKey) {
        return jdbc.queryForObject("""
                SELECT i.id, i.title, i.category, i.status, i.anonymous,
                       i.created_at, i.submitted_at, assignee.login_name AS assignee_login,
                       i.version
                  FROM incidents i
             LEFT JOIN accounts assignee ON assignee.id = i.assignee_id
                 WHERE i.reporter_id = ? AND i.submission_key = ?
                """, (result, row) -> mapRow(result), reporterId.value(), submissionKey);
    }

    private static IncidentRow mapRow(ResultSet result) throws SQLException {
        OffsetDateTime submittedAt = result.getObject("submitted_at", OffsetDateTime.class);
        return new IncidentRow(
                result.getObject("id", UUID.class),
                result.getString("title"),
                result.getString("category"),
                result.getString("status"),
                result.getBoolean("anonymous"),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                submittedAt == null ? null : submittedAt.toInstant(),
                result.getString("assignee_login"),
                result.getLong("version"));
    }

    private static IncidentRow toRow(Incident incident) {
        return new IncidentRow(
                incident.id().value(), incident.title(), incident.category().name(), incident.status().name(),
                incident.anonymous(), incident.createdAt(), incident.submittedAt().orElse(null), null, 0);
    }

    static final class ReporterDataAccessException extends RuntimeException {
        ReporterDataAccessException(DataAccessException cause) {
            super("Reporter incident data is unavailable", cause);
        }
    }
}
