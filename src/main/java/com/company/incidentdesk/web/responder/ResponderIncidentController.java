package com.company.incidentdesk.web.responder;

import java.security.Principal;
import java.util.Map;

import javax.sql.DataSource;

import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ResponderAccessDeniedException;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ResponderDataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for the authenticated Responder queue read. */
@RestController
@RequestMapping("/api/v1/responder/incidents")
@ConditionalOnBean(DataSource.class)
final class ResponderIncidentController {
    private final ResponderIncidentService incidents;

    ResponderIncidentController(ResponderIncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    ResponseEntity<IncidentPage> queue(
            Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(incidents.queue(principal.getName(), page, size));
    }

    @ExceptionHandler(ResponderAccessDeniedException.class)
    ResponseEntity<?> denied() {
        return safeError(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
    }

    @ExceptionHandler(ResponderDataAccessException.class)
    ResponseEntity<?> unavailable() {
        return safeError(HttpStatus.SERVICE_UNAVAILABLE, "INCIDENTS_UNAVAILABLE",
                "Incidents are temporarily unavailable. Try again.");
    }

    private static ResponseEntity<Map<String, String>> safeError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
