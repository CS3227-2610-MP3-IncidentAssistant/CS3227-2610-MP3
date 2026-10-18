package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

import com.company.incidentdesk.web.security.TemporaryAccountRegistrationController.RegistrationRequest;
import com.company.incidentdesk.web.security.TemporaryAccountRegistrationController.RegistrationResponse;
import com.company.incidentdesk.web.security.TemporaryAccountRegistrationService.RegistrationOutcome;

@SuppressWarnings("deprecation")
class TemporaryAccountRegistrationControllerTest {
    @Test
    void mapsRegistrationOutcomesToStableSafeResponses() {
        OutcomeStore store = new OutcomeStore();
        TemporaryAccountRegistrationService service = new TemporaryAccountRegistrationService(
                store,
                NoOpPasswordEncoder.getInstance(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                UUID::randomUUID);
        TemporaryAccountRegistrationController controller = new TemporaryAccountRegistrationController(service);

        assertResponse(controller, store, RegistrationOutcome.CREATED, HttpStatus.CREATED,
                "ACCOUNT_CREATED", "Account created");
        assertResponse(controller, store, RegistrationOutcome.DUPLICATE, HttpStatus.CONFLICT,
                "DUPLICATE_LOGIN_NAME", "That case-sensitive login name is already registered.");
        assertResponse(controller, store, RegistrationOutcome.UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                "REGISTRATION_UNAVAILABLE", "The account could not be created. Try again.");

        var invalid = controller.register(new RegistrationRequest("", "secret", "secret", "REPORTER"));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals("INVALID_LOGIN_NAME", invalid.getBody().code());
        assertEquals("Enter a non-blank login name.", invalid.getBody().message());
    }

    private static void assertResponse(
            TemporaryAccountRegistrationController controller,
            OutcomeStore store,
            RegistrationOutcome outcome,
            HttpStatus expectedStatus,
            String expectedCode,
            String expectedMessage) {
        store.outcome = outcome;
        var response = controller.register(new RegistrationRequest("name", "secret", "secret", "REPORTER"));
        RegistrationResponse body = response.getBody();
        assertEquals(expectedStatus, response.getStatusCode());
        assertEquals(expectedCode, body.code());
        assertEquals(expectedMessage, body.message());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    private static final class OutcomeStore implements TemporaryAccountRegistrationStore {
        private RegistrationOutcome outcome;

        @Override
        public RegistrationOutcome register(TemporaryAccountRegistration registration) {
            return outcome;
        }
    }
}
