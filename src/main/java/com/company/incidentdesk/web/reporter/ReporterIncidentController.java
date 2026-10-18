package com.company.incidentdesk.web.reporter;

import java.security.Principal;
import java.util.Map;

import javax.sql.DataSource;

import com.company.incidentdesk.web.reporter.JdbcReporterIncidentGateway.ReporterDataAccessException;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.IncidentPage;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.ListCriteria;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.ReporterAccessDeniedException;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.SubmissionCommand;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Trusted HTTP boundary for Reporter-owned incident list and direct submission. */
@RestController
@RequestMapping("/api/v1/reporter/incidents")
@ConditionalOnBean(DataSource.class)
final class ReporterIncidentController {
    private final ReporterIncidentService incidents;

    ReporterIncidentController(ReporterIncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    ResponseEntity<IncidentPage> list(
            Principal principal,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(incidents.findOwned(principal.getName(), new ListCriteria(search, page, size)));
    }

    @PostMapping
    ResponseEntity<?> submit(Principal principal, @RequestBody SubmissionCommand command) {
        SubmissionResult result = incidents.submit(principal.getName(), command);
        return switch (result.status()) {
        case CREATED -> ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(result.incident());
        case DUPLICATE -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.incident());
        case VALIDATION_FAILED -> ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("code", "VALIDATION_FAILED", "fieldErrors", result.fieldErrors()));
        case DENIED -> safeError(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
        case UNAVAILABLE -> safeError(HttpStatus.SERVICE_UNAVAILABLE, "SUBMISSION_UNAVAILABLE",
                "The incident could not be submitted. Try again.");
        };
    }

    @ExceptionHandler(ReporterDataAccessException.class)
    ResponseEntity<?> unavailable() {
        return safeError(HttpStatus.SERVICE_UNAVAILABLE, "INCIDENTS_UNAVAILABLE",
                "Incidents are temporarily unavailable. Try again.");
    }

    @ExceptionHandler(ReporterAccessDeniedException.class)
    ResponseEntity<?> denied() {
        return safeError(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
    }

    private static ResponseEntity<Map<String, String>> safeError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
