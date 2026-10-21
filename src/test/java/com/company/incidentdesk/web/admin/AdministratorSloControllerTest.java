package com.company.incidentdesk.web.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.company.incidentdesk.web.security.WebSecurityConfiguration;

class AdministratorSloControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AdministratorSloService service;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        service = context.getBean(AdministratorSloService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void readsRequireAdministratorAndUseTrustedPrincipal() throws Exception {
        var version = new AdministratorSloService.SloVersionView(
                UUID.randomUUID(), "IT", 10, 20, new BigDecimal("5.00"),
                Instant.parse("2026-10-21T12:00:00Z"));
        when(service.configuration("admin")).thenReturn(new AdministratorSloService.SloConfiguration(
                AdministratorSloStore.ReadOutcome.AVAILABLE,
                List.of(new AdministratorSloService.CategoryConfiguration("IT", version, List.of(version)))));

        mvc.perform(get("/api/v1/admin/slo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/slo").with(user("reporter").roles("REPORTER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/slo").with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.outcome").value("AVAILABLE"))
                .andExpect(jsonPath("$.categories[0].current.changedBy").doesNotExist());
    }

    @Test
    void changesRequireAdministratorAndCsrfBeforeServiceCall() throws Exception {
        String request = """
                {"timeToClaimMinutes":10,"timeInProgressMinutes":20,"reopenRatePercent":5,"expectedVersionId":null}
                """;
        mvc.perform(post("/api/v1/admin/slo/IT").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/slo/IT").with(user("reporter").roles("REPORTER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/slo/IT").with(user("admin").roles("ADMINISTRATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));

        verifyNoInteractions(service);
    }

    @Test
    void mapsValidationConflictNoOpAndSuccessWithoutLeakingDetails() throws Exception {
        var request = new AdministratorSloService.ChangeRequest(10L, 20L, java.math.BigDecimal.valueOf(5), null);
        when(service.change("admin", "IT", request))
                .thenReturn(AdministratorSloService.ChangeResult.validation(Map.of("timeToClaimMinutes", "invalid")))
                .thenReturn(new AdministratorSloService.ChangeResult(
                        AdministratorSloStore.ChangeOutcome.CONFLICT, Map.of()))
                .thenReturn(new AdministratorSloService.ChangeResult(
                        AdministratorSloStore.ChangeOutcome.UNCHANGED, Map.of()))
                .thenReturn(new AdministratorSloService.ChangeResult(
                        AdministratorSloStore.ChangeOutcome.CHANGED, Map.of()));

        mvc.perform(validRequest()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.timeToClaimMinutes").value("invalid"));
        mvc.perform(validRequest()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(validRequest()).andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));
        mvc.perform(validRequest()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.changed").value(true));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/admin/slo/IT")
                .with(user("admin").roles("ADMINISTRATOR")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"timeToClaimMinutes":10,"timeInProgressMinutes":20,"reopenRatePercent":5,"expectedVersionId":null}
                        """);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(WebSecurityConfiguration.class)
    static class TestConfiguration {
        @Bean
        AdministratorSloService service() {
            return mock(AdministratorSloService.class);
        }

        @Bean
        AdministratorSloController controller(AdministratorSloService service) {
            return new AdministratorSloController(service);
        }

        @Bean
        UserDetailsService users() {
            return name -> User.withUsername(name).password("synthetic").roles("ADMINISTRATOR").build();
        }
    }
}
