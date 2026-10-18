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
 * Temporary web-registration migration service.
 *
 * <p>This intentionally accepts a caller-selected role and must be replaced
 * before release as required by the registration migration specification.</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
final class TemporaryAccountRegistrationService {
    static final int MAX_LOGIN_NAME_LENGTH = 128;
    static final int MAX_PASSWORD_LENGTH = 128;

    private final TemporaryAccountRegistrationStore store;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    @Autowired
    TemporaryAccountRegistrationService(
            TemporaryAccountRegistrationStore store,
            PasswordEncoder passwords) {
        this(store, passwords, Clock.systemUTC(), UUID::randomUUID);
    }

    TemporaryAccountRegistrationService(
            TemporaryAccountRegistrationStore store,
            PasswordEncoder passwords,
            Clock clock,
            Supplier<UUID> identifiers) {
        this.store = Objects.requireNonNull(store, "store");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    RegistrationOutcome register(String loginName, String password, String confirmation, String requestedRole) {
        if (!validLoginName(loginName)) {
            return RegistrationOutcome.INVALID_LOGIN_NAME;
        }
        if (!validPassword(password)) {
            return RegistrationOutcome.INVALID_PASSWORD;
        }
        if (!password.equals(confirmation)) {
            return RegistrationOutcome.PASSWORD_MISMATCH;
        }
        Role role = parseRole(requestedRole);
        if (role == null) {
            return RegistrationOutcome.INVALID_ROLE;
        }

        String passwordHash;
        try {
            passwordHash = passwords.encode(password);
        } catch (RuntimeException exception) {
            return RegistrationOutcome.UNAVAILABLE;
        }

        UUID accountId = identifiers.get();
        UUID auditId = identifiers.get();
        Instant occurredAt = clock.instant();
        return store.register(new TemporaryAccountRegistration(
                accountId, loginName, role, passwordHash, auditId, occurredAt));
    }

    private static boolean validLoginName(String loginName) {
        return loginName != null && !loginName.isBlank() && loginName.length() <= MAX_LOGIN_NAME_LENGTH;
    }

    private static boolean validPassword(String password) {
        return password != null && !password.isEmpty() && password.length() <= MAX_PASSWORD_LENGTH;
    }

    private static Role parseRole(String requestedRole) {
        if (requestedRole == null) {
            return null;
        }
        try {
            return Role.valueOf(requestedRole);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    enum RegistrationOutcome {
        CREATED,
        INVALID_LOGIN_NAME,
        INVALID_PASSWORD,
        PASSWORD_MISMATCH,
        INVALID_ROLE,
        DUPLICATE,
        UNAVAILABLE
    }
}
