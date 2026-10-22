package com.company.incidentdesk.web.admin;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Locks and reauthorizes the acting Administrator before account mutations. */
@Repository
@ConditionalOnBean(DataSource.class)
class JdbcAdministratorAccountStore implements AdministratorAccountStore {
    static final Duration TEMPORARY_PASSWORD_LIFETIME = Duration.ofHours(24);
    private static final char[] TEMPORARY_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*?".toCharArray();
    private static final int TEMPORARY_PASSWORD_LENGTH = 24;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final Runnable actorLockedHook;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    JdbcAdministratorAccountStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            PasswordEncoder passwords) {
        this(jdbc, transactions, passwords, () -> { });
    }

    JdbcAdministratorAccountStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this(jdbc, transactions, new BCryptPasswordEncoder(12), () -> { });
    }

    JdbcAdministratorAccountStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            Runnable actorLockedHook) {
        this(jdbc, transactions, new BCryptPasswordEncoder(12), actorLockedHook);
    }

    JdbcAdministratorAccountStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            PasswordEncoder passwords,
            Runnable actorLockedHook) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.passwords = passwords;
        this.actorLockedHook = actorLockedHook;
    }

    @Override
    public AccountDirectoryResult findAccounts(String administratorLoginName, long offset, int limit) {
        try {
            return transactions.execute(status -> {
                if (!lockEnabledAdministrator(administratorLoginName)) return AccountDirectoryResult.unavailable();
                List<AccountDirectoryEntry> entries = jdbc.query("""
                        SELECT id, login_name, role, status, version
                          FROM accounts
                         WHERE status <> 'DELETED'
                         ORDER BY login_name ASC, id ASC
                         LIMIT ? OFFSET ?
                        """, (result, row) -> new AccountDirectoryEntry(
                        result.getObject("id", UUID.class),
                        result.getString("login_name"),
                        result.getString("role"),
                        result.getString("status"),
                        result.getLong("version"),
                        categories(result.getObject("id", UUID.class))), limit, offset);
                Long total = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM accounts WHERE status <> 'DELETED'", Long.class);
                return new AccountDirectoryResult(true, entries, total == null ? 0 : total);
            });
        } catch (DataAccessException exception) {
            return AccountDirectoryResult.unavailable();
        }
    }

    @Override
    public AccountTombstoneOutcome tombstone(
            String administratorLoginName,
            UUID targetAccountId,
            UUID auditEventId,
            Instant occurredAt) {
        try {
            AccountTombstoneOutcome outcome = transactions.execute(status ->
                    tombstoneInTransaction(administratorLoginName, targetAccountId, auditEventId, occurredAt));
            return outcome == null ? AccountTombstoneOutcome.PERSISTENCE_FAILURE : outcome;
        } catch (PessimisticLockingFailureException exception) {
            return AccountTombstoneOutcome.CONFLICT;
        } catch (DataAccessException exception) {
            return AccountTombstoneOutcome.PERSISTENCE_FAILURE;
        }
    }

    @Override
    public TemporaryPasswordResetOutcome resetTemporaryPassword(
            String administratorLoginName,
            UUID targetAccountId,
            UUID auditEventId,
            Instant occurredAt) {
        try {
            TemporaryPasswordResetOutcome outcome = transactions.execute(status ->
                    resetTemporaryPasswordInTransaction(
                            administratorLoginName, targetAccountId, auditEventId, occurredAt));
            return outcome == null
                    ? TemporaryPasswordResetOutcome.failure(TemporaryPasswordResetOutcome.Status.PERSISTENCE_FAILURE)
                    : outcome;
        } catch (PessimisticLockingFailureException exception) {
            return TemporaryPasswordResetOutcome.failure(TemporaryPasswordResetOutcome.Status.CONFLICT);
        } catch (DataAccessException exception) {
            return TemporaryPasswordResetOutcome.failure(TemporaryPasswordResetOutcome.Status.PERSISTENCE_FAILURE);
        }
    }

    private TemporaryPasswordResetOutcome resetTemporaryPasswordInTransaction(
            String administratorLoginName,
            UUID targetAccountId,
            UUID auditEventId,
            Instant occurredAt) {
        List<ActorState> actors = jdbc.query("""
                SELECT id, role, status
                  FROM accounts
                 WHERE login_name = ?
                 FOR UPDATE
                """, (result, row) -> new ActorState(
                result.getObject("id", UUID.class), result.getString("role"), result.getString("status")),
                administratorLoginName);
        if (actors.size() != 1 || !actors.getFirst().isEnabledAdministrator()) {
            return TemporaryPasswordResetOutcome.failure(TemporaryPasswordResetOutcome.Status.RESOURCE_UNAVAILABLE);
        }
        ActorState actor = actors.getFirst();
        if (actor.id().equals(targetAccountId)) {
            return TemporaryPasswordResetOutcome.failure(TemporaryPasswordResetOutcome.Status.INVALID_STATE);
        }

        actorLockedHook.run();
        List<TargetState> targets = jdbc.query("""
                SELECT login_name, role, status, version
                  FROM accounts
                 WHERE id = ?
                 FOR UPDATE
                """, (result, row) -> new TargetState(
                result.getString("login_name"), result.getString("role"), result.getString("status"),
                result.getLong("version")),
                targetAccountId);
        if (targets.size() != 1 || !AccountStatus.ENABLED.name().equals(targets.getFirst().status())) {
            return TemporaryPasswordResetOutcome.failure(TemporaryPasswordResetOutcome.Status.RESOURCE_UNAVAILABLE);
        }
        TargetState target = targets.getFirst();

        String temporaryPassword = generateTemporaryPassword();
        int changed = jdbc.update("""
                UPDATE account_credentials
                   SET password_hash = ?, temporary = TRUE, expires_at = ?
                 WHERE account_id = ?
                """, passwords.encode(temporaryPassword),
                Timestamp.from(occurredAt.plus(TEMPORARY_PASSWORD_LIFETIME)), targetAccountId);
        if (changed != 1) {
            return TemporaryPasswordResetOutcome.failure(TemporaryPasswordResetOutcome.Status.RESOURCE_UNAVAILABLE);
        }
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", target.loginName());
        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, ?, 'ADMINISTRATOR', 'PASSWORD_RESET_INITIATED',
                    'ACCOUNT', ?, 'SUCCESS', CAST(? AS jsonb))
                """, auditEventId, Timestamp.from(occurredAt), actor.id(), targetAccountId.toString(),
                "{\"changes\":[{\"field\":\"ACCOUNT_CREDENTIAL\",\"before\":\"ACTIVE\",\"after\":\"TEMPORARY\"}]}");
        return TemporaryPasswordResetOutcome.reset(temporaryPassword);
    }

    private String generateTemporaryPassword() {
        StringBuilder password = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
        for (int index = 0; index < TEMPORARY_PASSWORD_LENGTH; index++) {
            password.append(TEMPORARY_PASSWORD_ALPHABET[random.nextInt(TEMPORARY_PASSWORD_ALPHABET.length)]);
        }
        return password.toString();
    }

    @Override
    public ResponderCategoryAccessOutcome replaceResponderCategories(
            String administratorLoginName,
            UUID targetAccountId,
            long expectedVersion,
            Set<IncidentCategory> categories,
            UUID auditEventId,
            Instant occurredAt) {
        try {
            ResponderCategoryAccessOutcome outcome = transactions.execute(status -> replaceResponderCategoriesInTransaction(
                    administratorLoginName, targetAccountId, expectedVersion, categories, auditEventId, occurredAt));
            return outcome == null ? ResponderCategoryAccessOutcome.PERSISTENCE_FAILURE : outcome;
        } catch (DataAccessException exception) {
            return ResponderCategoryAccessOutcome.PERSISTENCE_FAILURE;
        }
    }

    private ResponderCategoryAccessOutcome replaceResponderCategoriesInTransaction(
            String administratorLoginName,
            UUID targetAccountId,
            long expectedVersion,
            Set<IncidentCategory> categories,
            UUID auditEventId,
            Instant occurredAt) {
        List<ActorState> actors = jdbc.query("""
                SELECT id, role, status
                  FROM accounts
                 WHERE login_name = ?
                 FOR SHARE
                """, (result, row) -> new ActorState(
                result.getObject("id", UUID.class), result.getString("role"), result.getString("status")),
                administratorLoginName);
        if (actors.size() != 1 || !actors.getFirst().isEnabledAdministrator()) {
            return ResponderCategoryAccessOutcome.RESOURCE_UNAVAILABLE;
        }
        ActorState actor = actors.getFirst();

        List<TargetState> targets = jdbc.query("""
                SELECT login_name, role, status, version
                  FROM accounts
                 WHERE id = ?
                 FOR UPDATE
                """, (result, row) -> new TargetState(
                result.getString("login_name"), result.getString("role"), result.getString("status"),
                result.getLong("version")),
                targetAccountId);
        if (targets.size() != 1 || !targets.getFirst().isEnabledResponder()) {
            return ResponderCategoryAccessOutcome.RESOURCE_UNAVAILABLE;
        }
        TargetState target = targets.getFirst();
        if (target.version() != expectedVersion) return ResponderCategoryAccessOutcome.CONFLICT;
        Set<IncidentCategory> current = categorySet(targetAccountId);
        if (current.equals(categories)) return ResponderCategoryAccessOutcome.UNCHANGED;

        int changed = jdbc.update("""
                UPDATE accounts
                   SET version = version + 1
                 WHERE id = ? AND role = 'RESPONDER' AND status = 'ENABLED'
                """, targetAccountId);
        if (changed != 1) return ResponderCategoryAccessOutcome.RESOURCE_UNAVAILABLE;

        jdbc.update("DELETE FROM responder_category_access WHERE account_id = ?", targetAccountId);
        for (IncidentCategory category : categories.stream().sorted().toList()) {
            jdbc.update("""
                    INSERT INTO responder_category_access (account_id, category, granted_at)
                    VALUES (?, ?, ?)
                    """, targetAccountId, category.name(), Timestamp.from(occurredAt));
        }
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", target.loginName());
        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, ?, 'ADMINISTRATOR', 'RESPONDER_ACCESS_CHANGED',
                    'ACCOUNT', ?, 'SUCCESS', CAST(? AS jsonb))
                """, auditEventId, Timestamp.from(occurredAt), actor.id(), targetAccountId.toString(),
                categoryAuditDetails(current, categories));
        return ResponderCategoryAccessOutcome.UPDATED;
    }

    private AccountTombstoneOutcome tombstoneInTransaction(
            String administratorLoginName,
            UUID targetAccountId,
            UUID auditEventId,
            Instant occurredAt) {
        List<ActorState> actors = jdbc.query("""
                SELECT id, role, status
                  FROM accounts
                 WHERE login_name = ?
                 FOR UPDATE
                """, (result, row) -> new ActorState(
                result.getObject("id", UUID.class), result.getString("role"), result.getString("status")),
                administratorLoginName);
        if (actors.size() != 1 || !actors.getFirst().isEnabledAdministrator()) {
            return AccountTombstoneOutcome.RESOURCE_UNAVAILABLE;
        }
        ActorState actor = actors.getFirst();
        if (actor.id().equals(targetAccountId)) return AccountTombstoneOutcome.INVALID_STATE;

        actorLockedHook.run();
        List<TargetState> targets = jdbc.query("""
                SELECT login_name, role, status, version
                  FROM accounts
                 WHERE id = ?
                 FOR UPDATE
                """, (result, row) -> new TargetState(
                result.getString("login_name"), result.getString("role"), result.getString("status"),
                result.getLong("version")),
                targetAccountId);
        if (targets.size() != 1 || !AccountStatus.ENABLED.name().equals(targets.getFirst().status())) {
            return AccountTombstoneOutcome.RESOURCE_UNAVAILABLE;
        }
        TargetState target = targets.getFirst();

        int changed = jdbc.update("""
                UPDATE accounts
                   SET login_name = ?, status = 'DELETED', version = version + 1
                 WHERE id = ? AND status = 'ENABLED'
                """, "deleted:" + targetAccountId, targetAccountId);
        if (changed != 1) return AccountTombstoneOutcome.CONFLICT;
        jdbc.update("DELETE FROM responder_category_access WHERE account_id = ?", targetAccountId);
        jdbc.update("DELETE FROM account_credentials WHERE account_id = ?", targetAccountId);
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", target.loginName());
        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, ?, 'ADMINISTRATOR', 'ACCOUNT_DELETED',
                    'ACCOUNT', ?, 'SUCCESS', CAST(? AS jsonb))
                """, auditEventId, Timestamp.from(occurredAt), actor.id(), targetAccountId.toString(),
                "{\"changes\":[{\"field\":\"ACCOUNT_STATUS\",\"before\":\"ENABLED\",\"after\":\"DELETED\"}]}");
        return AccountTombstoneOutcome.TOMBSTONED;
    }

    private boolean lockEnabledAdministrator(String loginName) {
        List<ActorState> actors = jdbc.query("""
                SELECT id, role, status FROM accounts
                 WHERE login_name = ?
                 FOR SHARE
                """, (result, row) -> new ActorState(
                result.getObject("id", UUID.class), result.getString("role"), result.getString("status")),
                loginName);
        return actors.size() == 1 && actors.getFirst().isEnabledAdministrator();
    }

    private List<String> categories(UUID accountId) {
        return jdbc.queryForList("""
                SELECT category FROM responder_category_access
                 WHERE account_id = ? ORDER BY category ASC
                """, String.class, accountId);
    }

    private Set<IncidentCategory> categorySet(UUID accountId) {
        EnumSet<IncidentCategory> categories = EnumSet.noneOf(IncidentCategory.class);
        for (String value : categories(accountId)) categories.add(IncidentCategory.valueOf(value));
        return Set.copyOf(categories);
    }

    private static String categoryAuditDetails(
            Set<IncidentCategory> before,
            Set<IncidentCategory> after) {
        return "{\"changes\":[{\"field\":\"RESPONDER_CATEGORIES\",\"before\":\""
                + formatCategories(before) + "\",\"after\":\"" + formatCategories(after) + "\"}]}";
    }

    private static String formatCategories(Set<IncidentCategory> categories) {
        return categories.isEmpty() ? "none" : String.join(",", categories.stream().sorted().map(Enum::name).toList());
    }

    private record ActorState(UUID id, String role, String status) {
        boolean isEnabledAdministrator() {
            return Role.ADMINISTRATOR.name().equals(role) && AccountStatus.ENABLED.name().equals(status);
        }
    }

    private record TargetState(String loginName, String role, String status, long version) {
        boolean isEnabledResponder() {
            return Role.RESPONDER.name().equals(role) && AccountStatus.ENABLED.name().equals(status);
        }
    }
}
