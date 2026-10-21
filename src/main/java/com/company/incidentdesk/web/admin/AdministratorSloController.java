package com.company.incidentdesk.web.admin;

import java.security.Principal;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only HTTP boundary for current and historical SLO targets. */
@RestController
@RequestMapping("/api/v1/admin/slo")
@ConditionalOnBean(DataSource.class)
final class AdministratorSloController {
    private final AdministratorSloService service;

    AdministratorSloController(AdministratorSloService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<?> configuration(Principal principal) {
        if (principal == null) return unavailable();
        AdministratorSloService.SloConfiguration result = service.configuration(principal.getName());
        return switch (result.outcome()) {
        case AVAILABLE -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        case RESOURCE_UNAVAILABLE -> unavailable();
        case PERSISTENCE_FAILURE -> failure();
        };
    }

    @PostMapping("/{category}")
    ResponseEntity<?> change(
            Principal principal,
            @PathVariable String category,
            @RequestBody AdministratorSloService.ChangeRequest request) {
        if (principal == null) return unavailable();
        AdministratorSloService.ChangeResult result = service.change(principal.getName(), category, request);
        if (result.isValidationFailure()) {
            return response(HttpStatus.BAD_REQUEST, Map.of(
                    "code", "VALIDATION", "message", "Review the highlighted fields", "fieldErrors", result.fieldErrors()));
        }
        return switch (result.outcome()) {
        case CHANGED -> response(HttpStatus.CREATED, Map.of("changed", true));
        case UNCHANGED -> response(HttpStatus.OK, Map.of("changed", false));
        case CONFLICT -> response(HttpStatus.CONFLICT, Map.of(
                "code", "CONFLICT", "message", "The SLO target changed. Refresh and try again"));
        case RESOURCE_UNAVAILABLE -> unavailable();
        case PERSISTENCE_FAILURE -> failure();
        };
    }

    private static ResponseEntity<Map<String, String>> unavailable() {
        return response(HttpStatus.NOT_FOUND, Map.of("code", "UNAVAILABLE", "message", "Resource unavailable"));
    }

    private static ResponseEntity<Map<String, String>> failure() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, Map.of(
                "code", "PERSISTENCE_FAILURE", "message", "SLO configuration is temporarily unavailable"));
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
