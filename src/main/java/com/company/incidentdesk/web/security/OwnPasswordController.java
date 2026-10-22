package com.company.incidentdesk.web.security;

import java.security.Principal;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the authenticated account replace its own password, which is the only workflow
 * available while a temporary credential is in force.
 */
@RestController
@RequestMapping("/api/v1/auth/password")
@ConditionalOnBean(DataSource.class)
final class OwnPasswordController {
    private final OwnPasswordService passwords;

    OwnPasswordController(OwnPasswordService passwords) {
        this.passwords = passwords;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> changePassword(Principal principal, @RequestBody(required = false) ChangeBody body) {
        if (principal == null) return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        if (body == null || body.currentPassword() == null || body.currentPassword().isEmpty()) {
            return validation("currentPassword");
        }
        if (!OwnPasswordService.isAcceptablePassword(body.newPassword())) return validation("newPassword");

        return switch (passwords.changeOwnPassword(
                principal.getName(), body.currentPassword(), body.newPassword())) {
        case CHANGED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        case REJECTED -> error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "Sign-in failed");
        case RESOURCE_UNAVAILABLE -> error(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
        case PERSISTENCE_FAILURE -> error(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_FAILURE",
                "The password could not be changed");
        };
    }

    private static ResponseEntity<Map<String, Object>> validation(String field) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of(
                "code", "VALIDATION_FAILED",
                "fieldErrors", Map.of(field, "Invalid value.")));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }

    record ChangeBody(String currentPassword, String newPassword) { }
}
