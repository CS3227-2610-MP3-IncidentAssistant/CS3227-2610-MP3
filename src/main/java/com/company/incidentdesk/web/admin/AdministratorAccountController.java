package com.company.incidentdesk.web.admin;

import java.security.Principal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.domain.incident.IncidentCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only account directory, credential-reset, and tombstone HTTP operations. */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@ConditionalOnBean(DataSource.class)
final class AdministratorAccountController {
    private final AdministratorAccountService accounts;

    AdministratorAccountController(AdministratorAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    ResponseEntity<?> accounts(
            Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        if (principal == null) return unavailable();
        AdministratorAccountService.AccountPage result = accounts.accounts(principal.getName(), page, size);
        if (!result.authorized()) return unavailable();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/{accountId}/tombstone")
    ResponseEntity<?> tombstone(Principal principal, @PathVariable UUID accountId) {
        if (principal == null) return unavailable();
        return switch (accounts.tombstone(principal.getName(), accountId)) {
        case TOMBSTONED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        case RESOURCE_UNAVAILABLE -> unavailable();
        case INVALID_STATE -> error(HttpStatus.CONFLICT, "INVALID_STATE",
                "The current account cannot be deleted");
        case CONFLICT -> error(HttpStatus.CONFLICT, "CONFLICT",
                "The account changed. Refresh before trying again");
        case PERSISTENCE_FAILURE -> error(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_FAILURE",
                "The account could not be deleted");
        };
    }

    @PostMapping("/{accountId}/temporary-password")
    ResponseEntity<?> resetTemporaryPassword(Principal principal, @PathVariable UUID accountId) {
        if (principal == null) return unavailable();
        TemporaryPasswordResetOutcome outcome = accounts.resetTemporaryPassword(principal.getName(), accountId);
        return switch (outcome.status()) {
        case RESET -> outcome.takeTemporaryPassword()
                .<ResponseEntity<?>>map(password -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(Map.of(
                                "temporaryPassword", password,
                                "expiresInHours", 24)))
                .orElseGet(() -> error(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_FAILURE",
                        "The temporary password could not be issued"));
        case RESOURCE_UNAVAILABLE -> unavailable();
        case INVALID_STATE -> error(HttpStatus.CONFLICT, "INVALID_STATE",
                "The current account cannot be reset");
        case CONFLICT -> error(HttpStatus.CONFLICT, "CONFLICT",
                "The account changed. Refresh before trying again");
        case PERSISTENCE_FAILURE -> error(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_FAILURE",
                "The temporary password could not be issued");
        };
    }

    @PutMapping(path = "/{accountId}/responder-categories", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> replaceResponderCategories(
            Principal principal,
            @PathVariable UUID accountId,
            @RequestBody ResponderCategoriesBody body) {
        if (principal == null) return unavailable();
        if (body == null || body.expectedVersion() == null || body.expectedVersion() < 0) {
            return validation("expectedVersion");
        }
        if (body.categories() == null) return validation("categories");
        List<String> invalidFields = new ArrayList<>();
        Set<IncidentCategory> categories = parseCategories(body.categories(), invalidFields);
        if (!invalidFields.isEmpty()) return validation("categories");

        return switch (accounts.replaceResponderCategories(
                principal.getName(), accountId, body.expectedVersion(), categories)) {
        case UPDATED, UNCHANGED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        case CONFLICT -> error(HttpStatus.CONFLICT, "CONFLICT",
                "The account changed. Refresh before trying again");
        case RESOURCE_UNAVAILABLE -> unavailable();
        case PERSISTENCE_FAILURE -> error(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_UPDATE_UNAVAILABLE",
                "Responder access could not be updated");
        };
    }

    private static Set<IncidentCategory> parseCategories(List<String> values, List<String> invalidFields) {
        EnumSet<IncidentCategory> categories = EnumSet.noneOf(IncidentCategory.class);
        for (String value : values) {
            try {
                if (!categories.add(IncidentCategory.valueOf(value))) invalidFields.add("categories");
            } catch (IllegalArgumentException | NullPointerException exception) {
                invalidFields.add("categories");
            }
        }
        return Set.copyOf(categories);
    }

    private static ResponseEntity<Map<String, Object>> validation(String field) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of(
                "code", "VALIDATION_FAILED",
                "fieldErrors", Map.of(field, "Invalid value.")));
    }

    private static ResponseEntity<Map<String, String>> unavailable() {
        return error(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }

    record ResponderCategoriesBody(Long expectedVersion, List<String> categories) { }
}
