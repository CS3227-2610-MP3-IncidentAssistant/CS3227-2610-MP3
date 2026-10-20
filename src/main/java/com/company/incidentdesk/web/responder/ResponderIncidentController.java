package com.company.incidentdesk.web.responder;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.Assignment;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ResponderAccessDeniedException;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ResponderDataAccessException;
import com.company.incidentdesk.web.responder.ResponderIncidentService.InvalidResolutionException;
import com.company.incidentdesk.web.responder.ResponderIncidentService.InvalidQueueFilterException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for authenticated Responder incident queue operations. */
@RestController
@RequestMapping("/api/v1/responder/incidents")
@ConditionalOnBean(DataSource.class)
final class ResponderIncidentController {
    private final ResponderIncidentService incidents;

    ResponderIncidentController(ResponderIncidentService incidents) {
        this.incidents = incidents;
    }

    @PostMapping("/query")
    ResponseEntity<IncidentPage> queue(Principal principal, @RequestBody QueueRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(incidents.queue(principal.getName(), request.search(), request.categories(), request.statuses(),
                        request.assignment(), request.page(), request.size()));
    }

    @PostMapping("/{incidentId}/claim")
    ResponseEntity<?> claim(Principal principal, @PathVariable UUID incidentId) {
        var result = incidents.claim(principal.getName(), incidentId);
        if (!result.claimed()) {
            return safeError(HttpStatus.CONFLICT, "CLAIM_CONFLICT", "This incident is no longer available to claim.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("claimed", true));
    }

    @PostMapping("/{incidentId}/resolve")
    ResponseEntity<?> resolve(Principal principal, @PathVariable UUID incidentId,
            @RequestBody(required = false) ResolveRequest request) {
        var result = incidents.resolve(principal.getName(), incidentId,
                request == null ? null : request.requestId(), request == null ? null : request.remarks());
        if (!result.resolved()) {
            return safeError(HttpStatus.CONFLICT, "RESOLUTION_CONFLICT", "This incident can no longer be resolved.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("resolved", true, "cycleNumber", result.cycleNumber()));
    }

    @GetMapping("/{incidentId}")
    ResponseEntity<?> detail(Principal principal, @PathVariable UUID incidentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(incidents.detail(principal.getName(), incidentId));
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

    @ExceptionHandler(InvalidQueueFilterException.class)
    ResponseEntity<?> invalidFilter() {
        return safeError(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "One or more queue filters are invalid.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformedQuery() {
        return safeError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The Responder request is invalid.");
    }

    @ExceptionHandler(InvalidResolutionException.class)
    ResponseEntity<?> invalidResolution() {
        return safeError(HttpStatus.BAD_REQUEST, "INVALID_RESOLUTION", "Resolution remarks are invalid.");
    }

    private static ResponseEntity<Map<String, String>> safeError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }

    record QueueRequest(String search, List<String> categories, List<String> statuses,
            Assignment assignment, int page, int size) {
    }

    record ResolveRequest(UUID requestId, String remarks) {
    }
}
