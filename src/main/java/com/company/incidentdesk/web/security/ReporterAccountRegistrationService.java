package com.company.incidentdesk.web.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

import com.company.incidentdesk.domain.account.Role;

/**
 * Public Reporter account-registration service.
 *
 * <p>The HTTP boundary supplies no role; public registration always creates a
 * Reporter as required by the approved Reporter slice.</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
final class ReporterAccountRegistrationService {
    static final int MAX_LOGIN_NAME_LENGTH = 128;
    static final int MAX_PASSWORD_LENGTH = 128;

    private final ReporterAccountRegistrationStore store;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    @Autowired
    ReporterAccountRegistrationService(
            ReporterAccountRegistrationStore store,
            PasswordEncoder passwords) {
        this(store, passwords, Clock.systemUTC(), UUID::randomUUID);
    }

    ReporterAccountRegistrationService(
            ReporterAccountRegistrationStore store,
            PasswordEncoder passwords,
            Clock clock,
            Supplier<UUID> identifiers) {
        this.store = Objects.requireNonNull(store, "store");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    RegistrationOutcome register(String loginName, String password, String confirmation) {
        if (!validLoginName(loginName)) {
            return RegistrationOutcome.INVALID_LOGIN_NAME;
        }
        if (!validPassword(password)) {
            return RegistrationOutcome.INVALID_PASSWORD;
        }
        if (!password.equals(confirmation)) {
            return RegistrationOutcome.PASSWORD_MISMATCH;
        }
        String passwordHash;
        try {
            passwordHash = passwords.encode(password);
        } catch (RuntimeException ignored) {
            return RegistrationOutcome.UNAVAILABLE;
        }

        UUID accountId = identifiers.get();
        UUID auditId = identifiers.get();
        Instant occurredAt = clock.instant();
        return store.register(new ReporterAccountRegistration(
                accountId, loginName, Role.REPORTER, passwordHash, auditId, occurredAt));
    }

    private static boolean validLoginName(String loginName) {
        return loginName != null && !loginName.isBlank() && loginName.length() <= MAX_LOGIN_NAME_LENGTH;
    }

    private static boolean validPassword(String password) {
        return password != null && !password.isEmpty() && password.length() <= MAX_PASSWORD_LENGTH;
    }

    enum RegistrationOutcome {
        CREATED,
        INVALID_LOGIN_NAME,
        INVALID_PASSWORD,
        PASSWORD_MISMATCH,
        DUPLICATE,
        UNAVAILABLE
    }
}
