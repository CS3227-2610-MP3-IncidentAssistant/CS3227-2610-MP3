package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;
import com.company.incidentdesk.web.security.WebSecurityConfiguration;

/**
 * AI-ADM-AUTH-01: only a currently enabled Administrator reaches the provider or the analytics
 * registry. Every other caller receives a non-enumerating denial with no provider or analytics call.
 */
class AdministratorInsightSecurityTest {
    private static final String PATH = "/api/v1/admin/statistics/insights";
    private static final String QUESTION = "{\"question\":\"What changed in the past 30 days?\"}";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private RecordingAnalytics analytics;
    private SecuredAiGateway gateway;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        analytics = context.getBean(RecordingAnalytics.class);
        gateway = context.getBean(SecuredAiGateway.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void unauthenticatedRequestIsDeniedBeforeAnyProviderOrAnalyticsCall() throws Exception {
        mvc.perform(ask().with(csrf())).andExpect(status().isUnauthorized());

        assertNoProviderOrAnalyticsCall();
    }

    @Test
    void reporterRequestIsDeniedWithoutEnumeratingTheAdministratorRoute() throws Exception {
        mvc.perform(ask().with(user("reporter").roles("REPORTER")).with(csrf()))
                .andExpect(status().isNotFound());

        assertNoProviderOrAnalyticsCall();
    }

    @Test
    void responderRequestIsDeniedWithoutEnumeratingTheAdministratorRoute() throws Exception {
        mvc.perform(ask().with(user("responder").roles("RESPONDER")).with(csrf()))
                .andExpect(status().isNotFound());

        assertNoProviderOrAnalyticsCall();
    }

    @Test
    void missingCsrfTokenIsRejectedBeforeAnyProviderOrAnalyticsCall() throws Exception {
        mvc.perform(ask().with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));

        assertNoProviderOrAnalyticsCall();
    }

    @Test
    void administratorDisabledOrDemotedInTheDatabaseReceivesTheGenericUnavailableResponse() throws Exception {
        analytics.authorized = false;

        mvc.perform(ask().with(user("admin").roles("ADMINISTRATOR")).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Resource unavailable"));

        verify(gateway, never()).assist(any());
        assertEquals(0, analytics.executions);
    }

    @Test
    void enabledAdministratorReachesTheProviderAndTheAnalyticsRegistry() throws Exception {
        mvc.perform(ask().with(user("admin").roles("ADMINISTRATOR")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ANSWER"));

        verify(gateway).assist(any());
        assertEquals(1, analytics.executions);
    }

    private void assertNoProviderOrAnalyticsCall() {
        verifyNoInteractions(gateway);
        assertEquals(0, analytics.executions);
    }

    private static MockHttpServletRequestBuilder ask() {
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content(QUESTION);
    }

    /** Stands in for PostgreSQL so a stale or demoted Administrator can be simulated deterministically. */
    static final class RecordingAnalytics implements AdministratorInsightAnalytics {
        boolean authorized = true;
        int executions;

        @Override public List<String> knownLoginNames(String actor) {
            if (!authorized) throw new JdbcAdministratorInsightAnalytics.AnalyticsUnavailableException();
            return List.of();
        }

        @Override public Result execute(String actor, Query query) {
            if (!authorized) throw new JdbcAdministratorInsightAnalytics.AnalyticsUnavailableException();
            executions++;
            return new Result(List.of(), query.periods().requestInstant());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(WebSecurityConfiguration.class)
    static class TestConfiguration {
        @Bean RecordingAnalytics analytics() { return new RecordingAnalytics(); }

        @Bean SecuredAiGateway gateway() {
            SecuredAiGateway gateway = mock(SecuredAiGateway.class);
            when(gateway.assist(any())).thenReturn(new SecuredAiGateway.Suggestion("""
                    {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT","metric":"UNASSIGNED_BACKLOG_COUNT",
                     "period":"ROLLING_30_DAYS"}
                    """));
            return gateway;
        }

        @SuppressWarnings("unchecked")
        @Bean AdministratorInsightService service(RecordingAnalytics analytics, SecuredAiGateway gateway) {
            ObjectProvider<SecuredAiGateway> gateways = mock(ObjectProvider.class);
            when(gateways.getIfAvailable()).thenReturn(gateway);
            return new AdministratorInsightService(analytics, gateways,
                    Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneOffset.UTC));
        }

        @Bean AdministratorInsightController controller(AdministratorInsightService service) {
            return new AdministratorInsightController(service);
        }

        @Bean UserDetailsService users() {
            return name -> User.withUsername(name).password("synthetic").roles("ADMINISTRATOR").build();
        }
    }
}
