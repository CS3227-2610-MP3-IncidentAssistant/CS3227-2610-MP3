package com.company.incidentdesk.web.reporter;

import java.io.IOException;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.infrastructure.soclaas.AiRequest;
import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;
import com.company.incidentdesk.infrastructure.soclaas.SoclaasException;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Advisory only: Spring Security guards role/CSRF and the gateway rechecks the current database actor. */
@RestController
final class ReporterAiDescriptionController {
    private static final int MAXIMUM_BODY = 16384;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ObjectProvider<SecuredAiGateway> gateways;

    ReporterAiDescriptionController(ObjectProvider<SecuredAiGateway> gateways) { this.gateways = gateways; }

    @PostMapping(path = "/api/v1/reporter/ai/description", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> improve(HttpServletRequest request) {
        AiRequest.ReporterDraft draft;
        try {
            // Bound the wire representation before Jackson can allocate or retain arbitrary submitted fields.
            byte[] body = request.getInputStream().readNBytes(MAXIMUM_BODY + 1);
            if (body.length > MAXIMUM_BODY) { return invalid(); }
            var data = JSON.readTree(body);
            if (data == null || !data.isObject() || data.size() != 3 || !data.path("title").isString()
                    || !data.path("category").isString() || !data.path("description").isString()) { return invalid(); }
            draft = new AiRequest.ReporterDraft(data.path("title").asString(),
                    IncidentCategory.valueOf(data.path("category").asString()), data.path("description").asString());
        } catch (IOException | RuntimeException unsafe) { return invalid(); }

        try {
            var gateway = gateways.getIfAvailable();
            if (gateway == null) { return unavailable(); }
            var suggestion = gateway.assist(draft);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("description", suggestion.text()));
        } catch (SoclaasException safe) {
            return switch (safe.category()) {
            case AI_RATE_LIMITED, AI_BUDGET_EXHAUSTED -> error(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED",
                    "AI assistance is temporarily limited. You can still submit your report.");
            case AI_TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
                    "AI assistance took too long. Your description is unchanged.");
            case AI_REJECTED -> error(HttpStatus.FORBIDDEN, "AI_REJECTED", "AI assistance is unavailable for this request.");
            case AI_INVALID_RESPONSE, AI_OUTPUT_TOO_LARGE -> error(HttpStatus.BAD_GATEWAY, safe.category().name(),
                    "The AI suggestion could not be used. Your description is unchanged.");
            case AI_UNAVAILABLE -> unavailable();
            };
        } catch (RuntimeException unsafe) { return unavailable(); }
    }

    private static ResponseEntity<?> invalid() {
        return error(HttpStatus.BAD_REQUEST, "AI_REJECTED", "Provide a valid title, category, and description only.");
    }
    private static ResponseEntity<?> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                "AI assistance is temporarily unavailable. You can still submit your report.");
    }
    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code, "message", message));
    }
}
