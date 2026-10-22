package com.company.incidentdesk.application.ai.insights;

import static com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict parser for the one closed JSON object accepted from the untrusted provider. */
public final class AdministratorInsightPlanParser {
    private static final int MAXIMUM_JSON_BYTES = 4096;
    private static final Set<String> FIELDS = Set.of("outcome", "queryType", "metric", "period",
            "comparisonPeriod", "direction", "limit", "grouping", "clarificationCode");
    private static final Set<String> EXPLICIT_PERIOD_FIELDS = Set.of("fromDate", "throughDate");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public AdministratorInsightPlan parse(String providerOutput) {
        if (providerOutput == null || providerOutput.isBlank()
                || providerOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES) {
            throw rejected();
        }
        try {
            JsonNode root = JSON.readTree(providerOutput);
            if (root == null || !root.isObject() || !FIELDS.containsAll(root.propertyNames())) {
                throw rejected();
            }
            Outcome outcome = enumField(root, "outcome", Outcome.class).orElseThrow(AdministratorInsightPlanParser::rejected);
            return new AdministratorInsightPlan(
                    outcome,
                    enumField(root, "queryType", QueryType.class),
                    enumField(root, "metric", Metric.class),
                    periodField(root, "period"),
                    periodField(root, "comparisonPeriod"),
                    enumField(root, "direction", Direction.class),
                    integerField(root, "limit"),
                    enumField(root, "grouping", Grouping.class),
                    enumField(root, "clarificationCode", ClarificationCode.class));
        } catch (PlanRejectedException safe) {
            throw safe;
        } catch (RuntimeException unsafe) {
            throw rejected();
        }
    }

    private static Optional<Period> periodField(JsonNode root, String name) {
        JsonNode value = root.path(name);
        if (value.isMissingNode()) {
            return Optional.empty();
        }
        if (value.isTextual()) {
            return Optional.of(new Named(NamedPeriod.valueOf(value.asString())));
        }
        if (!value.isObject() || !Set.copyOf(value.propertyNames()).equals(EXPLICIT_PERIOD_FIELDS)
                || !value.path("fromDate").isTextual() || !value.path("throughDate").isTextual()) {
            throw rejected();
        }
        try {
            return Optional.of(new Explicit(LocalDate.parse(value.path("fromDate").asString()),
                    LocalDate.parse(value.path("throughDate").asString())));
        } catch (DateTimeParseException invalid) {
            throw rejected();
        }
    }

    private static Optional<Integer> integerField(JsonNode root, String name) {
        JsonNode value = root.path(name);
        if (value.isMissingNode()) {
            return Optional.empty();
        }
        if (!value.isInt()) {
            throw rejected();
        }
        return Optional.of(value.intValue());
    }

    private static <E extends Enum<E>> Optional<E> enumField(JsonNode root, String name, Class<E> type) {
        JsonNode value = root.path(name);
        if (value.isMissingNode()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw rejected();
        }
        return Optional.of(Enum.valueOf(type, value.asString()));
    }

    private static PlanRejectedException rejected() {
        return new PlanRejectedException();
    }

    public static final class PlanRejectedException extends RuntimeException {
        private PlanRejectedException() {
            super("AI_INVALID_RESPONSE", null, false, false);
        }
    }
}
