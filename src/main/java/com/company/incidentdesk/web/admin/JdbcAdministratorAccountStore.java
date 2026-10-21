package com.company.incidentdesk.web.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Locks and reauthorizes the acting Administrator before any account tombstone. */
@Repository
@ConditionalOnBean(DataSource.class)
class JdbcAdministratorAccountStore implements AdministratorAccountStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Runnable actorLockedHook;

    @Autowired
    JdbcAdministratorAccountStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this(jdbc, transactions, () -> { });
    }

    JdbcAdministratorAccountStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            Runnable actorLockedHook) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.actorLockedHook = actorLockedHook;
    }

    @Override
    public AccountDirectoryResult findAccounts(String administratorLoginName, long offset, int limit) {
        try {
            return transactions.execute(status -> {
                if (!lockEnabledAdministrator(administratorLoginName)) return AccountDirectoryResult.unavailable();
                List<AccountDirectoryEntry> entries = jdbc.query("""
                        SELECT id, login_name, role, status
                          FROM accounts
                         WHERE status <> 'DELETED'
                         ORDER BY login_name ASC, id ASC
                         LIMIT ? OFFSET ?
                        """, (result, row) -> new AccountDirectoryEntry(
                        result.getObject("id", UUID.class),
                        result.getString("login_name"),
                        result.getString("role"),
                        result.getString("status"),
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
                SELECT login_name, status
                  FROM accounts
                 WHERE id = ?
                 FOR UPDATE
                """, (result, row) -> new TargetState(result.getString("login_name"), result.getString("status")),
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

    private record ActorState(UUID id, String role, String status) {
        boolean isEnabledAdministrator() {
            return Role.ADMINISTRATOR.name().equals(role) && AccountStatus.ENABLED.name().equals(status);
        }
    }

    private record TargetState(String loginName, String status) { }
}
