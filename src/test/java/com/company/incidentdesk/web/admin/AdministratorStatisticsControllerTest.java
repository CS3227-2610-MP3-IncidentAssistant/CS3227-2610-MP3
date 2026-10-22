package com.company.incidentdesk.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.company.incidentdesk.web.ApiExceptionHandler;
import com.company.incidentdesk.web.security.WebSecurityConfiguration;

class AdministratorStatisticsControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AdministratorStatisticsService service;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        service = context.getBean(AdministratorStatisticsService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void readsRequireAdministratorAndUseTrustedPrincipal() throws Exception {
        when(service.read(eq("admin"), any())).thenReturn(available());

        mvc.perform(get("/api/v1/admin/statistics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/statistics").with(user("reporter").roles("REPORTER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/statistics?category=IT").with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.outcome").value("AVAILABLE"))
                .andExpect(jsonPath("$.summary.resolvedIncidentCount").value(0));
    }

    @Test
    void mapsInvalidFiltersToTheStableValidationResponse() throws Exception {
        when(service.read(eq("admin"), any())).thenThrow(new IllegalArgumentException("inverted period"));

        mvc.perform(get("/api/v1/admin/statistics")
                        .param("periodFrom", "2026-02-01T00:00:00Z")
                        .param("periodThrough", "2026-01-01T00:00:00Z")
                        .with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void mapsReauthorizationAndPersistenceFailuresToSafeResponses() throws Exception {
        when(service.read(eq("admin"), any()))
                .thenReturn(AdministratorStatisticsStore.ReadResult.unavailable())
                .thenReturn(AdministratorStatisticsStore.ReadResult.persistenceFailure());

        mvc.perform(adminRequest()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"));
        mvc.perform(adminRequest()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERSISTENCE_FAILURE"));
    }

    @Test
    void securityDenialsDoNotInvokeStatisticsService() throws Exception {
        mvc.perform(get("/api/v1/admin/statistics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/statistics").with(user("responder").roles("RESPONDER")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminRequest() {
        return get("/api/v1/admin/statistics").with(user("admin").roles("ADMINISTRATOR"));
    }

    private static AdministratorStatisticsStore.ReadResult available() {
        return new AdministratorStatisticsStore.ReadResult(
                AdministratorStatisticsStore.ReadOutcome.AVAILABLE,
                AdministratorStatisticsStore.Summary.empty(),
                List.of(),
                new AdministratorStatisticsStore.FilterOptions(
                        List.of(new AdministratorStatisticsStore.IdentityOption(UUID.randomUUID(), "responder")),
                        List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({WebSecurityConfiguration.class, ApiExceptionHandler.class})
    static class TestConfiguration {
        @Bean
        AdministratorStatisticsService statisticsService() {
            return mock(AdministratorStatisticsService.class);
        }

        @Bean
        AdministratorStatisticsController statisticsController(AdministratorStatisticsService service) {
            return new AdministratorStatisticsController(service);
        }

        @Bean
        UserDetailsService users() {
            return name -> User.withUsername(name).password("synthetic").roles("ADMINISTRATOR").build();
        }
    }
}
