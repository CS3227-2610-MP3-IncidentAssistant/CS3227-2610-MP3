package com.company.incidentdesk.web.responder;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.incidentdesk.infrastructure.soclaas.AiRequest;
import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;
import com.company.incidentdesk.infrastructure.soclaas.SoclaasException;

import jakarta.servlet.http.HttpServletRequest;

/** Explicit, read-only responder advice request. Actor and authorized data come from the gateway. */
@RestController
final class ResponderAiIncidentController {
    private final ObjectProvider<SecuredAiGateway> gateways;

    ResponderAiIncidentController(ObjectProvider<SecuredAiGateway> gateways) { this.gateways = gateways; }

    @PostMapping(path = "/api/v1/responder/incidents/{incidentId}/ai/advice", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> advise(@PathVariable UUID incidentId, HttpServletRequest request) {
        try {
            if (request.getQueryString() != null || request.getInputStream().read() != -1) {
                return error(HttpStatus.BAD_REQUEST, "AI_REJECTED", "Provide only an incident identifier.");
            }
            var gateway = gateways.getIfAvailable();
            if (gateway == null) return unavailable();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(Map.of("advice", gateway.assist(new AiRequest.ResponderIncident(incidentId)).text()));
        } catch (SoclaasException safe) {
            return switch (safe.category()) {
            case AI_REJECTED -> error(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
            case AI_RATE_LIMITED, AI_BUDGET_EXHAUSTED -> error(HttpStatus.TOO_MANY_REQUESTS,
                    "AI_RATE_LIMITED", "AI assistance is temporarily limited. You can continue handling the incident.");
            case AI_TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
                    "AI assistance took too long. You can continue handling the incident.");
            case AI_INVALID_RESPONSE, AI_OUTPUT_TOO_LARGE -> error(HttpStatus.BAD_GATEWAY, safe.category().name(),
                    "The AI suggestion could not be used. You can continue handling the incident.");
            case AI_UNAVAILABLE -> unavailable();
            };
        } catch (IOException | RuntimeException unsafe) { return unavailable(); }
    }

    private static ResponseEntity<?> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                "AI assistance is temporarily unavailable. You can continue handling the incident.");
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
