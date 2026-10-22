package com.company.incidentdesk.web.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.company.incidentdesk.web.admin.AdministratorIncidentService.AttachmentEntry;
import com.company.incidentdesk.web.admin.AdministratorIncidentService.CommentEntry;
import com.company.incidentdesk.web.admin.AdministratorIncidentService.CommentResult;
import com.company.incidentdesk.web.admin.AdministratorIncidentService.EligibleResponder;
import com.company.incidentdesk.web.admin.AdministratorIncidentService.IncidentDataAccessException;
import com.company.incidentdesk.web.admin.AdministratorIncidentService.IncidentDetail;
import com.company.incidentdesk.web.admin.AdministratorIncidentService.MutationResult;
import com.company.incidentdesk.web.admin.AdministratorIncidentService.ResolutionEntry;
import com.company.incidentdesk.web.admin.AdministratorIncidentService.ResolutionResult;
import com.company.incidentdesk.web.admin.AdministratorIncidentService.SloProgress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL-backed Administrator incident operations with transaction-local authorization. */
@Repository
@ConditionalOnBean(DataSource.class)
class JdbcAdministratorIncidentStore implements AdministratorIncidentStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    @Autowired
    JdbcAdministratorIncidentStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, Clock.systemUTC(), UUID::randomUUID);
    }

    JdbcAdministratorIncidentStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Supplier<UUID> identifiers) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.clock = clock;
        this.identifiers = identifiers;
    }

    @Override
    public Optional<IncidentDetail> findDetail(String loginName, UUID incidentId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            return transaction.execute(status -> readDetail(loginName, incidentId));
        } catch (DataAccessException exception) {
            throw new IncidentDataAccessException(exception);
        }
    }

    @Override
    public CommentResult addComment(String loginName, UUID incidentId, UUID requestId, String text) {
        return write(() -> addCommentInTransaction(loginName, incidentId, requestId, text));
    }

    @Override
    public MutationResult assign(String loginName, UUID incidentId, long expectedVersion, UUID responderId) {
        return write(() -> assignInTransaction(loginName, incidentId, expectedVersion, responderId));
    }

    @Override
    public MutationResult unassign(String loginName, UUID incidentId, long expectedVersion) {
        return write(() -> unassignInTransaction(loginName, incidentId, expectedVersion));
    }

    @Override
    public ResolutionResult resolve(
            String loginName, UUID incidentId, long expectedVersion, UUID requestId, String remarks) {
        return write(() -> resolveInTransaction(loginName, incidentId, expectedVersion, requestId, remarks));
    }

    private <T> T write(Supplier<T> operation) {
        try {
            return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> operation.get()));
        } catch (DataAccessException exception) {
            throw new IncidentDataAccessException(exception);
        }
    }

    private Optional<IncidentDetail> readDetail(String loginName, UUID incidentId) {
        List<IncidentCore> cores = jdbc.query("""
                SELECT i.id, i.version, i.title, i.description, i.category, i.status, i.anonymous,
                       CASE WHEN i.anonymous THEN 'Anonymous reporter' ELSE reporter.login_name END reporter_label,
                       assignee.login_name assignee_label, i.created_at, i.submitted_at, i.withdrawn_at,
                       i.queue_entered_at, i.first_assigned_at, i.latest_assigned_at,
                       (SELECT MAX(resolution.resolved_at) FROM incident_resolutions resolution
                         WHERE resolution.incident_id = i.id) latest_resolved_at
                  FROM incidents i
                  JOIN accounts reporter ON reporter.id = i.reporter_id
                  JOIN accounts administrator
                    ON administrator.login_name = :loginName
                   AND administrator.role = 'ADMINISTRATOR'
                   AND administrator.status = 'ENABLED'
             LEFT JOIN accounts assignee ON assignee.id = i.assignee_id
                 WHERE i.id = :incidentId
                """, Map.of("loginName", loginName, "incidentId", incidentId),
                (result, row) -> mapCore(result));
        if (cores.isEmpty()) return Optional.empty();

        IncidentCore core = cores.getFirst();
        List<ResolutionEntry> resolutions = jdbc.query("""
                SELECT resolution.cycle_number, resolution.resolved_at, resolver.login_name, resolution.remarks
                  FROM incident_resolutions resolution
                  JOIN accounts resolver ON resolver.id = resolution.resolved_by
                 WHERE resolution.incident_id = :incidentId
              ORDER BY resolution.cycle_number
                """, Map.of("incidentId", incidentId), (result, row) -> new ResolutionEntry(
                result.getInt("cycle_number"), instant(result, "resolved_at"),
                result.getString("login_name"), result.getString("remarks")));
        List<CommentEntry> comments = jdbc.query("""
                SELECT comment.id,
                       CASE WHEN incident.anonymous AND comment.author_id = incident.reporter_id
                            THEN 'Anonymous reporter' ELSE author.login_name END author_label,
                       comment.author_role, comment.text, comment.created_at
                  FROM incident_comments comment
                  JOIN incidents incident ON incident.id = comment.incident_id
                  JOIN accounts author ON author.id = comment.author_id
                 WHERE comment.incident_id = :incidentId
              ORDER BY comment.created_at, comment.id
                """, Map.of("incidentId", incidentId), (result, row) -> new CommentEntry(
                result.getObject("id", UUID.class), result.getString("author_label"),
                result.getString("author_role"), result.getString("text"), instant(result, "created_at")));
        List<AttachmentEntry> attachments = jdbc.query("""
                SELECT id, media_type, size_bytes, created_at
                  FROM incident_attachments
                 WHERE incident_id = :incidentId
              ORDER BY created_at, id
                """, Map.of("incidentId", incidentId), (result, row) -> new AttachmentEntry(
                result.getObject("id", UUID.class), "Attachment", result.getString("media_type"),
                result.getLong("size_bytes"), instant(result, "created_at")));
        List<EligibleResponder> responders = jdbc.query("""
                SELECT responder.id, responder.login_name
                  FROM accounts responder
                  JOIN responder_category_access access ON access.account_id = responder.id
                 WHERE responder.role = 'RESPONDER'
                   AND responder.status = 'ENABLED'
                   AND access.category = :category
              ORDER BY responder.login_name, responder.id
                """, Map.of("category", core.category()), (result, row) -> new EligibleResponder(
                result.getObject("id", UUID.class), result.getString("login_name")));
        return Optional.of(core.toDetail(sloProgress(core), resolutions, comments, attachments, responders));
    }

    private SloProgress sloProgress(IncidentCore incident) {
        Instant evaluationAt = incident.queueEnteredAt() == null ? incident.createdAt() : incident.queueEnteredAt();
        List<SloTarget> targets = jdbc.query("""
                SELECT average_time_to_claim_minutes, average_time_in_progress_minutes
                  FROM slo_target_versions
                 WHERE category = :category
                   AND effective_at <= :evaluationAt
              ORDER BY effective_at DESC, id DESC
                 LIMIT 1
                """, Map.of(
                "category", incident.category(),
                "evaluationAt", Timestamp.from(evaluationAt)),
                (result, row) -> new SloTarget(result.getLong(1), result.getLong(2)));
        if (targets.isEmpty()) return new SloProgress(null, null, null, null, "NOT_CONFIGURED", "NOT_CONFIGURED");
        SloTarget target = targets.getFirst();
        Instant now = clock.instant();
        Long claimElapsed = incident.queueEnteredAt() == null ? null : Duration.between(
                incident.queueEnteredAt(), incident.firstAssignedAt() == null ? now : incident.firstAssignedAt()).toMillis();
        Instant progressEnd = incident.latestResolvedAt() == null ? now : incident.latestResolvedAt();
        Long progressElapsed = incident.firstAssignedAt() == null ? null
                : Duration.between(incident.firstAssignedAt(), progressEnd).toMillis();
        return new SloProgress(target.claimMinutes(), target.progressMinutes(), claimElapsed, progressElapsed,
                state(claimElapsed, target.claimMinutes()), state(progressElapsed, target.progressMinutes()));
    }

    private CommentResult addCommentInTransaction(String loginName, UUID incidentId, UUID requestId, String text) {
        lockRequest(requestId);
        Optional<Actor> actor = enabledAdministrator(loginName);
        if (actor.isEmpty() || !incidentExists(incidentId)) return CommentResult.conflict();
        List<ExistingComment> prior = jdbc.query("""
                SELECT id, incident_id, author_id, text FROM incident_comments WHERE request_id = :requestId
                """, Map.of("requestId", requestId), (result, row) -> new ExistingComment(
                result.getObject("id", UUID.class), result.getObject("incident_id", UUID.class),
                result.getObject("author_id", UUID.class), result.getString("text")));
        if (!prior.isEmpty()) {
            ExistingComment existing = prior.getFirst();
            return existing.incidentId().equals(incidentId) && existing.authorId().equals(actor.get().id())
                    && existing.text().equals(text) ? new CommentResult(true, existing.id()) : CommentResult.conflict();
        }
        UUID commentId = identifiers.get();
        Instant occurredAt = clock.instant();
        jdbc.update("""
                INSERT INTO incident_comments (
                    id, request_id, incident_id, author_id, author_role, comment_type, text, created_at
                ) VALUES (:id, :requestId, :incidentId, :actorId, 'ADMINISTRATOR', 'ORDINARY', :text, :occurredAt)
                """, Map.of("id", commentId, "requestId", requestId, "incidentId", incidentId,
                "actorId", actor.get().id(), "text", text, "occurredAt", Timestamp.from(occurredAt)));
        insertAudit(actor.get(), incidentId, occurredAt, "INCIDENT_COMMENT_ADDED",
                "jsonb_build_object('commentId', ?)", commentId);
        return new CommentResult(true, commentId);
    }

    private MutationResult assignInTransaction(
            String loginName, UUID incidentId, long expectedVersion, UUID responderId) {
        Optional<Actor> actor = enabledAdministrator(loginName);
        Optional<LockedIncident> incident = lockedIncident(incidentId);
        if (actor.isEmpty() || incident.isEmpty() || incident.get().version() != expectedVersion
                || !(incident.get().status().equals("SUBMITTED") || incident.get().status().equals("ASSIGNED"))
                || responderId.equals(incident.get().assigneeId())
                || !eligibleResponder(responderId, incident.get().category())) return MutationResult.conflict();
        Instant occurredAt = clock.instant();
        String action = incident.get().assigneeId() == null ? "INCIDENT_ASSIGNED" : "INCIDENT_REASSIGNED";
        jdbc.update("""
                UPDATE incidents
                   SET status = 'ASSIGNED', assignee_id = :responderId,
                       first_assigned_at = COALESCE(first_assigned_at, :occurredAt),
                       latest_assigned_at = :occurredAt, version = version + 1
                 WHERE id = :incidentId
                """, Map.of("responderId", responderId, "occurredAt", Timestamp.from(occurredAt),
                "incidentId", incidentId));
        insertAudit(actor.get(), incidentId, occurredAt, action,
                "jsonb_build_object('status', jsonb_build_object('before', CAST(? AS text), 'after', 'ASSIGNED'), "
                        + "'assigneeId', jsonb_build_object('before', CAST(? AS text), 'after', CAST(? AS text)))",
                incident.get().status(), string(incident.get().assigneeId()), responderId.toString());
        return new MutationResult(true, expectedVersion + 1);
    }

    private MutationResult unassignInTransaction(String loginName, UUID incidentId, long expectedVersion) {
        Optional<Actor> actor = enabledAdministrator(loginName);
        Optional<LockedIncident> incident = lockedIncident(incidentId);
        if (actor.isEmpty() || incident.isEmpty() || incident.get().version() != expectedVersion
                || !incident.get().status().equals("ASSIGNED") || incident.get().assigneeId() == null) {
            return MutationResult.conflict();
        }
        Instant occurredAt = clock.instant();
        jdbc.update("""
                UPDATE incidents
                   SET status = 'SUBMITTED', assignee_id = NULL,
                       version = version + 1
                 WHERE id = :incidentId
                """, Map.of("incidentId", incidentId));
        insertAudit(actor.get(), incidentId, occurredAt, "INCIDENT_HANDED_OFF",
                "jsonb_build_object('status', jsonb_build_object('before', 'ASSIGNED', 'after', 'SUBMITTED'), "
                        + "'assigneeId', jsonb_build_object('before', CAST(? AS text), 'after', NULL))",
                incident.get().assigneeId().toString());
        return new MutationResult(true, expectedVersion + 1);
    }

    private ResolutionResult resolveInTransaction(
            String loginName, UUID incidentId, long expectedVersion, UUID requestId, String remarks) {
        lockRequest(requestId);
        Optional<Actor> actor = enabledAdministrator(loginName);
        if (actor.isEmpty()) return ResolutionResult.conflict();
        List<ExistingResolution> prior = jdbc.query("""
                SELECT resolution.incident_id, resolution.resolved_by, resolution.cycle_number,
                       resolution.remarks, incident.version
                  FROM incident_resolutions resolution
                  JOIN incidents incident ON incident.id = resolution.incident_id
                 WHERE resolution.request_id = :requestId
                """, Map.of("requestId", requestId), (result, row) -> new ExistingResolution(
                result.getObject("incident_id", UUID.class), result.getObject("resolved_by", UUID.class),
                result.getInt("cycle_number"), result.getString("remarks"), result.getLong("version")));
        if (!prior.isEmpty()) {
            ExistingResolution existing = prior.getFirst();
            return existing.incidentId().equals(incidentId) && existing.resolvedBy().equals(actor.get().id())
                    && existing.remarks().equals(remarks)
                    ? new ResolutionResult(true, existing.version(), existing.cycleNumber()) : ResolutionResult.conflict();
        }
        Optional<LockedIncident> incident = lockedIncident(incidentId);
        if (incident.isEmpty() || incident.get().version() != expectedVersion
                || !incident.get().status().equals("ASSIGNED") || incident.get().assigneeId() == null
                || incident.get().queueEnteredAt() == null || incident.get().firstAssignedAt() == null) {
            return ResolutionResult.conflict();
        }
        Instant occurredAt = clock.instant();
        int cycle = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COALESCE(MAX(cycle_number), 0) + 1 FROM incident_resolutions WHERE incident_id = :incidentId
                """, Map.of("incidentId", incidentId), Integer.class));
        UUID resolutionId = identifiers.get();
        jdbc.update("""
                UPDATE incidents SET status = 'RESOLVED', assignee_id = NULL, version = version + 1
                 WHERE id = :incidentId
                """, Map.of("incidentId", incidentId));
        jdbc.update("""
                INSERT INTO incident_resolutions (
                    id, request_id, incident_id, cycle_number, resolved_by, resolved_at, remarks,
                    queue_entered_at, first_assigned_at, responder_at_resolution
                ) VALUES (:id, :requestId, :incidentId, :cycle, :actorId, :occurredAt, :remarks,
                          :queueEnteredAt, :firstAssignedAt, :responderId)
                """, Map.of("id", resolutionId, "requestId", requestId, "incidentId", incidentId,
                "cycle", cycle, "actorId", actor.get().id(), "occurredAt", Timestamp.from(occurredAt),
                "remarks", remarks, "queueEnteredAt", Timestamp.from(incident.get().queueEnteredAt()),
                "firstAssignedAt", Timestamp.from(incident.get().firstAssignedAt()),
                "responderId", incident.get().assigneeId()));
        insertAudit(actor.get(), incidentId, occurredAt, "INCIDENT_RESOLVED",
                "jsonb_build_object('status', jsonb_build_object('before', 'ASSIGNED', 'after', 'RESOLVED'), "
                        + "'resolutionId', ?, 'resolutionCycle', ?)", resolutionId, cycle);
        return new ResolutionResult(true, expectedVersion + 1, cycle);
    }

    private Optional<Actor> enabledAdministrator(String loginName) {
        return jdbc.query("""
                SELECT id FROM accounts
                 WHERE login_name = :loginName AND role = 'ADMINISTRATOR' AND status = 'ENABLED'
                 FOR SHARE
                """, Map.of("loginName", loginName),
                (result, row) -> new Actor(result.getObject("id", UUID.class))).stream().findFirst();
    }

    private Optional<LockedIncident> lockedIncident(UUID incidentId) {
        return jdbc.query("""
                SELECT id, version, status, category, assignee_id, queue_entered_at, first_assigned_at
                  FROM incidents WHERE id = :incidentId FOR UPDATE
                """, Map.of("incidentId", incidentId), (result, row) -> new LockedIncident(
                result.getObject("id", UUID.class), result.getLong("version"), result.getString("status"),
                result.getString("category"), result.getObject("assignee_id", UUID.class),
                nullableInstant(result, "queue_entered_at"), nullableInstant(result, "first_assigned_at")))
                .stream().findFirst();
    }

    private boolean incidentExists(UUID incidentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM incidents WHERE id = :incidentId FOR SHARE)",
                Map.of("incidentId", incidentId), Boolean.class));
    }

    private boolean eligibleResponder(UUID responderId, String category) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM accounts responder
                    JOIN responder_category_access access ON access.account_id = responder.id
                     WHERE responder.id = :responderId AND responder.role = 'RESPONDER'
                       AND responder.status = 'ENABLED' AND access.category = :category
                )
                """, Map.of("responderId", responderId, "category", category), Boolean.class));
    }

    private void lockRequest(UUID requestId) {
        jdbc.queryForObject("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:requestKey, 0))) request_lock
                """, Map.of("requestKey", requestId.toString()), Integer.class);
    }

    private void insertAudit(
            Actor actor, UUID incidentId, Instant occurredAt, String action, String detailsExpression, Object... values) {
        Object[] parameters = new Object[5 + values.length];
        parameters[0] = identifiers.get();
        parameters[1] = Timestamp.from(occurredAt);
        parameters[2] = actor.id();
        parameters[3] = action;
        parameters[4] = incidentId.toString();
        System.arraycopy(values, 0, parameters, 5, values.length);
        jdbc.getJdbcTemplate().update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, ?, 'ADMINISTRATOR', ?, 'INCIDENT', ?, 'SUCCESS',
                """ + detailsExpression + ")", parameters);
    }

    private static IncidentCore mapCore(ResultSet result) throws SQLException {
        return new IncidentCore(result.getObject("id", UUID.class), result.getLong("version"),
                result.getString("title"), result.getString("description"), result.getString("category"),
                result.getString("status"), result.getBoolean("anonymous"), result.getString("reporter_label"),
                result.getString("assignee_label"), instant(result, "created_at"),
                nullableInstant(result, "submitted_at"), nullableInstant(result, "withdrawn_at"),
                nullableInstant(result, "queue_entered_at"), nullableInstant(result, "first_assigned_at"),
                nullableInstant(result, "latest_assigned_at"), nullableInstant(result, "latest_resolved_at"));
    }

    private static String state(Long elapsedMillis, Long targetMinutes) {
        if (elapsedMillis == null || targetMinutes == null) return "NOT_APPLICABLE";
        return elapsedMillis <= Duration.ofMinutes(targetMinutes).toMillis() ? "WITHIN_TARGET" : "OVERDUE";
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record Actor(UUID id) { }
    private record SloTarget(long claimMinutes, long progressMinutes) { }
    private record ExistingComment(UUID id, UUID incidentId, UUID authorId, String text) { }
    private record ExistingResolution(
            UUID incidentId, UUID resolvedBy, int cycleNumber, String remarks, long version) { }
    private record LockedIncident(
            UUID id, long version, String status, String category, UUID assigneeId,
            Instant queueEnteredAt, Instant firstAssignedAt) { }

    private record IncidentCore(
            UUID id, long version, String title, String description, String category, String status,
            boolean anonymous, String reporterLabel, String assigneeLabel, Instant createdAt, Instant submittedAt,
            Instant withdrawnAt, Instant queueEnteredAt, Instant firstAssignedAt, Instant latestAssignedAt,
            Instant latestResolvedAt) {
        IncidentDetail toDetail(
                SloProgress slo, List<ResolutionEntry> resolutions, List<CommentEntry> comments,
                List<AttachmentEntry> attachments, List<EligibleResponder> responders) {
            return new IncidentDetail(id, version, title, description, category, status, anonymous,
                    reporterLabel, assigneeLabel, createdAt, submittedAt, withdrawnAt, queueEnteredAt,
                    firstAssignedAt, latestAssignedAt, slo, resolutions, comments, attachments, responders);
        }
    }
}
