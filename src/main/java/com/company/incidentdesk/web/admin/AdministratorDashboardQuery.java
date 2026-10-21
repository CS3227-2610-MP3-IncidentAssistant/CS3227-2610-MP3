package com.company.incidentdesk.web.admin;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Server-side, bounded administrator incident-list query with privacy-safe identities. */
@Service
@ConditionalOnBean(DataSource.class)
public final class AdministratorDashboardQuery {
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> CATEGORIES = Set.of("IT", "HUMAN_RELATIONS", "FACILITIES");
    private static final Set<String> STATUSES = Set.of("DRAFT", "SUBMITTED", "ASSIGNED", "RESOLVED", "WITHDRAWN");
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "createdAt", "i.created_at",
            "submittedAt", "i.submitted_at",
            "queueEnteredAt", "i.queue_entered_at",
            "title", "i.title",
            "status", "i.status",
            "category", "i.category");

    private final NamedParameterJdbcTemplate jdbc;

    public AdministratorDashboardQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardPage find(DashboardCriteria criteria) {
        ValidatedCriteria validated = validate(criteria);
        QueryParts query = buildQuery(validated);
        long total = count(query);
        List<IncidentRow> incidents = loadIncidents(validated, query);
        long totalPages = Math.max(1, (total + validated.size() - 1) / validated.size());
        return new DashboardPage(incidents, validated.page(), validated.size(), total, totalPages,
                currentSloTargets());
    }

    private static QueryParts buildQuery(ValidatedCriteria criteria) {
        Map<String, Object> parameters = new HashMap<>();
        List<String> predicates = new ArrayList<>();
        addSearchPredicate(criteria, predicates, parameters);
        addSetPredicate(predicates, parameters, "i.category", "categories", criteria.categories());
        addSetPredicate(predicates, parameters, "i.status", "statuses", criteria.statuses());
        addAssignmentPredicate(criteria.assignment(), predicates);
        addRangePredicates(criteria, predicates, parameters);
        addIdentityPredicates(criteria, predicates, parameters);
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        return new QueryParts(where, parameters);
    }

    private static void addSearchPredicate(
            ValidatedCriteria criteria, List<String> predicates, Map<String, Object> parameters) {
        if (!criteria.search().isEmpty()) {
            predicates.add("(i.title ILIKE :search OR i.description ILIKE :search OR CAST(i.id AS text) ILIKE :search)");
            parameters.put("search", "%" + criteria.search() + "%");
        }
    }

    private static void addAssignmentPredicate(Assignment assignment, List<String> predicates) {
        switch (assignment) {
        case ALL -> { }
        case ASSIGNED -> predicates.add("i.assignee_id IS NOT NULL");
        case UNASSIGNED -> predicates.add("i.assignee_id IS NULL");
        }
    }

    private static void addRangePredicates(
            ValidatedCriteria criteria, List<String> predicates, Map<String, Object> parameters) {
        if (criteria.createdFrom() != null) {
            predicates.add("i.created_at >= :createdFrom");
            parameters.put("createdFrom", criteria.createdFrom());
        }
        if (criteria.createdThrough() != null) {
            predicates.add("i.created_at <= :createdThrough");
            parameters.put("createdThrough", criteria.createdThrough());
        }
    }

    private static void addIdentityPredicates(
            ValidatedCriteria criteria, List<String> predicates, Map<String, Object> parameters) {
        if (criteria.reporterId() != null) {
            predicates.add("i.anonymous = FALSE AND i.reporter_id = :reporterId");
            parameters.put("reporterId", criteria.reporterId());
        }
        if (criteria.responderId() != null) {
            predicates.add("i.assignee_id = :responderId");
            parameters.put("responderId", criteria.responderId());
        }
    }

    private long count(QueryParts query) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incidents i" + query.where(), query.parameters(), Long.class);
        return count == null ? 0 : count;
    }

    private List<IncidentRow> loadIncidents(ValidatedCriteria criteria, QueryParts query) {
        Map<String, Object> parameters = new HashMap<>(query.parameters());
        parameters.put("limit", criteria.size());
        parameters.put("offset", Math.multiplyExact(criteria.page(), criteria.size()));
        String order = SORT_COLUMNS.get(criteria.sort()) + " " + criteria.direction().name() + ", i.id ASC";
        return jdbc.query("""
                SELECT i.id, i.title, i.category, i.status, i.created_at, i.submitted_at,
                       i.anonymous, reporter.login_name AS reporter_login,
                       assignee.id AS assignee_id, assignee.login_name AS assignee_login,
                       i.version
                  FROM incidents i
                  JOIN accounts reporter ON reporter.id = i.reporter_id
             LEFT JOIN accounts assignee ON assignee.id = i.assignee_id
                """ + query.where() + " ORDER BY " + order + " LIMIT :limit OFFSET :offset",
                parameters, (result, row) -> mapIncident(result));
    }

    private static IncidentRow mapIncident(ResultSet result) throws SQLException {
        OffsetDateTime submittedAt = result.getObject("submitted_at", OffsetDateTime.class);
        String reporterLabel = result.getBoolean("anonymous")
                ? "Anonymous reporter" : result.getString("reporter_login");
        return new IncidentRow(
                result.getObject("id", UUID.class),
                result.getString("title"),
                result.getString("category"),
                result.getString("status"),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                submittedAt == null ? null : submittedAt.toInstant(),
                reporterLabel,
                result.getObject("assignee_id", UUID.class),
                result.getString("assignee_login"),
                result.getLong("version"));
    }

    private List<SloTarget> currentSloTargets() {
        Map<String, SloTarget> configured = jdbc.query("""
                SELECT DISTINCT ON (category) category, average_time_to_claim_minutes,
                       average_time_in_progress_minutes, average_reopen_rate_percent, effective_at
                  FROM slo_target_versions
              ORDER BY category, effective_at DESC, id DESC
                """, result -> {
                    Map<String, SloTarget> values = new HashMap<>();
                    while (result.next()) {
                        String category = result.getString("category");
                        values.put(category, new SloTarget(category,
                                result.getLong("average_time_to_claim_minutes"),
                                result.getLong("average_time_in_progress_minutes"),
                                result.getBigDecimal("average_reopen_rate_percent"),
                                result.getObject("effective_at", OffsetDateTime.class).toInstant()));
                    }
                    return values;
                });
        return CATEGORIES.stream().sorted()
                .map(category -> configured.getOrDefault(category, SloTarget.unconfigured(category)))
                .toList();
    }

    private static void addSetPredicate(
            List<String> predicates,
            Map<String, Object> parameters,
            String column,
            String name,
            Set<String> values) {
        if (!values.isEmpty()) {
            predicates.add(column + " IN (:" + name + ")");
            parameters.put(name, values);
        }
    }

    private static ValidatedCriteria validate(DashboardCriteria criteria) {
        int page = Math.max(0, criteria.page());
        int size = criteria.size() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(criteria.size(), MAX_PAGE_SIZE);
        Set<String> categories = Set.copyOf(criteria.categories());
        Set<String> statuses = Set.copyOf(criteria.statuses());
        if (!CATEGORIES.containsAll(categories) || !STATUSES.containsAll(statuses)) {
            throw new IllegalArgumentException("Unknown category or status filter");
        }
        if (criteria.createdFrom() != null && criteria.createdThrough() != null
                && criteria.createdFrom().isAfter(criteria.createdThrough())) {
            throw new IllegalArgumentException("createdFrom must not be after createdThrough");
        }
        String sort = SORT_COLUMNS.containsKey(criteria.sort()) ? criteria.sort() : "createdAt";
        Direction direction = criteria.direction() == null ? Direction.DESC : criteria.direction();
        return new ValidatedCriteria(criteria.search().strip(), categories, statuses, criteria.assignment(),
                criteria.createdFrom(), criteria.createdThrough(), criteria.reporterId(), criteria.responderId(),
                page, size, sort, direction);
    }

    public record DashboardCriteria(
            String search, List<String> categories, List<String> statuses, Assignment assignment,
            Instant createdFrom, Instant createdThrough, UUID reporterId, UUID responderId,
            int page, int size, String sort, Direction direction) {
        public DashboardCriteria {
            search = search == null ? "" : search;
            categories = categories == null ? List.of() : List.copyOf(categories);
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            assignment = assignment == null ? Assignment.ALL : assignment;
            sort = sort == null ? "createdAt" : sort;
        }
    }

    private record ValidatedCriteria(
            String search, Set<String> categories, Set<String> statuses, Assignment assignment,
            Instant createdFrom, Instant createdThrough, UUID reporterId, UUID responderId,
            int page, int size, String sort, Direction direction) {
    }

    private record QueryParts(String where, Map<String, Object> parameters) {
    }

    public enum Assignment { ALL, ASSIGNED, UNASSIGNED }
    public enum Direction { ASC, DESC }

    public record DashboardPage(
            List<IncidentRow> incidents, int page, int size, long totalElements, long totalPages,
            List<SloTarget> sloTargets) {
    }

    public record IncidentRow(
            UUID id, String title, String category, String status, Instant createdAt, Instant submittedAt,
            String reporterLabel, UUID assigneeId, String assigneeLabel, long version) {
    }

    public record SloTarget(
            String category, Long timeToClaimMinutes, Long timeInProgressMinutes,
            BigDecimal reopenRatePercent, Instant effectiveAt) {
        static SloTarget unconfigured(String category) {
            return new SloTarget(category, null, null, null, null);
        }
    }
}
