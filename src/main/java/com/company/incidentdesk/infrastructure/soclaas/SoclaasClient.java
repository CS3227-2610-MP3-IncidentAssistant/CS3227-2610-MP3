package com.company.incidentdesk.infrastructure.soclaas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Narrow non-streaming provider adapter; callers must first authorize and admit requests. */
public final class SoclaasClient implements AutoCloseable {
    private final SoclaasSettings settings;
    private final URI origin;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private volatile long validatedAt;

    /** Uses only the fixed production origin. No endpoint override is exposed to application callers. */
    public SoclaasClient(SoclaasSettings settings) {
        this(settings, SoclaasSettings.ORIGIN);
    }

    // The fake provider uses this package-private seam; production settings still require the exact HTTPS origin.
    SoclaasClient(SoclaasSettings settings, URI origin) {
        this.settings = settings;
        this.origin = origin;
        this.http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Validates model access and returns its untouched metadata for later price/accounting validation. */
    public Model validateModel() {
        validatedAt = 0;
        requireEnabled();
        JsonNode root = parse(exchange("/models", null));
        JsonNode models = root.path("data");
        if (!models.isArray()) {
            throw failure(SoclaasException.Category.AI_INVALID_RESPONSE);
        }
        for (JsonNode model : models) {
            if (model.path("id").isString() && settings.model().equals(model.path("id").asString())) {
                validatedAt = System.nanoTime();
                return new Model(settings.model(), model.deepCopy());
            }
        }
        throw failure(SoclaasException.Category.AI_REJECTED);
    }

    /** Accepts trusted instructions and an already allowlisted JSON object, never a client-selected prompt policy. */
    public Completion complete(String system, String dataJson) {
        requireEnabled();
        if (validatedAt == 0 || System.nanoTime() - validatedAt > Duration.ofMinutes(15).toNanos()) {
            throw failure(SoclaasException.Category.AI_UNAVAILABLE);
        }
        if (system == null || system.isBlank() || dataJson == null
                || utf8(dataJson) > settings.maxInputBytes()
                || (long) utf8(system) + utf8(dataJson) + 256 > settings.maxInputTokens()) {
            throw failure(SoclaasException.Category.AI_REJECTED);
        }
        JsonNode data;
        try {
            data = json.readTree(dataJson);
        } catch (RuntimeException exception) {
            throw failure(SoclaasException.Category.AI_REJECTED);
        }
        if (data == null || !data.isObject()) {
            throw failure(SoclaasException.Category.AI_REJECTED);
        }
        byte[] request = json.writeValueAsBytes(Map.of("model", settings.model(), "stream", false,
                "max_tokens", settings.maxOutputTokens(), "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", dataJson))));
        return decodeCompletion(parse(exchange("/chat/completions", request)));
    }

    private Completion decodeCompletion(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            throw failure(SoclaasException.Category.AI_INVALID_RESPONSE);
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        JsonNode content = message.path("content");
        if (!"stop".equals(choice.path("finish_reason").asString(""))
                || !"assistant".equals(message.path("role").asString(""))
                || !content.isString() || content.asString().isBlank()
                || message.hasNonNull("tool_calls") || message.hasNonNull("function_call")
                || message.hasNonNull("refusal")) {
            throw failure(SoclaasException.Category.AI_INVALID_RESPONSE);
        }
        String text = content.asString();
        Usage usage = usage(root.path("usage"));
        // Without a tokenizer or trustworthy token usage, UTF-8 bytes give a conservative token upper bound.
        if (utf8(text) > settings.maxOutputBytes()
                || (usage == null ? utf8(text) : usage.completionTokens()) > settings.maxOutputTokens()) {
            throw failure(SoclaasException.Category.AI_OUTPUT_TOO_LARGE);
        }
        return new Completion(text, usage);
    }

    private Usage usage(JsonNode node) {
        JsonNode input = node.path("prompt_tokens");
        JsonNode output = node.path("completion_tokens");
        JsonNode total = node.path("total_tokens");
        if (!input.isIntegralNumber() || !output.isIntegralNumber() || !total.isIntegralNumber()
                || !input.canConvertToLong() || !output.canConvertToLong() || !total.canConvertToLong()) {
            return null;
        }
        long in = input.asLong();
        long out = output.asLong();
        long sum = total.asLong();
        return in >= 0 && out >= 0 && sum >= in && sum - in == out ? new Usage(in, out, sum) : null;
    }

    private byte[] exchange(String path, byte[] payload) {
        long deadline = System.nanoTime() + settings.requestTimeout().toNanos();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw failure(SoclaasException.Category.AI_UNAVAILABLE);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw failure(SoclaasException.Category.AI_TIMEOUT);
            }
            var builder = HttpRequest.newBuilder(URI.create(origin + path))
                    .timeout(Duration.ofNanos(remaining)).header("Authorization", "Bearer " + settings.apiKey())
                    .header("Accept", "application/json");
            HttpRequest request = payload == null ? builder.GET().build()
                    : builder.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            var pending = http.sendAsync(request, info ->
                    new BoundedBodySubscriber(info.statusCode() == 200 ? settings.maxResponseBytes() : 0));
            HttpResponse<byte[]> response;
            try {
                response = pending.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw failure(SoclaasException.Category.AI_UNAVAILABLE);
            } catch (TimeoutException exception) {
                pending.cancel(true);
                throw failure(SoclaasException.Category.AI_TIMEOUT);
            } catch (ExecutionException exception) {
                Throwable cause = exception;
                while (cause != null) {
                    if (cause instanceof SoclaasException safe) {
                        throw safe;
                    }
                    if (cause instanceof HttpTimeoutException) {
                        throw failure(SoclaasException.Category.AI_TIMEOUT);
                    }
                    cause = cause.getCause();
                }
                throw failure(SoclaasException.Category.AI_UNAVAILABLE);
            }
            int status = response.statusCode();
            // Only retry idempotent catalog reads, with no accepted body, once within the original deadline.
            if (payload == null && attempt == 0 && response.body().length == 0
                    && (status == 502 || status == 503 || status == 504)) {
                try {
                    long pause = TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(50, 151));
                    long available = deadline - System.nanoTime();
                    if (available <= 0) {
                        throw failure(SoclaasException.Category.AI_TIMEOUT);
                    }
                    TimeUnit.NANOSECONDS.sleep(Math.min(pause, available));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw failure(SoclaasException.Category.AI_UNAVAILABLE);
                }
                continue;
            }
            if (status != 200) {
                throw new SoclaasException(switch (status) {
                case 400, 401, 403 -> SoclaasException.Category.AI_REJECTED;
                case 413 -> SoclaasException.Category.AI_OUTPUT_TOO_LARGE;
                case 429 -> SoclaasException.Category.AI_RATE_LIMITED;
                default -> SoclaasException.Category.AI_UNAVAILABLE;
                }, status, retryAfter(response));
            }
            if (!response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                throw failure(SoclaasException.Category.AI_INVALID_RESPONSE);
            }
            return response.body();
        }
        throw failure(SoclaasException.Category.AI_UNAVAILABLE);
    }

    private JsonNode parse(byte[] bytes) {
        try {
            JsonNode root = json.readTree(bytes);
            if (root != null && root.isObject()) {
                return root;
            }
        } catch (RuntimeException exception) {
            // Jackson errors can contain provider text. Do not propagate them.
        }
        throw failure(SoclaasException.Category.AI_INVALID_RESPONSE);
    }

    private static Long retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        if (value.matches("[0-9]{1,5}")) {
            long seconds = Long.parseLong(value);
            if (seconds <= 86400) {
                return seconds;
            }
        }
        return null;
    }

    private void requireEnabled() {
        if (!settings.enabled()) {
            throw failure(SoclaasException.Category.AI_UNAVAILABLE);
        }
    }

    private static int utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static SoclaasException failure(SoclaasException.Category category) {
        return new SoclaasException(category);
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    /** Provider catalog metadata, not yet verified as accounting prices. */
    public record Model(String id, JsonNode metadata) {
        @Override
        public String toString() {
            return "SoCLaaS model metadata [content withheld]";
        }
    }

    /** Numeric provider usage; a missing or invalid usage record is represented by null, never zero. */
    public record Usage(long promptTokens, long completionTokens, long totalTokens) { }

    /** Untrusted plain text for authorized callers; stringification intentionally omits content. */
    public record Completion(String text, Usage usage) {
        @Override
        public String toString() {
            return "SoCLaaS completion [content withheld]";
        }
    }
}
