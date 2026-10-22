package com.company.incidentdesk.web.admin;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

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

/** HTTP boundary for Administrator incident detail and collaboration operations. */
@RestController
@RequestMapping("/api/v1/admin/incidents")
@ConditionalOnBean(DataSource.class)
final class AdministratorIncidentController {
    private final AdministratorIncidentService incidents;

    AdministratorIncidentController(AdministratorIncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping("/{incidentId}")
    ResponseEntity<?> detail(Principal principal, @PathVariable UUID incidentId) {
        return ok(incidents.detail(principal.getName(), incidentId));
    }

    @PostMapping("/{incidentId}/comments")
    ResponseEntity<?> comment(Principal principal, @PathVariable UUID incidentId,
            @RequestBody(required = false) CommentRequest request) {
        var result = incidents.addComment(principal.getName(), incidentId,
                request == null ? null : request.requestId(), request == null ? null : request.text());
        if (!result.added()) return conflict("COMMENT_CONFLICT", "The comment could not be added.");
        return ok(Map.of("added", true, "commentId", result.commentId()));
    }

    @PostMapping("/{incidentId}/assignment")
    ResponseEntity<?> assign(Principal principal, @PathVariable UUID incidentId,
            @RequestBody(required = false) AssignmentRequest request) {
        var result = incidents.assign(principal.getName(), incidentId,
                request == null ? -1 : request.expectedVersion(), request == null ? null : request.responderId());
        if (!result.changed()) return conflict("ASSIGNMENT_CONFLICT", "The incident or responder changed. Refresh and try again.");
        return ok(Map.of("changed", true, "version", result.version()));
    }

    @PostMapping("/{incidentId}/unassign")
    ResponseEntity<?> unassign(Principal principal, @PathVariable UUID incidentId,
            @RequestBody(required = false) VersionRequest request) {
        var result = incidents.unassign(principal.getName(), incidentId,
                request == null ? -1 : request.expectedVersion());
        if (!result.changed()) return conflict("UNASSIGNMENT_CONFLICT", "The incident changed. Refresh and try again.");
        return ok(Map.of("changed", true, "version", result.version()));
    }

    @PostMapping("/{incidentId}/resolve")
    ResponseEntity<?> resolve(Principal principal, @PathVariable UUID incidentId,
            @RequestBody(required = false) ResolutionRequest request) {
        var result = incidents.resolve(principal.getName(), incidentId,
                request == null ? -1 : request.expectedVersion(), request == null ? null : request.requestId(),
                request == null ? null : request.remarks());
        if (!result.resolved()) return conflict("RESOLUTION_CONFLICT", "The incident changed. Refresh and try again.");
        return ok(Map.of("resolved", true, "version", result.version(), "cycleNumber", result.cycleNumber()));
    }

    @ExceptionHandler(AdministratorIncidentService.IncidentUnavailableException.class)
    ResponseEntity<?> denied() {
        return error(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
    }

    @ExceptionHandler(AdministratorIncidentService.IncidentDataAccessException.class)
    ResponseEntity<?> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "INCIDENT_UNAVAILABLE",
                "Incident data is temporarily unavailable.");
    }

    @ExceptionHandler(AdministratorIncidentService.InvalidCommentException.class)
    ResponseEntity<?> invalidComment() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_COMMENT", "Comment text is invalid.");
    }

    @ExceptionHandler(AdministratorIncidentService.InvalidMutationException.class)
    ResponseEntity<?> invalidMutation() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MUTATION", "The incident update is invalid.");
    }

    @ExceptionHandler(AdministratorIncidentService.InvalidResolutionException.class)
    ResponseEntity<?> invalidResolution() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_RESOLUTION", "Resolution remarks are invalid.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The Administrator request is invalid.");
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<?> conflict(String code, String message) {
        return error(HttpStatus.CONFLICT, code, message);
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }

    record CommentRequest(UUID requestId, String text) { }
    record AssignmentRequest(long expectedVersion, UUID responderId) { }
    record VersionRequest(long expectedVersion) { }
    record ResolutionRequest(long expectedVersion, UUID requestId, String remarks) { }
}
