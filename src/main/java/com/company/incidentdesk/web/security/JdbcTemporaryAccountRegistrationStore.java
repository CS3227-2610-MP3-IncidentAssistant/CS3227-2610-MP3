package com.company.incidentdesk.web.security;

import java.sql.Timestamp;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.web.security.TemporaryAccountRegistrationService.RegistrationOutcome;

/** PostgreSQL adapter that commits an interim account and audit atomically. */
@Repository
@ConditionalOnBean(DataSource.class)
public class JdbcTemporaryAccountRegistrationStore implements TemporaryAccountRegistrationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcTemporaryAccountRegistrationStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public RegistrationOutcome register(TemporaryAccountRegistration registration) {
        try {
            transactions.executeWithoutResult(status -> persist(registration));
            return RegistrationOutcome.CREATED;
        } catch (DuplicateKeyException exception) {
            return RegistrationOutcome.DUPLICATE;
        } catch (DataAccessException exception) {
            return RegistrationOutcome.UNAVAILABLE;
        }
    }

    private void persist(TemporaryAccountRegistration registration) {
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, 'ENABLED')",
                registration.accountId(), registration.loginName(), registration.role().name());
        jdbc.update("INSERT INTO account_credentials (account_id, password_hash) VALUES (?, ?)",
                registration.accountId(), registration.passwordHash());
        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, ?, ?, 'ACCOUNT_REGISTERED', 'ACCOUNT', ?, 'SUCCESS', CAST(? AS jsonb))
                """,
                registration.auditId(), Timestamp.from(registration.occurredAt()), registration.accountId(),
                registration.role().name(), registration.accountId().toString(),
                "{\"role\":\"" + registration.role().name() + "\"}");
    }
}
