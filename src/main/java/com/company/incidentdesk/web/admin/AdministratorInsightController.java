package com.company.incidentdesk.web.admin;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlanParser.PlanRejectedException;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightQuestionValidator.QuestionRejectedException;
import com.company.incidentdesk.infrastructure.soclaas.SoclaasException;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict administrator-only boundary for one-shot, read-only operational insights. */
@RestController
@RequestMapping("/api/v1/admin/statistics/insights")
@ConditionalOnBean(DataSource.class)
final class AdministratorInsightController {
    private static final int MAXIMUM_BODY = 8192;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AdministratorInsightService service;

    AdministratorInsightController(AdministratorInsightService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> answer(Principal principal, HttpServletRequest request) {
        if (principal == null) return unavailable();
        AdministratorInsightService.Request input;
        try {
            byte[] body = request.getInputStream().readNBytes(MAXIMUM_BODY + 1);
            if (body.length > MAXIMUM_BODY) return invalid();
            input = parse(body);
        } catch (IOException | RuntimeException invalid) {
            return invalid();
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(service.answer(principal.getName(), input));
        } catch (QuestionRejectedException | AdministratorInsightService.InvalidInsightRequestException invalid) {
            return invalid();
        } catch (PlanRejectedException invalidPlan) {
            return error(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE",
                    "The insight plan could not be used. No statistics query was run.");
        } catch (SoclaasException safe) {
            return switch (safe.category()) {
            case AI_RATE_LIMITED, AI_BUDGET_EXHAUSTED -> error(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED",
                    "AI insights are temporarily limited. Operational statistics remain available.");
            case AI_TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
                    "The insight request timed out. No statistics query was run.");
            case AI_REJECTED -> unavailable();
            case AI_INVALID_RESPONSE, AI_OUTPUT_TOO_LARGE -> error(HttpStatus.BAD_GATEWAY,
                    safe.category().name(), "The insight plan could not be used. No statistics query was run.");
            case AI_UNAVAILABLE -> serviceUnavailable();
            };
        } catch (AdministratorInsightService.AiUnavailableException unavailable) {
            return serviceUnavailable();
        } catch (AdministratorInsightService.InsightUnavailableException unavailable) {
            return unavailable();
        } catch (RuntimeException unsafe) {
            return serviceUnavailable();
        }
    }

    private static AdministratorInsightService.Request parse(byte[] body) {
        JsonNode root = JSON.readTree(body);
        if (root == null || !root.isObject() || root.size() < 1 || root.size() > 3
                || !root.path("question").isString()) throw new IllegalArgumentException();
        for (String field : root.propertyNames()) {
            if (!field.equals("question") && !field.equals("categories") && !field.equals("responderIds")) {
                throw new IllegalArgumentException();
            }
        }
        return new AdministratorInsightService.Request(root.path("question").asString(),
                strings(root.path("categories")), identifiers(root.path("responderIds")));
    }

    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException();
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isString()) throw new IllegalArgumentException();
            values.add(value.asString());
        }
        return List.copyOf(values);
    }

    private static List<UUID> identifiers(JsonNode node) {
        List<UUID> values = new ArrayList<>();
        for (String value : strings(node)) values.add(UUID.fromString(value));
        return List.copyOf(values);
    }

    private static ResponseEntity<?> invalid() {
        return error(HttpStatus.BAD_REQUEST, "AI_REJECTED",
                "Ask a question of at most 500 characters without names, identifiers, personal data, or links.");
    }
    private static ResponseEntity<?> unavailable() {
        return error(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
    }
    private static ResponseEntity<?> serviceUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                "AI insights are temporarily unavailable. Operational statistics remain available.");
    }
    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
