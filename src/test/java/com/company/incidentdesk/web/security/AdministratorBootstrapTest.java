package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class AdministratorBootstrapTest {
    @Test
    void rejectsShortPasswordWithActionableErrorWithoutDisclosingIt() {
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
}
