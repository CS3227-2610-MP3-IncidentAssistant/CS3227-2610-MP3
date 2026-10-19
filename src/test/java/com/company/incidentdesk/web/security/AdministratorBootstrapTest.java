package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class AdministratorBootstrapTest {
    @Test
    void doesNothingWhenBootstrapIsNotConfigured() {
        AdministratorBootstrap bootstrap = new AdministratorBootstrap(
                new MockEnvironment(), null, null, null, Clock.systemUTC());

        assertDoesNotThrow(() -> bootstrap.run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void rejectsShortPasswordWithActionableErrorWithoutDisclosingIt(CapturedOutput output) {
        String invalidPassword = "too-short";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN", "initial-admin")
                .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD", invalidPassword);
        AdministratorBootstrap bootstrap = new AdministratorBootstrap(
                environment, null, null, null, Clock.systemUTC());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments(new String[0])));

        assertEquals("Invalid INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD: expected 12 to 128 characters. "
                + "No account was created.", failure.getMessage());
        assertFalse(failure.getMessage().contains(invalidPassword));
        assertFalse(output.getAll().contains(invalidPassword));
    }

    @Test
    void identifiesMissingBootstrapVariable() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN", "initial-admin");
        AdministratorBootstrap bootstrap = new AdministratorBootstrap(
                environment, null, null, null, Clock.systemUTC());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments(new String[0])));

        assertEquals("Invalid administrator bootstrap configuration: "
                + "INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD is missing. No account was created.",
                failure.getMessage());
    }

    @Test
    void rejectsPasswordOnlyConfigurationWithoutDisclosingValue(CapturedOutput output) {
        String secret = "password-only-synthetic-secret";
        AdministratorBootstrap bootstrap = new AdministratorBootstrap(
                new MockEnvironment().withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD", secret),
                null, null, null, Clock.systemUTC());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments(new String[0])));

        assertTrue(failure.getMessage().contains("INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN is missing"));
        assertFalse(failure.getMessage().contains(secret));
        assertFalse(output.getAll().contains(secret));
    }

    @Test
    void rejectsBlankAndOversizedLoginsWithoutDisclosingValues(CapturedOutput output) {
        Stream.of("x".repeat(129)).forEach(login -> {
            AdministratorBootstrap bootstrap = new AdministratorBootstrap(
                    new MockEnvironment()
                            .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN", login)
                            .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD", "synthetic-valid-password"),
                    null, null, null, Clock.systemUTC());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> bootstrap.run(new DefaultApplicationArguments(new String[0])));

            assertTrue(failure.getMessage().contains("Invalid INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN"));
            assertFalse(failure.getMessage().contains(login));
            assertFalse(output.getAll().contains(login));
        });

        AdministratorBootstrap blankLoginBootstrap = new AdministratorBootstrap(
                new MockEnvironment()
                        .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN", " ")
                        .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD", "synthetic-valid-password"),
                null, null, null, Clock.systemUTC());
        IllegalStateException blankLoginFailure = assertThrows(IllegalStateException.class,
                () -> blankLoginBootstrap.run(new DefaultApplicationArguments(new String[0])));
        assertTrue(blankLoginFailure.getMessage().contains("INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN is missing"));
        assertFalse(output.getAll().contains("synthetic-valid-password"));
    }

    @Test
    void rejectsOversizedPasswordWithoutDisclosingIt(CapturedOutput output) {
        String secret = "x".repeat(129);
        AdministratorBootstrap bootstrap = new AdministratorBootstrap(
                new MockEnvironment()
                        .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN", "initial-admin")
                        .withProperty("INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD", secret),
                null, null, null, Clock.systemUTC());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments(new String[0])));

        assertTrue(failure.getMessage().contains("Invalid INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD"));
        assertFalse(failure.getMessage().contains(secret));
        assertFalse(output.getAll().contains(secret));
    }
}
