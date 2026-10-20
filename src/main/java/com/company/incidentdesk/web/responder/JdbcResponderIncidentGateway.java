package com.company.incidentdesk.web.responder;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentRow;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ListCriteria;
import com.company.incidentdesk.web.responder.ResponderIncidentService.Assignment;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ResponderDataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL queue query that scopes data to the current enabled Responder and categories. */
@Repository
@ConditionalOnBean(DataSource.class)
final class JdbcResponderIncidentGateway implements ResponderIncidentGateway {
    private static final String AUTHORIZED_INCIDENTS = """
            (i.status = 'SUBMITTED' AND i.assignee_id IS NULL AND EXISTS (
                SELECT 1 FROM responder_category_access category_access
                 WHERE category_access.account_id = :actorId
                   AND category_access.category = i.category
            )) OR (i.status = 'ASSIGNED' AND i.assignee_id = :actorId)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    JdbcResponderIncidentGateway(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    @Override
    public Optional<IncidentPage> findQueue(String loginName, ListCriteria criteria) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            IncidentPage page = transaction.execute(status -> readQueue(loginName, criteria));
            return Optional.ofNullable(page);
        } catch (DataAccessException exception) {
            throw new ResponderDataAccessException(exception);
        }
    }

    private IncidentPage readQueue(String loginName, ListCriteria criteria) {
        List<UUID> actorIds = jdbc.query("""
                SELECT id
                  FROM accounts
                 WHERE login_name = :loginName
                   AND role = 'RESPONDER'
                   AND status = 'ENABLED'
                """, Map.of("loginName", loginName),
                (result, row) -> result.getObject("id", UUID.class));
        if (actorIds.isEmpty()) {
            return null;
        }

        UUID actorId = actorIds.getFirst();
        boolean categoriesConfigured = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM responder_category_access WHERE account_id = :actorId
                )
                """, Map.of("actorId", actorId), Boolean.class));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("actorId", actorId);
        String filteredQueue = filteredQueue(criteria, parameters);
        long total = Optional.ofNullable(jdbc.queryForObject("""
                SELECT COUNT(*) FROM incidents i WHERE
                """ + filteredQueue, parameters, Long.class)).orElse(0L);
        parameters.put("limit", criteria.size());
        parameters.put("offset", criteria.offset());
        List<IncidentRow> rows = jdbc.query("""
                SELECT i.id, i.title, i.category, i.status, i.submitted_at, i.queue_entered_at
                 FROM incidents i
                 WHERE
                """ + filteredQueue + """
                 ORDER BY i.queue_entered_at ASC NULLS LAST, i.id ASC
                 LIMIT :limit OFFSET :offset
                """, parameters, (result, row) -> new IncidentRow(
                result.getObject("id", UUID.class),
                result.getString("title"),
                result.getString("category"),
                result.getString("status"),
                result.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                result.getObject("queue_entered_at", OffsetDateTime.class).toInstant()));
        long totalPages = Math.max(1, (total + criteria.size() - 1) / criteria.size());
        return new IncidentPage(rows, criteria.page(), criteria.size(), total, totalPages, categoriesConfigured);
    }

    private static String filteredQueue(ListCriteria criteria, Map<String, Object> parameters) {
        StringBuilder where = new StringBuilder("(").append(AUTHORIZED_INCIDENTS).append(")");
        if (!criteria.search().isEmpty()) {
            where.append(" AND (i.title ILIKE :search ESCAPE '!' OR i.description ILIKE :search ESCAPE '!'")
                    .append(" OR CAST(i.id AS text) ILIKE :search ESCAPE '!')");
            parameters.put("search", "%" + escapeLikePattern(criteria.search()) + "%");
        }
        addSetPredicate(where, parameters, "i.category", "categories", criteria.categories());
        addSetPredicate(where, parameters, "i.status", "statuses", criteria.statuses());
        switch (criteria.assignment()) {
        case ALL -> { }
        case ASSIGNED -> where.append(" AND i.assignee_id = :actorId");
        case UNASSIGNED -> where.append(" AND i.assignee_id IS NULL");
        }
        return where.toString();
    }

    private static String escapeLikePattern(String search) {
        return search.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static void addSetPredicate(
            StringBuilder where, Map<String, Object> parameters, String column, String name, List<String> values) {
        if (!values.isEmpty()) {
            where.append(" AND ").append(column).append(" IN (:").append(name).append(')');
            parameters.put(name, values);
        }
    }
}
