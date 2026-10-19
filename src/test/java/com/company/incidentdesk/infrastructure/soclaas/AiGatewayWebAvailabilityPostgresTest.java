package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.company.incidentdesk.IncidentDeskWebApplication;
import com.company.incidentdesk.domain.incident.IncidentCategory;

import io.micrometer.core.instrument.MeterRegistry;

class AiGatewayWebAvailabilityPostgresTest extends AiGatewayPostgresFixture {
    private static AiGatewayFakeProvider provider;
    private static boolean failInitialBudget;

    @Test
    void providerFailureAtStartupOpensOnlyTheAiCircuit() throws Exception {
        failInitialBudget = true;
        try (var fake = new AiGatewayFakeProvider()) {
            provider = fake;
            try (var application = new SpringApplicationBuilder(WebDependencies.class, IncidentDeskWebApplication.class)
                    .run("--server.port=0", "--AI_ENABLED=true", "--SOCLAAS_API_KEY=synthetic-web-test",
                            "--SOCLAAS_MODEL=test", "--INCIDENT_DESK_SECURE_COOKIES=false",
                            "--spring.flyway.enabled=false", "--spring.main.banner-mode=off")) {
                assertNotNull(application.getBean(SecuredAiGateway.class));
                assertEquals("BLOCKED", jdbc.queryForObject("SELECT circuit_kind FROM ai_usage_control", String.class));
                assertNull(jdbc.queryForObject("SELECT verified_at FROM ai_usage_control", java.sql.Timestamp.class));
                int port = application.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                try (var browser = HttpClient.newHttpClient()) {
                    assertAvailable(browser, URI.create("http://127.0.0.1:" + port), "/health");
                }
            }
        } finally { provider = null; failInitialBudget = false; }
    }

    @Test
    void authenticatedIncidentHttpEndpointsRemainAvailableDuringSlowAndFailedAi() throws Exception {
        try (var fake = new AiGatewayFakeProvider()) {
            provider = fake;
            try (var application = new SpringApplicationBuilder(WebDependencies.class, IncidentDeskWebApplication.class)
                    .run("--server.port=0", "--AI_ENABLED=true", "--SOCLAAS_API_KEY=synthetic-web-test",
                            "--SOCLAAS_MODEL=test", "--INCIDENT_DESK_SECURE_COOKIES=false",
                            "--spring.flyway.enabled=false", "--spring.main.banner-mode=off")) {
                var gateway = application.getBean(SecuredAiGateway.class);
                int port = application.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                URI origin = URI.create("http://127.0.0.1:" + port);
                UUID loginActor = account("REPORTER");
                String password = "synthetic-password-42";
                jdbc.update("INSERT INTO account_credentials(account_id,password_hash) VALUES (?,?)", loginActor,
                        application.getBean(PasswordEncoder.class).encode(password));
                var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
                try (var browser = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build()) {
                    var session = browser.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/session"))
                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, session.statusCode());
                    String form = "username=" + loginActor + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
                    var login = browser.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/reporter/login"))
                            .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-XSRF-TOKEN", csrfToken(cookies, origin))
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(204, login.statusCode());
                    assertAvailable(browser, origin, "/api/v1/reporter/incidents");
                    var actors = new ArrayList<UUID>();
                    for (int index = 0; index < 8; index++) { actors.add(account("REPORTER")); }
                    fake.hold = new CountDownLatch(1);
                    try (var callers = Executors.newFixedThreadPool(8)) {
                        var results = new ArrayList<Future<SecuredAiGateway.Suggestion>>();
                        for (UUID actor : actors) {
                            results.add(callers.submit(() -> {
                                authenticate(actor, "REPORTER");
                                try { return gateway.assist(draft()); }
                                finally { SecurityContextHolder.clearContext(); }
                            }));
                        }
                        try {
                            fake.waitForCalls(8);
                            assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_requests WHERE outcome='IN_FLIGHT'", Integer.class));
                            assertAvailable(browser, origin, "/health");
                            assertAvailable(browser, origin, "/api/v1/reporter/incidents");
                        } finally { fake.hold.countDown(); }
                        for (var result : results) { assertEquals("suggestion", result.get(5, TimeUnit.SECONDS).text()); }
                    }
                    fake.hold = null;
                    fake.status = 503;
                    authenticate(loginActor, "REPORTER");
                    for (int index = 0; index < 3; index++) {
                        assertEquals(SoclaasException.Category.AI_UNAVAILABLE,
                                assertThrows(SoclaasException.class, () -> gateway.assist(draft())).category());
                    }
                    assertEquals("TRANSIENT", jdbc.queryForObject("SELECT circuit_kind FROM ai_usage_control", String.class));
                    assertEquals(11, fake.calls.get());
                    assertAvailable(browser, origin, "/health");
                    assertAvailable(browser, origin, "/api/v1/reporter/incidents");
                }
            } finally { provider = null; }
        }
    }

    private static AiRequest.ReporterDraft draft() {
        return new AiRequest.ReporterDraft("Broken display", IncidentCategory.IT, "The display is flickering.");
    }

    private static String csrfToken(CookieManager cookies, URI origin) {
        return cookies.getCookieStore().get(origin).stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()) && !cookie.hasExpired())
                .map(cookie -> URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8))
                .findFirst().orElseThrow(() -> new AssertionError("Expected session CSRF cookie"));
    }

    private static void assertAvailable(HttpClient browser, URI origin, String path) throws Exception {
        long started = System.nanoTime();
        var response = browser.send(HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WebDependencies {
        @Bean(destroyMethod = "")
        DataSource webTestDataSource() { return source; }

        @Bean(destroyMethod = "close")
        @Primary
        SoclaasClient webFakeClient(SoclaasSettings settings) { return new SoclaasClient(settings, provider.origin()); }

        @Bean(destroyMethod = "close")
        @Primary
        AiGatewayMaintenance webFakeMaintenance(SoclaasSettings settings, AiGatewayControl control,
                SoclaasClient client, MeterRegistry metrics) {
            return new AiGatewayMaintenance(settings, control, client,
                    () -> {
                        if (failInitialBudget) { throw new SoclaasException(SoclaasException.Category.AI_REJECTED, 403, null); }
                        return new SoclaasBudgetClient.Budget(Instant.now(), 30, 50000000, 500000000,
                                OptionalLong.empty(), 0, 0);
                    }, Clock.systemUTC(), metrics);
        }
    }
}
