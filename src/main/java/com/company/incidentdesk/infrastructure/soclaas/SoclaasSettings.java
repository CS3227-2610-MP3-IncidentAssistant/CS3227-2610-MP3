package com.company.incidentdesk.infrastructure.soclaas;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.springframework.core.env.Environment;

/** Validated backend-only settings; supplied values never appear in diagnostics. */
public final class SoclaasSettings {
    static final URI ORIGIN = URI.create("https://soclaas-api.comp.nus.edu.sg/v1");
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final Map<String, Integer> limits;

    private SoclaasSettings(boolean enabled, String apiKey, String model, Environment environment) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        // Validated values so later environment mutation cannot alter an active client.
        this.limits = Map.of("AI_CONNECT_TIMEOUT_MS", readLimit(environment, "AI_CONNECT_TIMEOUT_MS", 3000),
                "AI_REQUEST_TIMEOUT_MS", readLimit(environment, "AI_REQUEST_TIMEOUT_MS", 30000),
                "AI_MAX_RESPONSE_BYTES", readLimit(environment, "AI_MAX_RESPONSE_BYTES", 65536),
                "AI_MAX_INPUT_BYTES", readLimit(environment, "AI_MAX_INPUT_BYTES", 16384),
                "AI_MAX_INPUT_TOKENS", readLimit(environment, "AI_MAX_INPUT_TOKENS", 8000),
                "AI_MAX_OUTPUT_BYTES", readLimit(environment, "AI_MAX_OUTPUT_BYTES", 16384),
                "AI_MAX_OUTPUT_TOKENS", readLimit(environment, "AI_MAX_OUTPUT_TOKENS", 1200));
    }

    /** Reads deployment settings without reading environment files or accepting arbitrary hosts. */
    public static SoclaasSettings from(Environment environment) {
        String flag = environment.getProperty("AI_ENABLED", "false");
        if (!flag.equals("true") && !flag.equals("false")) {
            throw invalid("AI_ENABLED");
        }
        if (flag.equals("false")) {
            return new SoclaasSettings(false, "", "", environment);
        }
        String origin = environment.getProperty("SOCLAAS_BASE_URL", ORIGIN.toString());
        if (!origin.equals(ORIGIN.toString())) {
            throw invalid("SOCLAAS_BASE_URL");
        }
        return new SoclaasSettings(true, required(environment, "SOCLAAS_API_KEY"),
                required(environment, "SOCLAAS_MODEL"), environment);
    }

    public boolean enabled() {
        return enabled;
    }

    public String model() {
        return model;
    }

    String apiKey() {
        return apiKey;
    }

    Duration connectTimeout() {
        return Duration.ofMillis(limits.get("AI_CONNECT_TIMEOUT_MS"));
    }

    Duration requestTimeout() {
        return Duration.ofMillis(limits.get("AI_REQUEST_TIMEOUT_MS"));
    }

    int maxResponseBytes() {
        return limits.get("AI_MAX_RESPONSE_BYTES");
    }

    int maxInputBytes() {
        return limits.get("AI_MAX_INPUT_BYTES");
    }

    int maxInputTokens() {
        return limits.get("AI_MAX_INPUT_TOKENS");
    }

    int maxOutputBytes() {
        return limits.get("AI_MAX_OUTPUT_BYTES");
    }

    public int maxOutputTokens() {
        return limits.get("AI_MAX_OUTPUT_TOKENS");
    }

    private int readLimit(Environment environment, String name, int maximum) {
        if (!enabled) {
            return maximum;
        }
        try {
            int value = Integer.parseInt(environment.getProperty(name, Integer.toString(maximum)));
            if (value > 0 && value <= maximum) {
                return value;
            }
        } catch (NumberFormatException exception) {
            // Never attach a parser exception, which contains the supplied value.
        }
        throw invalid(name);
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || value.length() > 4096
                || value.chars().anyMatch(character -> character <= 32 || character >= 127)) {
            throw invalid(name);
        }
        return value;
    }

    private static IllegalArgumentException invalid(String name) {
        return new IllegalArgumentException("Invalid or missing " + name);
    }
}
