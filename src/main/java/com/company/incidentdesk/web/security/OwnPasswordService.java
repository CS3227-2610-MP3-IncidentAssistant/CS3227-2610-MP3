package com.company.incidentdesk.web.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replaces the authenticated account's own password, clearing any temporary marker and expiry,
 * and commits the change with its audit record in one transaction.
 */
@Repository
@ConditionalOnBean(DataSource.class)
class OwnPasswordService {
    static final int MAX_PASSWORD_LENGTH = 128;
    static final int MIN_PASSWORD_LENGTH = 12;

    enum Outcome { CHANGED, REJECTED, RESOURCE_UNAVAILABLE, PERSISTENCE_FAILURE }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final Clock clock;

    OwnPasswordService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            PasswordEncoder passwords,
            @Qualifier(WebSecurityConfiguration.SECURITY_CLOCK) Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static boolean isAcceptablePassword(String password) {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH
                && password.length() <= MAX_PASSWORD_LENGTH && !password.isBlank();
    }

    Outcome changeOwnPassword(String loginName, String currentPassword, String newPassword) {
        Objects.requireNonNull(loginName, "loginName");
        if (!isAcceptablePassword(newPassword)) return Outcome.REJECTED;
        Instant occurredAt = clock.instant();
        try {
            Outcome outcome = transactions.execute(status ->
                    changeInTransaction(loginName, currentPassword, newPassword, occurredAt));
            return outcome == null ? Outcome.PERSISTENCE_FAILURE : outcome;
        } catch (DataAccessException exception) {
            return Outcome.PERSISTENCE_FAILURE;
        }
    }

    private Outcome changeInTransaction(
            String loginName, String currentPassword, String newPassword, Instant occurredAt) {
        List<AccountCredential> rows = jdbc.query("""
                SELECT a.id, a.role, a.status, c.password_hash
                  FROM accounts a
                  JOIN account_credentials c ON c.account_id = a.id
                 WHERE a.login_name = ?
                 FOR UPDATE OF a, c
                """, (result, row) -> new AccountCredential(
                result.getObject("id", UUID.class), result.getString("role"),
                result.getString("status"), result.getString("password_hash")),
                loginName);
        if (rows.size() != 1 || !"ENABLED".equals(rows.getFirst().status())) {
            return Outcome.RESOURCE_UNAVAILABLE;
        }
        AccountCredential account = rows.getFirst();
        if (!passwords.matches(currentPassword, account.passwordHash())) return Outcome.REJECTED;

        int changed = jdbc.update("""
                UPDATE account_credentials
                   SET password_hash = ?, temporary = FALSE, expires_at = NULL
                 WHERE account_id = ?
                """, passwords.encode(newPassword), account.id());
        if (changed != 1) return Outcome.RESOURCE_UNAVAILABLE;

        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, ?, ?, 'PASSWORD_CHANGED', 'ACCOUNT', ?, 'SUCCESS', CAST(? AS jsonb))
                """, UUID.randomUUID(), Timestamp.from(occurredAt), account.id(), account.role(),
                account.id().toString(),
                "{\"changes\":[{\"field\":\"ACCOUNT_CREDENTIAL\",\"before\":\"TEMPORARY_OR_ACTIVE\",\"after\":\"ACTIVE\"}]}");
        return Outcome.CHANGED;
    }

    private record AccountCredential(UUID id, String role, String status, String passwordHash) { }
}
