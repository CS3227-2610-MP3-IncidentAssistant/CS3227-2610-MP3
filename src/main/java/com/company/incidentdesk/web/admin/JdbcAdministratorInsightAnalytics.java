package com.company.incidentdesk.web.admin;

import static com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPeriodResolver.ResolvedPeriod;

/** PostgreSQL analytics registry. Every plan is dispatched through one exhaustive typed switch. */
@Repository
@ConditionalOnBean(DataSource.class)
class JdbcAdministratorInsightAnalytics implements AdministratorInsightAnalytics {
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final Set<String> ALL_CATEGORIES = EnumSet.allOf(com.company.incidentdesk.domain.incident.IncidentCategory.class)
            .stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final NamedParameterJdbcTemplate jdbc;
    private final AdministratorStatisticsStore statistics;
    private final TransactionTemplate transactions;

    JdbcAdministratorInsightAnalytics(NamedParameterJdbcTemplate jdbc, AdministratorStatisticsStore statistics,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.statistics = statistics;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setReadOnly(true);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transactions.setTimeout(QUERY_TIMEOUT_SECONDS);
    }

    @Override
    public List<String> knownLoginNames(String administratorLoginName) {
        try {
            List<String> result = transactions.execute(status -> {
                requireAdministrator(administratorLoginName);
                return jdbc.query("SELECT login_name FROM accounts", Map.of(),
                        (row, index) -> row.getString("login_name"));
            });
            return result == null ? List.of() : result;
        } catch (DataAccessException failure) {
            throw new AnalyticsUnavailableException();
        }
    }

    @Override
    public Result execute(String administratorLoginName, Query query) {
        try {
            Result result = transactions.execute(status -> executeAuthorized(administratorLoginName, query));
            if (result == null) throw new AnalyticsUnavailableException();
            return result;
        } catch (AnalyticsUnavailableException safe) {
            throw safe;
        } catch (DataAccessException failure) {
            throw new AnalyticsUnavailableException();
        }
    }

    private Result executeAuthorized(String actor, Query query) {
        requireAdministrator(actor);
        requireResponders(query.responderIds());
        Instant snapshot = query.periods().requestInstant();
        return switch (query.plan().queryType().orElseThrow()) {
        case RANK_RESPONDERS -> responders(actor, query, false, snapshot);
        case COMPARE_SELECTED_RESPONDERS -> responders(actor, query, true, snapshot);
        case RANK_CATEGORIES -> categories(actor, query, snapshot);
        case CATEGORY_TREND -> trend(actor, query, snapshot);
        case PERIOD_COMPARISON -> periodComparison(actor, query, snapshot);
        case BACKLOG_SNAPSHOT -> backlog(query, snapshot);
        case SLO_TARGET_COMPARISON -> sloComparison(actor, query, snapshot);
        case OPERATIONAL_OVERVIEW -> overview(actor, query, snapshot);
        };
    }

