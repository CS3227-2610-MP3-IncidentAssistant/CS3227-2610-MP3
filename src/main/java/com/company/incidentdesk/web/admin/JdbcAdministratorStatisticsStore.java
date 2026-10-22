package com.company.incidentdesk.web.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL implementation that aggregates persisted lifecycle-cycle snapshots. */
@Repository
@ConditionalOnBean(DataSource.class)
final class JdbcAdministratorStatisticsStore implements AdministratorStatisticsStore {
    private static final String DELETED_ACCOUNT_LABEL = "Deleted account";
    private static final System.Logger LOGGER = System.getLogger(JdbcAdministratorStatisticsStore.class.getName());

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    JdbcAdministratorStatisticsStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    @Override
    public ReadResult read(String administratorLoginName, StatisticsFilter filter) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            ReadResult result = transaction.execute(status -> readAuthorized(administratorLoginName, filter));
            return result == null ? ReadResult.persistenceFailure() : result;
        } catch (DataAccessException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Administrator statistics query failed", exception);
            return ReadResult.persistenceFailure();
        }
    }

    private ReadResult readAuthorized(String administratorLoginName, StatisticsFilter filter) {
        if (!isEnabledAdministrator(administratorLoginName)) {
            return ReadResult.unavailable();
        }
        ResolutionAggregate resolutions = resolutionAggregate(filter);
        ClaimAggregate claims = queueClaimAggregate(filter);
        Summary summary = new Summary(
                resolutions.resolvedIncidentCount(),
                claims.averageMillis(),
                claims.cycleCount(),
                resolutions.averageProgressMillis(),
                resolutions.completedCycleCount(),
                resolutions.reopenedIncidentCount(),
                ratio(resolutions.reopenedIncidentCount(), resolutions.resolvedIncidentCount()),
                resolutions.reopenEventCount(),
                resolutions.administratorResolvedCount());
        return new ReadResult(ReadOutcome.AVAILABLE, summary, responderBreakdown(filter), filterOptions());
    }

    private boolean isEnabledAdministrator(String loginName) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM accounts
                 WHERE login_name = :loginName
                   AND role = 'ADMINISTRATOR'
                   AND status = 'ENABLED'
                """, Map.of("loginName", loginName), Long.class);
        return count != null && count == 1;
    }

    private ResolutionAggregate resolutionAggregate(StatisticsFilter filter) {
        QueryScope scope = resolutionScope(filter);
        return jdbc.queryForObject("""
                SELECT COUNT(DISTINCT resolution.incident_id) AS resolved_incident_count,
                       COUNT(*) AS completed_cycle_count,
                       AVG(EXTRACT(EPOCH FROM (resolution.resolved_at - resolution.first_assigned_at)) * 1000)
                           AS average_progress_millis,
                       COUNT(DISTINCT resolution.incident_id)
                           FILTER (WHERE resolution.reopened_at IS NOT NULL) AS reopened_incident_count,
                       COUNT(*) FILTER (WHERE resolution.reopened_at IS NOT NULL) AS reopen_event_count,
                       COUNT(*) FILTER (WHERE resolution.resolved_by <> resolution.responder_at_resolution)
                           AS administrator_resolved_count
                  FROM incident_resolutions resolution
                  JOIN incidents incident ON incident.id = resolution.incident_id
                """ + scope.where(), scope.parameters(), (result, row) -> new ResolutionAggregate(
                result.getLong("resolved_incident_count"),
                result.getLong("completed_cycle_count"),
                milliseconds(result.getBigDecimal("average_progress_millis")),
                result.getLong("reopened_incident_count"),
                result.getLong("reopen_event_count"),
                result.getLong("administrator_resolved_count")));
    }

    private ClaimAggregate queueClaimAggregate(StatisticsFilter filter) {
        // Queue speed is an incident/category measure and is not attributed to the eventual resolver.
        return filter.responderId() == null ? claimAggregate(filter) : ClaimAggregate.empty();
    }

    private ClaimAggregate claimAggregate(StatisticsFilter filter) {
        QueryScope resolvedScope = claimScope(filter, "incident", "resolution.queue_entered_at");
        QueryScope activeScope = activeClaimScope(filter);
        Map<String, Object> parameters = new HashMap<>(resolvedScope.parameters());
        parameters.putAll(activeScope.parameters());
        return jdbc.queryForObject("""
                WITH claim_cycles AS (
                    SELECT resolution.queue_entered_at, resolution.first_assigned_at
                      FROM incident_resolutions resolution
                      JOIN incidents incident ON incident.id = resolution.incident_id
                """ + resolvedScope.where() + """
                    UNION ALL
                    SELECT incident.queue_entered_at, incident.first_assigned_at
                      FROM incidents incident
                """ + activeScope.where() + """
                )
                SELECT COUNT(*) AS claimed_cycle_count,
                       AVG(EXTRACT(EPOCH FROM (first_assigned_at - queue_entered_at)) * 1000)
                           AS average_claim_millis
                  FROM claim_cycles
                """, parameters, (result, row) -> new ClaimAggregate(
                result.getLong("claimed_cycle_count"),
                milliseconds(result.getBigDecimal("average_claim_millis"))));
    }

    private List<ResponderBreakdown> responderBreakdown(StatisticsFilter filter) {
        QueryScope scope = resolutionScope(filter);
        return jdbc.query("""
                SELECT resolution.responder_at_resolution AS responder_id,
                       CASE WHEN responder.status = 'DELETED' THEN :deletedLabel ELSE responder.login_name END
                           AS display_name,
                       COUNT(*) AS resolved_cycle_count,
                       AVG(EXTRACT(EPOCH FROM (resolution.resolved_at - resolution.first_assigned_at)) * 1000)
                           AS average_progress_millis,
                       COUNT(*) FILTER (WHERE resolution.reopened_at IS NOT NULL) AS reopened_cycle_count,
                       COUNT(*) FILTER (WHERE resolution.resolved_by <> resolution.responder_at_resolution)
                           AS administrator_resolved_count
                  FROM incident_resolutions resolution
                  JOIN incidents incident ON incident.id = resolution.incident_id
                  JOIN accounts responder ON responder.id = resolution.responder_at_resolution
                """ + scope.where() + """

              GROUP BY resolution.responder_at_resolution, responder.status, responder.login_name
              ORDER BY LOWER(CASE WHEN responder.status = 'DELETED' THEN :deletedLabel ELSE responder.login_name END),
                       resolution.responder_at_resolution
                """, withDeletedLabel(scope.parameters()), (result, row) -> {
                    long resolved = result.getLong("resolved_cycle_count");
                    long reopened = result.getLong("reopened_cycle_count");
                    return new ResponderBreakdown(
                            result.getObject("responder_id", UUID.class),
                            result.getString("display_name"),
                            resolved,
                            milliseconds(result.getBigDecimal("average_progress_millis")),
                            reopened,
                            ratio(reopened, resolved),
                            result.getLong("administrator_resolved_count"));
                });
    }

    private FilterOptions filterOptions() {
        List<IdentityOption> responders = jdbc.query("""
                SELECT account.id,
                       CASE WHEN account.status = 'DELETED' THEN :deletedLabel ELSE account.login_name END
                           AS display_name
                  FROM accounts account
                 WHERE account.role = 'RESPONDER'
                    OR EXISTS (
                        SELECT 1 FROM incident_resolutions resolution
                         WHERE resolution.responder_at_resolution = account.id
                    )
              ORDER BY LOWER(CASE WHEN account.status = 'DELETED' THEN :deletedLabel ELSE account.login_name END),
                       account.id
                """, Map.of("deletedLabel", DELETED_ACCOUNT_LABEL),
                (result, row) -> new IdentityOption(
                        result.getObject("id", UUID.class), result.getString("display_name")));
        List<IdentityOption> reporters = jdbc.query("""
                SELECT DISTINCT account.id,
                       CASE WHEN account.status = 'DELETED' THEN :deletedLabel ELSE account.login_name END
                           AS display_name
                  FROM accounts account
                  JOIN incidents incident ON incident.reporter_id = account.id
                 WHERE incident.anonymous = FALSE
              ORDER BY display_name, account.id
                """, Map.of("deletedLabel", DELETED_ACCOUNT_LABEL),
                (result, row) -> new IdentityOption(
                        result.getObject("id", UUID.class), result.getString("display_name")));
        return new FilterOptions(responders, reporters);
    }

    private static QueryScope resolutionScope(StatisticsFilter filter) {
        Map<String, Object> parameters = baseParameters(filter);
        List<String> predicates = incidentPredicates(filter, "incident");
        if (filter.periodFrom() != null) {
            predicates.add("resolution.resolved_at >= :periodFrom");
        }
        if (filter.periodThrough() != null) {
            predicates.add("resolution.resolved_at <= :periodThrough");
        }
        if (filter.responderId() != null) {
            predicates.add("resolution.responder_at_resolution = :responderId");
        }
        return new QueryScope(where(predicates), parameters);
    }

    private static QueryScope claimScope(StatisticsFilter filter, String incidentAlias, String queueTimestamp) {
        Map<String, Object> parameters = baseParameters(filter);
        List<String> predicates = incidentPredicates(filter, incidentAlias);
        if (filter.periodFrom() != null) {
            predicates.add(queueTimestamp + " >= :periodFrom");
        }
        if (filter.periodThrough() != null) {
            predicates.add(queueTimestamp + " <= :periodThrough");
        }
        return new QueryScope(where(predicates), parameters);
    }

    private static QueryScope activeClaimScope(StatisticsFilter filter) {
        Map<String, Object> parameters = baseParameters(filter);
        List<String> predicates = incidentPredicates(filter, "incident");
        if (filter.periodFrom() != null) {
            predicates.add("incident.queue_entered_at >= :periodFrom");
        }
        if (filter.periodThrough() != null) {
            predicates.add("incident.queue_entered_at <= :periodThrough");
        }
        predicates.add("incident.status = 'ASSIGNED'");
        predicates.add("incident.first_assigned_at IS NOT NULL");
        return new QueryScope(where(predicates), parameters);
    }

    private static List<String> incidentPredicates(StatisticsFilter filter, String alias) {
        List<String> predicates = new ArrayList<>();
        if (!filter.categories().isEmpty()) {
            predicates.add(alias + ".category IN (:categories)");
        }
        if (filter.reporterId() != null) {
            predicates.add(alias + ".anonymous = FALSE");
            predicates.add(alias + ".reporter_id = :reporterId");
        }
        return predicates;
    }

    private static Map<String, Object> baseParameters(StatisticsFilter filter) {
        Map<String, Object> parameters = new HashMap<>();
        if (!filter.categories().isEmpty()) parameters.put("categories", filter.categories());
        if (filter.reporterId() != null) parameters.put("reporterId", filter.reporterId());
        if (filter.responderId() != null) parameters.put("responderId", filter.responderId());
        if (filter.periodFrom() != null) parameters.put("periodFrom", Timestamp.from(filter.periodFrom()));
        if (filter.periodThrough() != null) parameters.put("periodThrough", Timestamp.from(filter.periodThrough()));
        return parameters;
    }

    private static Map<String, Object> withDeletedLabel(Map<String, Object> original) {
        Map<String, Object> parameters = new HashMap<>(original);
        parameters.put("deletedLabel", DELETED_ACCOUNT_LABEL);
        return parameters;
    }

    private static String where(List<String> predicates) {
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }

    private static Long milliseconds(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.DOWN).longValueExact();
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private record QueryScope(String where, Map<String, Object> parameters) { }

    private record ResolutionAggregate(
            long resolvedIncidentCount,
            long completedCycleCount,
            Long averageProgressMillis,
            long reopenedIncidentCount,
            long reopenEventCount,
            long administratorResolvedCount) { }

    private record ClaimAggregate(long cycleCount, Long averageMillis) {
        static ClaimAggregate empty() {
            return new ClaimAggregate(0, null);
        }
    }
}
