package com.company.incidentdesk.web.admin;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only account directory and tombstone HTTP operations. */
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

    private static ResponseEntity<Map<String, String>> unavailable() {
        return error(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
