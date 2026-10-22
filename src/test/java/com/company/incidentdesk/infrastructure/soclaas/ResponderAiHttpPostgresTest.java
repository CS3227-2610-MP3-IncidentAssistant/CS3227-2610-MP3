package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.company.incidentdesk.IncidentDeskWebApplication;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** Real security chain, route, wired selector, gateway, fake provider, and isolated database. */
@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_URL", matches = ".+")
class ResponderAiHttpPostgresTest extends AiGatewayPostgresFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static AiGatewayFakeProvider provider;

    @Test
    void authorizedPayloadAndRevocationDuringProviderDelay() throws Exception {
        var fakeProvider = new AiGatewayFakeProvider();
        provider = fakeProvider;
        try (fakeProvider; var application = new SpringApplicationBuilder(Dependencies.class, IncidentDeskWebApplication.class)
                .run("--server.port=0", "--AI_ENABLED=true", "--SOCLAAS_API_KEY=synthetic-responder-test",
                        "--SOCLAAS_MODEL=test", "--INCIDENT_DESK_SECURE_COOKIES=false",
                        "--spring.flyway.enabled=false", "--spring.main.banner-mode=off")) {
            assertInstanceOf(JdbcResponderAiSelector.class, application.getBean(AiDataSelector.class));
            application.getBean(AiGatewayMaintenance.class).runOnce();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) application)
                    .apply(SecurityMockMvcConfigurers.springSecurity()).build();
            UUID reporter = account("REPORTER");
            UUID responder = account("RESPONDER");
            UUID outsider = account("RESPONDER");
            UUID incident = UUID.randomUUID();
            Instant queue = Instant.now().minusSeconds(600);
            jdbc.update("INSERT INTO responder_category_access(account_id,category,granted_at) VALUES (?,'IT',?)",
                    responder, java.sql.Timestamp.from(queue));
            jdbc.update("""
                    INSERT INTO incidents(id,reporter_id,title,description,category,status,anonymous,created_at,
                                          submitted_at,queue_entered_at)
                    VALUES (?,?,'Synthetic title','Untrusted <script>instruction</script>','IT','SUBMITTED',true,?,?,?)
                    """, incident, reporter, java.sql.Timestamp.from(queue), java.sql.Timestamp.from(queue),
                    java.sql.Timestamp.from(queue));
            String path = "/api/v1/responder/incidents/" + incident + "/ai/advice";

            mvc.perform(post(path).with(user(outsider.toString()).roles("RESPONDER")).with(csrf()))
                    .andExpect(result -> assertEquals(404, result.getResponse().getStatus()));
            assertEquals(0, provider.calls.get());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_usage_requests", Integer.class));

            mvc.perform(post(path).with(user(responder.toString()).roles("RESPONDER")).with(csrf()))
                    .andExpect(result -> assertEquals(200, result.getResponse().getStatus()));
            var sent = JSON.readTree(provider.received.get());
            assertFalse(sent.has("tools"));
            var data = JSON.readTree(sent.path("messages").get(1).path("content").asString());
            assertEquals(9, data.size());
            assertEquals(incident.toString(), data.path("incidentId").asString());
            assertEquals("QUEUE", data.path("stage").asString());
            assertEquals("Untrusted <script>instruction</script>", data.path("description").asString());
            assertFalse(provider.received.get().contains(reporter.toString()));
            assertFalse(provider.received.get().contains(responder.toString()));
            assertEquals("SUBMITTED", jdbc.queryForObject("SELECT status FROM incidents WHERE id=?", String.class, incident));

            provider.hold = new CountDownLatch(1);
            var late = CompletableFuture.supplyAsync(() -> {
                try {
                    return mvc.perform(post(path).with(user(responder.toString()).roles("RESPONDER")).with(csrf()))
                            .andReturn().getResponse().getStatus();
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            provider.waitForCalls(2);
            jdbc.update("DELETE FROM responder_category_access WHERE account_id=?", responder);
            provider.hold.countDown();
            assertEquals(404, late.join());
            assertEquals("AI_REJECTED", jdbc.queryForObject("""
                    SELECT gateway_outcome FROM ai_usage_requests ORDER BY admitted_at DESC LIMIT 1
                    """, String.class));
            assertEquals("AI_REJECTED", jdbc.queryForObject("""
                    SELECT details->>'category' FROM audit_events WHERE action='AI_REQUEST_FINISHED'
                    ORDER BY sequence DESC LIMIT 1
                    """, String.class));
            assertTrue(jdbc.queryForObject("""
                    SELECT charged=reserved FROM ai_usage_requests ORDER BY admitted_at DESC LIMIT 1
                    """, Boolean.class));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ai_usage_requests", Integer.class));
            assertEquals("SUBMITTED", jdbc.queryForObject("SELECT status FROM incidents WHERE id=?", String.class, incident));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean(destroyMethod = "") DataSource responderTestDataSource() { return source; }
        @Bean(destroyMethod = "close") @Primary
        SoclaasClient responderFakeClient(SoclaasSettings settings) { return new SoclaasClient(settings, provider.origin()); }
        @Bean(destroyMethod = "close") @Primary
        AiGatewayMaintenance responderFakeMaintenance(SoclaasSettings settings, AiGatewayControl control,
                SoclaasClient client, MeterRegistry metrics) {
            return new AiGatewayMaintenance(settings, control, client,
                    () -> new SoclaasBudgetClient.Budget(Instant.now(), 30, 50000000, 500000000,
                            OptionalLong.empty(), 0, 0), Clock.systemUTC(), metrics);
        }
    }
}
