package com.company.incidentdesk.web.admin;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only HTTP boundary for privacy-safe operational statistics. */
@RestController
@RequestMapping("/api/v1/admin/statistics")
@ConditionalOnBean(DataSource.class)
final class AdministratorStatisticsController {
    private final AdministratorStatisticsService service;

    AdministratorStatisticsController(AdministratorStatisticsService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<?> statistics(
            Principal principal,
            @RequestParam(defaultValue = "") List<String> category,
            @RequestParam(required = false) UUID responderId,
            @RequestParam(required = false) UUID reporterId,
            @RequestParam(required = false) Instant periodFrom,
            @RequestParam(required = false) Instant periodThrough) {
        if (principal == null) {
            return unavailable();
        }
        AdministratorStatisticsStore.ReadResult result = service.read(
                principal.getName(),
                new AdministratorStatisticsService.Query(
                        category, responderId, reporterId, periodFrom, periodThrough));
        return switch (result.outcome()) {
        case AVAILABLE -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        case RESOURCE_UNAVAILABLE -> unavailable();
        case PERSISTENCE_FAILURE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "code", "PERSISTENCE_FAILURE",
                        "message", "Operational statistics are temporarily unavailable"));
        };
    }

    private static ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("code", "UNAVAILABLE", "message", "Resource unavailable"));
    }
}
