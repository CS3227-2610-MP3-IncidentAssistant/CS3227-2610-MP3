package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.web.security.ReporterAccountRegistrationService.RegistrationOutcome;

class ReporterAccountRegistrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-18T08:30:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AUDIT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Test
    void alwaysCreatesReporterAndHashesThePassword() {
        RecordingStore store = new RecordingStore();
        PasswordEncoder encoder = new BCryptPasswordEncoder(4);
        ReporterAccountRegistrationService service = service(store, encoder);

        assertEquals(RegistrationOutcome.CREATED,
                service.register("MigrationReporter", "temporary-secret", "temporary-secret"));

        ReporterAccountRegistration saved = store.saved;
        assertEquals(ACCOUNT_ID, saved.accountId());
        assertEquals(AUDIT_ID, saved.auditId());
        assertEquals("MigrationReporter", saved.loginName());
        assertEquals(Role.REPORTER, saved.role());
        assertEquals(NOW, saved.occurredAt());
        assertNotEquals("temporary-secret", saved.passwordHash());
        assertTrue(encoder.matches("temporary-secret", saved.passwordHash()));
    }

    @Test
    void rejectsInvalidInputBeforeEncodingOrPersistence() {
        RecordingStore store = new RecordingStore();
        CountingEncoder encoder = new CountingEncoder();
        ReporterAccountRegistrationService service = service(store, encoder);
        String oversizedLogin = "x".repeat(ReporterAccountRegistrationService.MAX_LOGIN_NAME_LENGTH + 1);
        String oversizedPassword = "x".repeat(ReporterAccountRegistrationService.MAX_PASSWORD_LENGTH + 1);

        assertEquals(RegistrationOutcome.INVALID_LOGIN_NAME,
                service.register(" ", "secret", "secret"));
        assertEquals(RegistrationOutcome.INVALID_LOGIN_NAME,
                service.register(oversizedLogin, "secret", "secret"));
        assertEquals(RegistrationOutcome.INVALID_PASSWORD, service.register("name", "", ""));
        assertEquals(RegistrationOutcome.INVALID_PASSWORD,
                service.register("name", oversizedPassword, oversizedPassword));
        assertEquals(RegistrationOutcome.PASSWORD_MISMATCH,
                service.register("name", "one", "two"));
        assertEquals(0, encoder.calls);
        assertEquals(null, store.saved);
    }

    @Test
    void returnsSafeUnavailableOutcomeWhenPasswordEncodingFails() {
        RecordingStore store = new RecordingStore();
        PasswordEncoder unavailable = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new IllegalStateException("encoder unavailable");
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };

        assertEquals(RegistrationOutcome.UNAVAILABLE,
                service(store, unavailable).register("reporter", "secret", "secret"));
        assertEquals(null, store.saved);
    }

    @Test
    void propagatesDuplicateAndPersistenceOutcomesWithoutChangingThem() {
        RecordingStore store = new RecordingStore();
        ReporterAccountRegistrationService service = service(store, new BCryptPasswordEncoder(4));

        store.outcome = RegistrationOutcome.DUPLICATE;
        assertEquals(RegistrationOutcome.DUPLICATE,
                service.register("same", "secret", "secret"));
        store.outcome = RegistrationOutcome.UNAVAILABLE;
        assertEquals(RegistrationOutcome.UNAVAILABLE,
                service.register("other", "secret", "secret"));
    }

    private static ReporterAccountRegistrationService service(
            RecordingStore store,
            PasswordEncoder encoder) {
        Deque<UUID> identifiers = new ArrayDeque<>();
        identifiers.add(ACCOUNT_ID);
        identifiers.add(AUDIT_ID);
        identifiers.add(UUID.fromString("10000000-0000-0000-0000-000000000003"));
        identifiers.add(UUID.fromString("10000000-0000-0000-0000-000000000004"));
        return new ReporterAccountRegistrationService(
                store, encoder, Clock.fixed(NOW, ZoneOffset.UTC), identifiers::removeFirst);
    }

    private static final class RecordingStore implements ReporterAccountRegistrationStore {
        private RegistrationOutcome outcome = RegistrationOutcome.CREATED;
        private ReporterAccountRegistration saved;

        @Override
        public RegistrationOutcome register(ReporterAccountRegistration registration) {
            saved = registration;
            return outcome;
        }
    }

    private static final class CountingEncoder implements PasswordEncoder {
        private int calls;

        @Override
        public String encode(CharSequence rawPassword) {
            calls++;
            return "encoded";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return false;
        }
    }
}
