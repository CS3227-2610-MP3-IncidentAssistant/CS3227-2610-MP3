package com.company.incidentdesk.web.security;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.incidentdesk.web.security.ReporterAccountRegistrationService.RegistrationOutcome;

/** Public registration endpoint that creates Reporter accounts only. */
@RestController
@RequestMapping("/api/v1/auth/reporter")
@ConditionalOnBean(DataSource.class)
final class ReporterAccountRegistrationController {
    private final ReporterAccountRegistrationService registrations;

    ReporterAccountRegistrationController(ReporterAccountRegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping("/register")
    ResponseEntity<RegistrationResponse> register(@RequestBody RegistrationRequest request) {
        RegistrationOutcome outcome = registrations.register(
                request.loginName(), request.password(), request.passwordConfirmation());
        return switch (outcome) {
        case CREATED -> ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new RegistrationResponse("ACCOUNT_CREATED", "Account created"));
        case INVALID_LOGIN_NAME -> response(HttpStatus.BAD_REQUEST, "INVALID_LOGIN_NAME",
                "Enter a non-blank login name.");
        case INVALID_PASSWORD -> response(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Enter a password.");
        case PASSWORD_MISMATCH -> response(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "Passwords do not match.");
        case DUPLICATE -> response(HttpStatus.CONFLICT, "DUPLICATE_LOGIN_NAME",
                "That case-sensitive login name is already registered.");
        case UNAVAILABLE -> response(HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_UNAVAILABLE",
                "The account could not be created. Try again.");
        };
    }

    private static ResponseEntity<RegistrationResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new RegistrationResponse(code, message));
    }

    record RegistrationRequest(String loginName, String password, String passwordConfirmation) {
    }

    record RegistrationResponse(String code, String message) {
    }
}
