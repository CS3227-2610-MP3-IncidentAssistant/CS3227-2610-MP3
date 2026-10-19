package com.company.incidentdesk.web.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.application.account.PromotionWorkflowMutation;
import com.company.incidentdesk.application.account.PromotionWorkflowStore;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.PromotionRequestStatus;
import com.company.incidentdesk.domain.account.ResponderAccess;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.audit.AuditChange;
import com.company.incidentdesk.domain.audit.AuditEvent;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.persistence.AccountRepository;
import com.company.incidentdesk.persistence.AuditedMutation;
import com.company.incidentdesk.persistence.RepositoryException;
import com.company.incidentdesk.persistence.StorageFailureCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL account and promotion adapter; promotion/audit/session changes share one transaction. */
@Repository
@ConditionalOnBean(DataSource.class)
public class JdbcPromotionWorkflowStore implements AccountRepository, PromotionWorkflowStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public JdbcPromotionWorkflowStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions) {
        this(jdbc, transactions, Clock.systemUTC());
    }

    JdbcPromotionWorkflowStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Optional<Account> findById(AccountId accountId) {
        return findAccount("id", accountId.value());
    }

    @Override
    public Optional<Account> findByLoginName(String loginName) {
        return findAccount("login_name", loginName);
    }

    private Optional<Account> findAccount(String column, Object value) {
        if (!Set.of("id", "login_name").contains(column)) {
            throw new IllegalArgumentException("unsupported account lookup");
        }
        try {
            return jdbc.query("SELECT id, login_name, role, status FROM accounts WHERE " + column + " = ?",
                    (result, row) -> mapAccount(
                            result.getObject("id", UUID.class),
                            result.getString("login_name"),
                            result.getString("role"),
                            result.getString("status")), value)
                    .stream().findFirst();
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private Account mapAccount(UUID id, String loginName, String roleValue, String statusValue) {
        Role role = parse(Role.class, roleValue);
        AccountStatus status = parse(AccountStatus.class, statusValue);
        Set<IncidentCategory> categories = role == Role.RESPONDER
                ? categories(jdbc.queryForList(
                        "SELECT category FROM responder_category_access WHERE account_id = ? ORDER BY category",
                        String.class, id))
                : Set.of();
        return new Account(new AccountId(id), loginName, role, status, ResponderAccess.to(categories));
    }

    @Override
    public List<Account> findAll() {
        try {
            return jdbc.query("SELECT id, login_name, role, status FROM accounts ORDER BY login_name, id",
                    (result, row) -> mapAccount(
                            result.getObject("id", UUID.class), result.getString("login_name"),
                            result.getString("role"), result.getString("status")));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void create(Account account) {
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, ?)",
                        account.id().value(), account.loginName(), account.role().name(), account.status().name());
                replaceAccess(account);
            });
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void update(Account account) {
        try {
            transactions.executeWithoutResult(status -> {
                int changed = jdbc.update("UPDATE accounts SET login_name = ?, role = ?, status = ?, version = version + 1 WHERE id = ?",
                        account.loginName(), account.role().name(), account.status().name(), account.id().value());
                if (changed != 1) throw notFound("account");
                replaceAccess(account);
            });
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public Optional<ResponderPromotionRequest> findPromotionRequest(PromotionRequestId requestId) {
        try {
            return jdbc.query("""
                    SELECT id, requester_id, comments, requested_at, status, decided_by, decided_at
                      FROM responder_promotion_requests
                     WHERE id = ?
                    """, (result, row) -> mapRequest(result.getObject("id", UUID.class),
                    result.getObject("requester_id", UUID.class), result.getString("comments"),
                    result.getObject("requested_at", OffsetDateTime.class), result.getString("status"),
                    result.getObject("decided_by", UUID.class),
                    result.getObject("decided_at", OffsetDateTime.class)), requestId.value())
                    .stream().findFirst();
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public List<ResponderPromotionRequest> findPromotionRequests() {
        try {
            return jdbc.query("""
                    SELECT id, requester_id, comments, requested_at, status, decided_by, decided_at
                      FROM responder_promotion_requests
                     ORDER BY requested_at ASC, id ASC
                    """, (result, row) -> mapRequest(result.getObject("id", UUID.class),
                    result.getObject("requester_id", UUID.class), result.getString("comments"),
                    result.getObject("requested_at", OffsetDateTime.class), result.getString("status"),
                    result.getObject("decided_by", UUID.class),
                    result.getObject("decided_at", OffsetDateTime.class)));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public List<ResponderPromotionRequest> findPromotionRequestsByRequester(AccountId requesterId) {
        try {
            return jdbc.query("""
                    SELECT id, requester_id, comments, requested_at, status, decided_by, decided_at
                      FROM responder_promotion_requests
                     WHERE requester_id = ?
                     ORDER BY requested_at ASC, id ASC
                    """, (result, row) -> mapRequest(result.getObject("id", UUID.class),
                    result.getObject("requester_id", UUID.class), result.getString("comments"),
                    result.getObject("requested_at", OffsetDateTime.class), result.getString("status"),
                    result.getObject("decided_by", UUID.class),
                    result.getObject("decided_at", OffsetDateTime.class)), requesterId.value());
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public List<ResponderPromotionRequest> findPendingPromotionRequests(long offset, int limit) {
        try {
            return jdbc.query("""
                    SELECT id, requester_id, comments, requested_at, status, decided_by, decided_at
                      FROM responder_promotion_requests
                     WHERE status = 'PENDING'
                     ORDER BY requested_at ASC, id ASC
                     LIMIT ? OFFSET ?
                    """, (result, row) -> mapRequest(result.getObject("id", UUID.class),
                    result.getObject("requester_id", UUID.class), result.getString("comments"),
                    result.getObject("requested_at", OffsetDateTime.class), result.getString("status"),
                    result.getObject("decided_by", UUID.class),
                    result.getObject("decided_at", OffsetDateTime.class)), limit, offset);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public long countPendingPromotionRequests() {
        try {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM responder_promotion_requests WHERE status = 'PENDING'", Long.class);
            return count == null ? 0 : count;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean hasPendingPromotionRequest(AccountId requesterId) {
        try {
            Boolean exists = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM responder_promotion_requests
                                   WHERE requester_id = ? AND status = 'PENDING')
                    """, Boolean.class, requesterId.value());
            return Boolean.TRUE.equals(exists);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void commitPromotion(AuditedMutation<PromotionWorkflowMutation> mutation) {
        try {
            transactions.executeWithoutResult(status -> persist(mutation));
        } catch (DuplicateKeyException exception) {
            throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS,
                    "promotion request or audit record already exists", exception);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void persist(AuditedMutation<PromotionWorkflowMutation> mutation) {
        PromotionWorkflowMutation change = mutation.nextState();
        if (change.request().isPresent()) {
            ResponderPromotionRequest request = change.request().orElseThrow();
            if (request.status() == PromotionRequestStatus.PENDING) {
                insertRequest(request);
            } else {
                requireCurrentAdministrator(mutation.auditEvent(), request);
                updateDecision(request, change.updatedAccount());
            }
        } else if (change.updatedAccount().isPresent()) {
            updateAccountAndAccess(change.updatedAccount().orElseThrow(), false);
        }
        insertAudit(mutation.auditEvent());
    }

    private void requireCurrentAdministrator(
            AuditEvent audit,
            ResponderPromotionRequest request) {
        if (audit.actor().role() != Role.ADMINISTRATOR
                || request.decidedBy().filter(audit.actor().accountId()::equals).isEmpty()) {
            throw staleDecision();
        }
        List<AccountSecurityState> actorState = jdbc.query(
                "SELECT role, status FROM accounts WHERE id = ? FOR SHARE",
                (result, row) -> new AccountSecurityState(result.getString("role"), result.getString("status")),
                audit.actor().accountId().value());
        if (actorState.size() != 1 || !Role.ADMINISTRATOR.name().equals(actorState.getFirst().role())
                || !AccountStatus.ENABLED.name().equals(actorState.getFirst().status())) {
            throw staleDecision();
        }
    }

    private record AccountSecurityState(String role, String status) { }

    private void insertRequest(ResponderPromotionRequest request) {
        jdbc.update("""
                INSERT INTO responder_promotion_requests
                    (id, requester_id, comments, requested_at, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, request.id().value(), request.requesterId().value(), request.comments().orElse(null),
                Timestamp.from(request.requestedAt()));
        for (IncidentCategory category : request.requestedCategories().stream().sorted().toList()) {
            jdbc.update("INSERT INTO responder_promotion_requested_categories (request_id, category) VALUES (?, ?)",
                    request.id().value(), category.name());
        }
    }

    private void updateDecision(ResponderPromotionRequest request, Optional<Account> updatedAccount) {
        ResponderPromotionRequest persisted = loadPendingForUpdate(request.id());
        if (!persisted.requesterId().equals(request.requesterId())
                || !persisted.requestedCategories().equals(request.requestedCategories())) {
            throw staleDecision();
        }
        int updated = jdbc.update("""
                UPDATE responder_promotion_requests
                   SET status = ?, decided_by = ?, decided_at = ?, version = version + 1
                 WHERE id = ? AND status = 'PENDING'
                """, request.status().name(), request.decidedBy().orElseThrow().value(),
                Timestamp.from(request.decidedAt().orElseThrow()), request.id().value());
        if (updated != 1) throw staleDecision();

        for (IncidentCategory category : request.approvedCategories().orElse(Set.of()).stream().sorted().toList()) {
            jdbc.update("""
                    INSERT INTO responder_promotion_approved_categories (request_id, category)
                    VALUES (?, ?)
                    """, request.id().value(), category.name());
        }
        if (updatedAccount.isPresent()) {
            updateAccountAndAccess(updatedAccount.orElseThrow(), true);
        }
    }

    private ResponderPromotionRequest loadPendingForUpdate(PromotionRequestId requestId) {
        return jdbc.query("""
                SELECT id, requester_id, comments, requested_at, status, decided_by, decided_at
                  FROM responder_promotion_requests
                 WHERE id = ?
                 FOR UPDATE
                """, (result, row) -> mapRequest(result.getObject("id", UUID.class),
                result.getObject("requester_id", UUID.class), result.getString("comments"),
                result.getObject("requested_at", OffsetDateTime.class), result.getString("status"),
                result.getObject("decided_by", UUID.class),
                result.getObject("decided_at", OffsetDateTime.class)), requestId.value())
                .stream().filter(request -> request.status() == PromotionRequestStatus.PENDING)
                .findFirst().orElseThrow(JdbcPromotionWorkflowStore::staleDecision);
    }

    private void updateAccountAndAccess(Account account, boolean promotion) {
        String sql = promotion
                ? "UPDATE accounts SET role = 'RESPONDER', status = 'ENABLED', version = version + 1 WHERE id = ? AND role = 'REPORTER' AND status = 'ENABLED'"
                : "UPDATE accounts SET login_name = ?, role = ?, status = ?, version = version + 1 WHERE id = ?";
        int changed = promotion
                ? jdbc.update(sql, account.id().value())
                : jdbc.update(sql, account.loginName(), account.role().name(), account.status().name(), account.id().value());
        if (changed != 1) throw staleDecision();
        if (promotion) {
            jdbc.update("DELETE FROM responder_category_access WHERE account_id = ?", account.id().value());
            for (IncidentCategory category : account.responderAccess().categories().stream().sorted().toList()) {
                jdbc.update("INSERT INTO responder_category_access (account_id, category, granted_at) VALUES (?, ?, ?)",
                        account.id().value(), category.name(), Timestamp.from(clock.instant()));
            }
            String loginName = jdbc.queryForObject(
                    "SELECT login_name FROM accounts WHERE id = ?", String.class, account.id().value());
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", loginName);
        } else {
            replaceAccess(account);
        }
    }

    private void replaceAccess(Account account) {
        jdbc.update("DELETE FROM responder_category_access WHERE account_id = ?", account.id().value());
        if (account.role() == Role.RESPONDER && account.isEnabled()) {
            for (IncidentCategory category : account.responderAccess().categories().stream().sorted().toList()) {
                jdbc.update("INSERT INTO responder_category_access (account_id, category, granted_at) VALUES (?, ?, ?)",
                        account.id().value(), category.name(), Timestamp.from(clock.instant()));
            }
        }
    }

    private ResponderPromotionRequest mapRequest(
            UUID id, UUID requesterId, String comments, OffsetDateTime requestedAt,
            String statusValue, UUID decidedBy, OffsetDateTime decidedAt) {
        PromotionRequestId requestId = new PromotionRequestId(id);
        Set<IncidentCategory> requested = categories(jdbc.queryForList(
                "SELECT category FROM responder_promotion_requested_categories WHERE request_id = ? ORDER BY category",
                String.class, id));
        PromotionRequestStatus status = parse(PromotionRequestStatus.class, statusValue);
        Set<IncidentCategory> approved = categories(jdbc.queryForList(
                "SELECT category FROM responder_promotion_approved_categories WHERE request_id = ? ORDER BY category",
                String.class, id));
        Optional<Set<IncidentCategory>> approvedOption = status == PromotionRequestStatus.PENDING
                ? Optional.empty() : Optional.of(approved);
        return new ResponderPromotionRequest(
                requestId, new AccountId(requesterId), requested, Optional.ofNullable(comments),
                requestedAt.toInstant(), status,
                Optional.ofNullable(decidedBy).map(AccountId::new),
                Optional.ofNullable(decidedAt).map(OffsetDateTime::toInstant), approvedOption);
    }

    private void insertAudit(AuditEvent audit) {
        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, audit.id().value(), Timestamp.from(audit.occurredAt()),
                audit.actor().accountId().value(), audit.actor().role().name(), audit.action().name(),
                audit.target().type().name(), audit.target().identifier(), audit.outcome().name(),
                auditDetails(audit.changes()));
    }

    private static String auditDetails(List<AuditChange> changes) {
        List<String> entries = new ArrayList<>();
        for (AuditChange change : changes) {
            entries.add("{\"field\":\"" + change.field().name() + "\",\"before\":"
                    + jsonString(change.beforeValue().orElse(null)) + ",\"after\":"
                    + jsonString(change.afterValue().orElse(null)) + "}");
        }
        return "{\"changes\":[" + String.join(",", entries) + "]}";
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static Set<IncidentCategory> categories(List<String> values) {
        EnumSet<IncidentCategory> categories = EnumSet.noneOf(IncidentCategory.class);
        for (String value : values) categories.add(parse(IncidentCategory.class, value));
        return Set.copyOf(categories);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new RepositoryException(StorageFailureCode.CORRUPT_DATA,
                    "promotion storage contains an unknown enum value", exception);
        }
    }

    private static RepositoryException staleDecision() {
        return new RepositoryException(StorageFailureCode.ALREADY_EXISTS,
                "promotion request is no longer pending or account state changed");
    }

    private static RepositoryException notFound(String resource) {
        return new RepositoryException(StorageFailureCode.NOT_FOUND, resource + " does not exist");
    }

    private static RepositoryException unavailable(DataAccessException exception) {
        return new RepositoryException(StorageFailureCode.STORAGE_UNAVAILABLE,
                "promotion storage is unavailable", exception);
    }
}
