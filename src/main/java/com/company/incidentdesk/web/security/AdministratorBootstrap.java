package com.company.incidentdesk.web.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Performs the explicitly configured, one-time first-administrator bootstrap. */
@Component
@ConditionalOnBean(DataSource.class)
public final class AdministratorBootstrap implements ApplicationRunner {
    private static final String BOOTSTRAP_MARKER = "administrator-bootstrap-completed";
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final Clock clock;

    @Autowired
    public AdministratorBootstrap(
            Environment environment,
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            PasswordEncoder passwords) {
        this(environment, jdbc, transactions, passwords, Clock.systemUTC());
    }

    AdministratorBootstrap(
            Environment environment,
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            PasswordEncoder passwords,
            Clock clock) {
        this.environment = environment;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Optional<String> configuredLogin = nonBlank("INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN");
        Optional<String> configuredPassword = nonBlank("INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD");
        if (configuredLogin.isEmpty() && configuredPassword.isEmpty()) {
            return;
        }
        if (configuredLogin.isEmpty() || configuredPassword.isEmpty()) {
            String missingVariable = configuredLogin.isEmpty()
                    ? "INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN"
                    : "INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD";
            throw new IllegalStateException("Invalid administrator bootstrap configuration: "
                    + missingVariable + " is missing. No account was created.");
        }

        String login = configuredLogin.orElseThrow();
        String password = configuredPassword.orElseThrow();
        validate(login, password);
        BootstrapOutcome outcome = transactions.execute(status -> bootstrap(login, password));
        if (outcome != BootstrapOutcome.CREATED) {
            throw new IllegalStateException("Administrator bootstrap has already been used or cannot be applied");
        }
    }

    private BootstrapOutcome bootstrap(String login, String password) {
        Instant now = clock.instant();
        boolean administratorExists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM accounts WHERE role = 'ADMINISTRATOR')",
                Boolean.class));
        if (administratorExists) {
            audit("APPLICATION_DATA", "administrator-bootstrap",
                    "ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE", now,
                    Map.of("reason", "administrator_already_exists"));
            return BootstrapOutcome.ALREADY_COMPLETED;
        }

        int markerReserved = jdbc.update("""
                INSERT INTO application_settings (setting_key, setting_value, changed_at)
                VALUES (?, 'pending', ?)
                ON CONFLICT (setting_key) DO NOTHING
                """, BOOTSTRAP_MARKER, Timestamp.from(now));
        if (markerReserved == 0) {
            audit("APPLICATION_DATA", "administrator-bootstrap",
                    "ADMINISTRATOR_BOOTSTRAP_REJECTED", "FAILURE", now,
                    Map.of("reason", "already_completed"));
            return BootstrapOutcome.ALREADY_COMPLETED;
        }

        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, 'ADMINISTRATOR', 'ENABLED')",
                accountId, login);
        jdbc.update("INSERT INTO account_credentials (account_id, password_hash) VALUES (?, ?)",
                accountId, passwords.encode(password));
        int markerUpdated = jdbc.update("UPDATE application_settings SET setting_value = ? WHERE setting_key = ?",
                accountId.toString(), BOOTSTRAP_MARKER);
        if (markerUpdated != 1) {
            throw new IllegalStateException("Administrator bootstrap marker could not be finalized");
        }
        audit("ACCOUNT", accountId.toString(), "ADMINISTRATOR_BOOTSTRAPPED", "SUCCESS", now,
                Map.of("role", "ADMINISTRATOR"));
        return BootstrapOutcome.CREATED;
    }

    private void audit(
            String targetType,
            String targetIdentifier,
            String action,
            String outcome,
            Instant occurredAt,
            Map<String, String> details) {
        jdbc.update("""
                INSERT INTO audit_events (
                    id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details
                ) VALUES (?, ?, NULL, 'SYSTEM', ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                UUID.randomUUID(), Timestamp.from(occurredAt), action,
                targetType, targetIdentifier, outcome, toJson(details));
    }

    private Optional<String> nonBlank(String name) {
        return Optional.ofNullable(environment.getProperty(name)).filter(value -> !value.isBlank());
    }

    private static void validate(String login, String password) {
        if (login.isBlank() || login.length() > 128) {
            throw new IllegalStateException("Invalid INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN: expected 1 to 128 "
                    + "characters. No account was created.");
        }
        if (password.length() < 12 || password.length() > 128) {
            throw new IllegalStateException("Invalid INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD: expected 12 to 128 "
                    + "characters. No account was created.");
        }
    }

    private static String toJson(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private enum BootstrapOutcome {
        CREATED,
        ALREADY_COMPLETED
    }
}
