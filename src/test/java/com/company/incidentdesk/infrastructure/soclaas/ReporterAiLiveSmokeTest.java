package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.company.incidentdesk.IncidentDeskWebApplication;

import tools.jackson.databind.json.JsonMapper;

/** One opt-in synthetic Reporter request through the real HTTP route and provider; never required by CI. */
@EnabledIfEnvironmentVariable(named = "SOCLAAS_REPORTER_LIVE_SMOKE", matches = "true")
class ReporterAiLiveSmokeTest extends AiGatewayPostgresFixture {
    @Test
    void oneSyntheticDraftUsesRealGatewayWithoutSavingAnIncident() throws Exception {
        try (var application = new SpringApplicationBuilder(Dependencies.class, IncidentDeskWebApplication.class)
                .run("--server.port=0", "--AI_ENABLED=true", "--INCIDENT_DESK_SECURE_COOKIES=false",
                        "--spring.flyway.enabled=false", "--spring.main.banner-mode=off");
                var browser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .connectTimeout(Duration.ofSeconds(3)).build()) {
            var actor = account("REPORTER");
            jdbc.update("INSERT INTO account_credentials(account_id,password_hash) VALUES (?,?)", actor,
                    application.getBean(PasswordEncoder.class).encode("synthetic-live-password-42"));
            URI origin = URI.create("http://127.0.0.1:"
                    + application.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
            var json = JsonMapper.builder().build();
            var session = browser.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/session"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            var csrf = json.readTree(session.body());
            var login = browser.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/reporter/login"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded")
                    .header(csrf.path("csrfHeader").asString(), csrf.path("csrfToken").asString())
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + actor + "&password=synthetic-live-password-42"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, login.statusCode());
            session = browser.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/session"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            csrf = json.readTree(session.body());
            var result = browser.send(HttpRequest.newBuilder(origin.resolve("/api/v1/reporter/ai/description"))
                    .timeout(Duration.ofSeconds(35)).header("Content-Type", "application/json")
                    .header(csrf.path("csrfHeader").asString(), csrf.path("csrfToken").asString())
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"title":"Office fall","category":"IT",
                            "description":"i fell from the office window"}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            // Only constrained ledger categories belong in diagnostics, never provider text or credentials.
            var outcomes = jdbc.queryForList("SELECT gateway_outcome FROM ai_usage_requests", String.class);
            assertEquals(200, result.statusCode(), "Synthetic Reporter assistance did not return HTTP 200; outcomes=" + outcomes);
            assertFalse(json.readTree(result.body()).path("description").asString().isBlank());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incidents", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_requests", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action LIKE 'AI_%'", Integer.class));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean(destroyMethod = "") DataSource reporterLiveTestDataSource() { return source; }
    }
}