    private Result responders(String actor, Query query, boolean selectedOnly, Instant snapshot) {
        var read = statistics(actor, query.categories(), query.periods().period());
        Metric metric = query.plan().metric().orElseThrow();
        List<ResponderRank> responders = selectedOnly
                ? selectedResponders(query.responderIds(), read, metric)
                : rankedResponders(read, metric);
        Comparator<Double> values = query.plan().direction().orElse(Direction.DESCENDING) == Direction.DESCENDING
                ? Comparator.nullsLast(Comparator.reverseOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<ResponderRank> comparator = Comparator.comparing(rank -> rank.value().numericValue(), values);
        comparator = comparator.thenComparing(rank -> rank.value().label().toLowerCase(Locale.ROOT))
                .thenComparing(ResponderRank::responderId);
        List<Value> ranked = responders.stream().sorted(comparator).limit(query.plan().limit().orElse(10))
                .map(ResponderRank::value).toList();
        return new Result(ranked, snapshot);
    }

    private static List<ResponderRank> rankedResponders(AdministratorStatisticsStore.ReadResult read, Metric metric) {
        return read.responders().stream()
                .map(row -> new ResponderRank(row.responderId(), new Value(row.displayName(), metric,
                        responderValue(metric, row), row.resolvedCycleCount())))
                .toList();
    }

    private static List<ResponderRank> selectedResponders(Set<UUID> selectedIds,
            AdministratorStatisticsStore.ReadResult read, Metric metric) {
        Map<UUID, AdministratorStatisticsStore.ResponderBreakdown> statisticsByResponder = read.responders().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AdministratorStatisticsStore.ResponderBreakdown::responderId, row -> row));
        Map<UUID, String> namesByResponder = read.filterOptions().responders().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AdministratorStatisticsStore.IdentityOption::id,
                        AdministratorStatisticsStore.IdentityOption::displayName));
        return selectedIds.stream().map(responderId -> {
            var statistics = statisticsByResponder.get(responderId);
            if (statistics != null) {
                return new ResponderRank(responderId, new Value(statistics.displayName(), metric,
                        responderValue(metric, statistics), statistics.resolvedCycleCount()));
            }
            String displayName = namesByResponder.get(responderId);
            if (displayName == null) throw new AnalyticsUnavailableException();
            Double value = metric == Metric.RESOLVED_CYCLE_COUNT ? 0d : null;
            return new ResponderRank(responderId, new Value(displayName, metric, value, 0));
        }).toList();
    }

    private Result categories(String actor, Query query, Instant snapshot) {
        List<Value> values = new ArrayList<>();
        for (String category : categories(query.categories())) {
            Metric metric = query.plan().metric().orElseThrow();
            Value value = categoryValue(actor, category, metric, query.periods().period(), snapshot);
            if (value != null) values.add(value);
        }
        List<Value> ranked = ranked(values, query.plan().direction().orElse(Direction.DESCENDING),
                query.plan().limit().orElse(10));
        return new Result(ranked, snapshot);
    }

    private Result trend(String actor, Query query, Instant snapshot) {
        ResolvedPeriod period = query.periods().period();
        long days = Math.max(1, period.duration().toDays());
        long bucketDays = days <= 31 ? 1 : days <= 180 ? 7 : 31;
        List<Value> values = new ArrayList<>();
        for (Instant start = period.startInclusive(); start.isBefore(period.endExclusive()); start = start.plus(bucketDays, ChronoUnit.DAYS)) {
            Instant end = min(start.plus(bucketDays, ChronoUnit.DAYS), period.endExclusive());
            ResolvedPeriod bucket = new ResolvedPeriod(start, end);
            for (String category : categories(query.categories())) {
                Value value = categoryValue(actor, category, query.plan().metric().orElseThrow(), bucket, end);
                if (value != null) values.add(new Value(categoryLabel(category) + " · " + start.toString(),
                        value.metric(), value.numericValue(), value.sampleSize()));
            }
        }
        if (values.size() > 366) throw new AnalyticsUnavailableException();
        return new Result(values, snapshot);
    }

    private Result periodComparison(String actor, Query query, Instant snapshot) {
        ResolvedPeriod comparison = query.periods().comparisonPeriod().orElseThrow();
        Metric metric = query.plan().metric().orElseThrow();
        List<Value> values = new ArrayList<>();
        add(values, companyValue(actor, "Selected period", metric, query.categories(), query.periods().period(), snapshot));
        add(values, companyValue(actor, "Comparison period", metric, query.categories(), comparison, snapshot));
        return new Result(values, snapshot);
    }

    private Result backlog(Query query, Instant snapshot) {
        List<Value> values = jdbc.query("""
                SELECT category, COUNT(*) AS item_count,
                       MAX(EXTRACT(EPOCH FROM (:snapshot - queue_entered_at)) * 1000) AS oldest_millis
                  FROM incidents
                 WHERE status = 'SUBMITTED' AND assignee_id IS NULL
                   AND (:allCategories OR category IN (:categories))
              GROUP BY category
              ORDER BY category
                """, parameters(query.categories(), snapshot), (row, index) -> List.of(
                new Value(categoryLabel(row.getString("category")) + " unassigned",
                        Metric.UNASSIGNED_BACKLOG_COUNT, row.getDouble("item_count"), row.getLong("item_count")),
                new Value(categoryLabel(row.getString("category")) + " oldest",
                        Metric.OLDEST_UNASSIGNED_AGE, row.getDouble("oldest_millis"), row.getLong("item_count"))))
                .stream().flatMap(List::stream).toList();
        return new Result(values, snapshot);
    }

    private Result sloComparison(String actor, Query query, Instant snapshot) {
        Metric metric = query.plan().metric().orElseThrow();
        requireAdministrator(actor);
        List<Value> values = switch (metric) {
        case AVERAGE_TIME_TO_CLAIM -> claimSloValues(query);
        case AVERAGE_TIME_IN_PROGRESS, REOPEN_RATE -> resolutionSloValues(query, metric);
        default -> throw new IllegalArgumentException("Unsupported SLO metric");
        };
        return new Result(values, snapshot);
    }

    private List<Value> claimSloValues(Query query) {
        return jdbc.query("""
                WITH claim_cycles AS (
                    SELECT incident.category, resolution.queue_entered_at, resolution.first_assigned_at
                      FROM incident_resolutions resolution
                      JOIN incidents incident ON incident.id = resolution.incident_id
                    UNION ALL
                    SELECT incident.category, incident.queue_entered_at, incident.first_assigned_at
                      FROM incidents incident
                     WHERE incident.status = 'ASSIGNED' AND incident.first_assigned_at IS NOT NULL
                )
                SELECT cycle.category, target.effective_at, COUNT(*) AS sample_size,
                       AVG(EXTRACT(EPOCH FROM (cycle.first_assigned_at - cycle.queue_entered_at)) * 1000)
                           - target.average_time_to_claim_minutes * 60000 AS difference
                  FROM claim_cycles cycle
                  JOIN LATERAL (
                      SELECT version.effective_at, version.average_time_to_claim_minutes
                        FROM slo_target_versions version
                       WHERE version.category = cycle.category
                         AND version.effective_at <= cycle.first_assigned_at
                    ORDER BY version.effective_at DESC, version.id ASC LIMIT 1
                  ) target ON TRUE
                 WHERE cycle.queue_entered_at >= :from AND cycle.queue_entered_at < :until
                   AND (:allCategories OR cycle.category IN (:categories))
              GROUP BY cycle.category, target.effective_at, target.average_time_to_claim_minutes
              ORDER BY cycle.category, target.effective_at
                """, parameters(query.categories(), query.periods().period()), (row, index) -> new Value(
                categoryLabel(row.getString("category")) + " · target from " + row.getTimestamp("effective_at").toInstant(),
                Metric.AVERAGE_TIME_TO_CLAIM, row.getDouble("difference"), row.getLong("sample_size")));
    }

    private List<Value> resolutionSloValues(Query query, Metric metric) {
        String observed = metric == Metric.AVERAGE_TIME_IN_PROGRESS
                ? "AVG(EXTRACT(EPOCH FROM (resolution.resolved_at - resolution.first_assigned_at)) * 1000)"
                : "COUNT(*) FILTER (WHERE resolution.reopened_at IS NOT NULL)::double precision / COUNT(*)";
        String target = metric == Metric.AVERAGE_TIME_IN_PROGRESS
                ? "target.average_time_in_progress_minutes * 60000"
                : "target.average_reopen_rate_percent / 100";
        String sql = """
                SELECT incident.category, target.effective_at, COUNT(*) AS sample_size,
                       %s - %s AS difference
                  FROM incident_resolutions resolution
                  JOIN incidents incident ON incident.id = resolution.incident_id
                  JOIN LATERAL (
                      SELECT version.effective_at, version.average_time_in_progress_minutes,
                             version.average_reopen_rate_percent
                        FROM slo_target_versions version
                       WHERE version.category = incident.category
                         AND version.effective_at <= resolution.resolved_at
                    ORDER BY version.effective_at DESC, version.id ASC LIMIT 1
                  ) target ON TRUE
                 WHERE resolution.resolved_at >= :from AND resolution.resolved_at < :until
                   AND (:allCategories OR incident.category IN (:categories))
              GROUP BY incident.category, target.effective_at, target.average_time_in_progress_minutes,
                       target.average_reopen_rate_percent
              ORDER BY incident.category, target.effective_at
                """.formatted(observed, target);
        return jdbc.query(sql, parameters(query.categories(), query.periods().period()), (row, index) -> new Value(
                categoryLabel(row.getString("category")) + " · target from " + row.getTimestamp("effective_at").toInstant(),
                metric, row.getDouble("difference"), row.getLong("sample_size")));
    }

    private Result overview(String actor, Query query, Instant snapshot) {
        var summary = statistics(actor, query.categories(), query.periods().period()).summary();
        List<Value> values = new ArrayList<>();
        values.add(new Value("Resolved incidents", Metric.RESOLVED_CYCLE_COUNT,
                (double) summary.resolvedIncidentCount(), summary.completedCycleCount()));
        add(values, summaryValue("Average time to claim", Metric.AVERAGE_TIME_TO_CLAIM, summary));
        add(values, summaryValue("Average time in progress", Metric.AVERAGE_TIME_IN_PROGRESS, summary));
        add(values, summaryValue("Reopen rate", Metric.REOPEN_RATE, summary));
        long unassignedCount = backlogCount(query.categories());
        values.add(new Value("Unassigned backlog", Metric.UNASSIGNED_BACKLOG_COUNT,
                (double) unassignedCount, unassignedCount));
        if (query.periods().comparisonPeriod().isPresent()) {
            Value comparison = companyValue(actor, "Comparison period", query.plan().metric().orElseThrow(),
                    query.categories(), query.periods().comparisonPeriod().orElseThrow(), snapshot);
            add(values, comparison);
        }
        return new Result(values, snapshot);
    }

    private Value categoryValue(String actor, String category, Metric metric, ResolvedPeriod period, Instant snapshot) {
        if (metric == Metric.INCIDENT_COUNT) {
            long count = incidentCount(Set.of(category), period);
            return new Value(categoryLabel(category), metric, (double) count, count);
        }
        if (metric == Metric.UNASSIGNED_BACKLOG_COUNT || metric == Metric.OLDEST_UNASSIGNED_AGE) {
            return backlogValue(category, metric, snapshot);
        }
        var summary = statistics(actor, Set.of(category), period).summary();
        Double value = summaryValue(metric, summary);
        return value == null ? null : new Value(categoryLabel(category), metric, value, sample(metric, summary));
    }

    private Value companyValue(String actor, String label, Metric metric, Set<String> categories,
            ResolvedPeriod period, Instant snapshot) {
        if (metric == Metric.INCIDENT_COUNT) {
            long count = incidentCount(categories, period);
            return new Value(label, metric, (double) count, count);
        }
        if (metric == Metric.UNASSIGNED_BACKLOG_COUNT) {
            long count = backlogCount(categories);
            return new Value(label, metric, (double) count, count);
        }
        if (metric == Metric.OLDEST_UNASSIGNED_AGE) return oldestBacklog(label, categories, snapshot);
        var summary = statistics(actor, categories, period).summary();
        Double value = summaryValue(metric, summary);
        return value == null ? null : new Value(label, metric, value, sample(metric, summary));
    }

    private AdministratorStatisticsStore.ReadResult statistics(String actor, Set<String> categories, ResolvedPeriod period) {
        var result = statistics.read(actor, new AdministratorStatisticsStore.StatisticsFilter(categories, null, null,
                period.startInclusive(), period.endExclusive().minusNanos(1)));
        if (result.outcome() != AdministratorStatisticsStore.ReadOutcome.AVAILABLE) throw new AnalyticsUnavailableException();
        return result;
    }

    private long incidentCount(Set<String> categories, ResolvedPeriod period) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM incidents
                 WHERE submitted_at >= :from AND submitted_at < :until
                   AND (:allCategories OR category IN (:categories))
                """, parameters(categories, period), Long.class);
        return value == null ? 0 : value;
    }

    private long backlogCount(Set<String> categories) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM incidents
                 WHERE status = 'SUBMITTED' AND assignee_id IS NULL
                   AND (:allCategories OR category IN (:categories))
                """, categoryParameters(categories), Long.class);
        return value == null ? 0 : value;
    }

    private Value backlogValue(String category, Metric metric, Instant snapshot) {
        return jdbc.query("""
                SELECT COUNT(*) AS item_count,
                       MAX(EXTRACT(EPOCH FROM (:snapshot - queue_entered_at)) * 1000) AS oldest_millis
                  FROM incidents
                 WHERE status = 'SUBMITTED' AND assignee_id IS NULL AND category = :category
                """, Map.of("snapshot", Timestamp.from(snapshot), "category", category), rows -> {
            rows.next();
            long count = rows.getLong("item_count");
            if (metric == Metric.OLDEST_UNASSIGNED_AGE && rows.getObject("oldest_millis") == null) return null;
            double value = metric == Metric.UNASSIGNED_BACKLOG_COUNT ? count : rows.getDouble("oldest_millis");
            return new Value(categoryLabel(category), metric, value, count);
        });
    }

    private Value oldestBacklog(String label, Set<String> categories, Instant snapshot) {
        Double value = jdbc.queryForObject("""
                SELECT MAX(EXTRACT(EPOCH FROM (:snapshot - queue_entered_at)) * 1000)
                  FROM incidents WHERE status = 'SUBMITTED' AND assignee_id IS NULL
                   AND (:allCategories OR category IN (:categories))
                """, parameters(categories, snapshot), Double.class);
        return value == null ? null : new Value(label, Metric.OLDEST_UNASSIGNED_AGE, value, backlogCount(categories));
    }

    private void requireAdministrator(String loginName) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts
                 WHERE login_name=:loginName AND role='ADMINISTRATOR' AND status='ENABLED'
                """, Map.of("loginName", loginName), Long.class);
        if (count == null || count != 1) throw new AnalyticsUnavailableException();
    }

    private void requireResponders(Set<UUID> ids) {
        if (ids.isEmpty()) return;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts
                 WHERE id IN (:ids) AND role='RESPONDER' AND status='ENABLED'
                """, Map.of("ids", ids), Long.class);
        if (count == null || count != ids.size()) throw new AnalyticsUnavailableException();
    }

    private static List<Value> ranked(List<Value> values, Direction direction, int limit) {
        Comparator<Value> comparator = Comparator.comparing(Value::numericValue);
        if (direction == Direction.DESCENDING) comparator = comparator.reversed();
        comparator = comparator.thenComparing(value -> value.label().toLowerCase(Locale.ROOT));
        return values.stream().sorted(comparator).limit(limit).toList();
    }

    private static Double responderValue(Metric metric, AdministratorStatisticsStore.ResponderBreakdown row) {
        return switch (metric) {
        case RESOLVED_CYCLE_COUNT -> (double) row.resolvedCycleCount();
        case AVERAGE_TIME_IN_PROGRESS -> number(row.averageTimeInProgressMillis());
        case REOPEN_RATE -> row.reopenRate();
        default -> null;
        };
    }

    private static Double summaryValue(Metric metric, AdministratorStatisticsStore.Summary row) {
        return switch (metric) {
        case RESOLVED_CYCLE_COUNT -> (double) row.completedCycleCount();
        case AVERAGE_TIME_TO_CLAIM -> number(row.averageTimeToClaimMillis());
        case AVERAGE_TIME_IN_PROGRESS -> number(row.averageTimeInProgressMillis());
        case REOPEN_RATE -> row.reopenRate();
        case REOPEN_EVENT_COUNT -> (double) row.reopenEventCount();
        default -> null;
        };
    }

    private static Value summaryValue(String label, Metric metric, AdministratorStatisticsStore.Summary summary) {
        Double value = summaryValue(metric, summary);
        return value == null ? null : new Value(label, metric, value, sample(metric, summary));
    }

    private static long sample(Metric metric, AdministratorStatisticsStore.Summary row) {
        return metric == Metric.AVERAGE_TIME_TO_CLAIM ? row.claimedCycleCount() : row.completedCycleCount();
    }

    private static Set<String> categories(Set<String> selected) { return selected.isEmpty() ? ALL_CATEGORIES : selected; }
    private static String categoryLabel(String value) { return value.equals("IT") ? value : value.toLowerCase(Locale.ROOT).replace('_', ' '); }
    private static Double number(Number value) { return value == null ? null : value.doubleValue(); }
    private static Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private static void add(List<Value> values, Value value) { if (value != null) values.add(value); }

    private static Map<String, Object> categoryParameters(Set<String> categories) {
        Map<String, Object> values = new HashMap<>();
        values.put("allCategories", categories.isEmpty());
        values.put("categories", categories.isEmpty() ? ALL_CATEGORIES : categories);
        return values;
    }
    private static Map<String, Object> parameters(Set<String> categories, ResolvedPeriod period) {
        Map<String, Object> values = categoryParameters(categories);
        values.put("from", Timestamp.from(period.startInclusive()));
        values.put("until", Timestamp.from(period.endExclusive()));
        return values;
    }
    private static Map<String, Object> parameters(Set<String> categories, Instant snapshot) {
        Map<String, Object> values = categoryParameters(categories);
        values.put("snapshot", Timestamp.from(snapshot));
        return values;
    }

    static final class AnalyticsUnavailableException extends RuntimeException { }
    private record ResponderRank(UUID responderId, Value value) { }
}
