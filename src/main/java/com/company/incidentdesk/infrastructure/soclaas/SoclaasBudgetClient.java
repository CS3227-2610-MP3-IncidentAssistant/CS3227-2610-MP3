package com.company.incidentdesk.infrastructure.soclaas;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Backend-only budget discovery. Unknown funding semantics deny new AI requests. */
final class SoclaasBudgetClient implements AutoCloseable {
    private static final URI ENDPOINT = URI.create("https://soclaas-portal.comp.nus.edu.sg/api-key/budget");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final SoclaasSettings settings;
    private final Clock clock;
    private final URI endpoint;
    private final HttpClient http;

    SoclaasBudgetClient(SoclaasSettings settings, Clock clock) { this(settings, clock, ENDPOINT); }

    // Loopback fake-provider seam; no deployment or request parameter can override the production host.
    SoclaasBudgetClient(SoclaasSettings settings, Clock clock, URI endpoint) {
        this.settings = settings;
        this.clock = clock;
        this.endpoint = endpoint;
        http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    Budget load() {
        if (!settings.enabled()) { throw failure(); }
        Instant started = clock.instant();
        Duration deadline = settings.requestTimeout().compareTo(Duration.ofSeconds(5)) < 0
                ? settings.requestTimeout() : Duration.ofSeconds(5);
        var request = HttpRequest.newBuilder(endpoint).timeout(deadline)
                .header("Authorization", "Bearer " + settings.apiKey()).header("Accept", "application/json").GET().build();
        var pending = http.sendAsync(request, info -> new BoundedBodySubscriber(info.statusCode() == 200 ? 16384 : 0));
        try {
            var response = pending.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
            int status = response.statusCode();
            if (status != 200) {
                Long retry = null;
                String hint = response.headers().firstValue("Retry-After").orElse("");
                if (hint.matches("[0-9]{1,5}") && Long.parseLong(hint) <= 86400) { retry = Long.parseLong(hint); }
                throw new SoclaasException(status == 429 ? SoclaasException.Category.AI_RATE_LIMITED
                        : status == 401 || status == 403 ? SoclaasException.Category.AI_REJECTED
                        : SoclaasException.Category.AI_UNAVAILABLE, status, retry);
            }
            if (!response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip()
                    .equalsIgnoreCase("application/json")) { throw failure(); }
            return parse(response.body(), started);
        } catch (InterruptedException unsafe) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure();
        } catch (TimeoutException unsafe) {
            pending.cancel(true);
            throw new SoclaasException(SoclaasException.Category.AI_TIMEOUT);
        } catch (ExecutionException unsafe) {
            Throwable cause = unsafe;
            while (cause != null) {
                if (cause instanceof SoclaasException safe) { throw safe; }
                if (cause instanceof HttpTimeoutException) { throw new SoclaasException(SoclaasException.Category.AI_TIMEOUT); }
                cause = cause.getCause();
            }
            throw failure();
        }
    }

    static Budget parse(byte[] bytes, Instant verifiedAt) {
        try {
            JsonNode root = JSON.readTree(bytes);
            if (root == null || !root.isObject() || !root.path("api_key").path("is_active").isBoolean()
                    || !root.path("api_key").path("is_active").asBoolean()) { throw failure(); }
            var policy = root.path("policy");
            var effective = root.path("effective_limits");
            var credits = root.path("credits");
            // Only the observed allowance policy is verified. Credit-funded policies need a reviewed unit contract.
            if (!"allowance_and_credits".equals(policy.path("normal_service_funding_mode").asString(""))
                    || !policy.path("allow_excess_after_funding_exhaustion").isBoolean()
                    || !policy.path("allow_excess_after_funding_exhaustion").asBoolean()) { throw failure(); }
            for (String field : new String[] {"total_granted", "total_used", "remaining"}) {
                var value = credits.path(field);
                if ((!value.isNumber() && !value.isString()) || new BigDecimal(value.asString()).signum() != 0) {
                    throw failure();
                }
            }
            var windows = root.path("windows");
            var utc = verifiedAt.atOffset(ZoneOffset.UTC);
            if (!"UTC".equals(windows.path("timezone").asString(""))
                    || !Instant.parse(windows.path("day_start").asString()).equals(utc.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC))
                    || !Instant.parse(windows.path("month_start").asString()).equals(utc.toLocalDate().withDayOfMonth(1)
                            .atStartOfDay().toInstant(ZoneOffset.UTC))) { throw failure(); }
            long rpm = number(policy.path("requests_per_minute_limit"));
            if (rpm > Integer.MAX_VALUE) { throw failure(); }
            long daily = Math.min(number(policy.path("daily_microdollar_allowance")),
                    number(effective.path("daily_microdollar_allowance")));
            long monthly = Math.min(number(policy.path("monthly_microdollar_allowance")),
                    number(effective.path("monthly_microdollar_allowance")));
            for (JsonNode limits : new JsonNode[] {policy, effective}) {
                daily = cap(daily, limits.path("daily_microdollar_spend_cap"));
                monthly = cap(monthly, limits.path("monthly_microdollar_spend_cap"));
            }
            return new Budget(verifiedAt, (int) rpm, daily, monthly, OptionalLong.empty(),
                    number(root.path("usage").path("current_day_spend")),
                    number(root.path("usage").path("current_month_spend")));
        } catch (RuntimeException unsafe) { throw failure(); }
    }

    private static long cap(long allowance, JsonNode cap) { return cap.isNull() ? allowance : Math.min(allowance, number(cap)); }
    private static long number(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) { throw failure(); }
        return value.asLong();
    }
    private static SoclaasException failure() { return new SoclaasException(SoclaasException.Category.AI_UNAVAILABLE); }
    @Override public void close() { http.shutdownNow(); }

    record Budget(Instant verifiedAt, int requestsPerMinute, long daily, long monthly, OptionalLong lifetime,
            long daySpent, long monthSpent) {
        AiUsagePolicy.Snapshot snapshot(AiUsagePolicy.Price price, Instant catalogVerifiedAt) {
            return new AiUsagePolicy.Snapshot(verifiedAt.isBefore(catalogVerifiedAt) ? verifiedAt : catalogVerifiedAt,
                    requestsPerMinute, daily, monthly, lifetime.isPresent() ? lifetime.getAsLong() : null,
                    daySpent, monthSpent, 0, price);
        }
    }
}
