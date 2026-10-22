package com.company.incidentdesk.web.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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

class AdministratorIncidentSecurityTest {
    private static final UUID INCIDENT = UUID.fromString("73000000-0000-0000-0000-000000000001");
    private static final UUID REQUEST = UUID.fromString("73000000-0000-0000-0000-000000000002");

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AdministratorIncidentService incidents;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        incidents = context.getBean(AdministratorIncidentService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void detailRequiresAdministratorRole() throws Exception {
        when(incidents.detail("admin", INCIDENT)).thenReturn(detail());
        String path = "/api/v1/admin/incidents/" + INCIDENT;

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user("reporter").roles("REPORTER"))).andExpect(status().isNotFound());
        mvc.perform(get(path).with(user("admin").roles("ADMINISTRATOR"))).andExpect(status().isOk());
    }

    @Test
    void mutationsRequireAdministratorAndCsrfBeforeCallingTheService() throws Exception {
        String path = "/api/v1/admin/incidents/" + INCIDENT + "/comments";
        String body = "{\"requestId\":\"" + REQUEST + "\",\"text\":\"status update\"}";

        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user("reporter").roles("REPORTER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).with(user("admin").roles("ADMINISTRATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));

        verifyNoInteractions(incidents);
    }

    private static AdministratorIncidentService.IncidentDetail detail() {
        return new AdministratorIncidentService.IncidentDetail(INCIDENT, 0, "Title", "Description", "IT",
                "SUBMITTED", false, "reporter", null, Instant.EPOCH, Instant.EPOCH, null,
                Instant.EPOCH, null, null,
                new AdministratorIncidentService.SloProgress(null, null, null, null,
                        "NOT_CONFIGURED", "NOT_CONFIGURED"),
                List.of(), List.of(), List.of(), List.of());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(WebSecurityConfiguration.class)
    static class TestConfiguration {
        @Bean AdministratorIncidentService incidents() { return mock(AdministratorIncidentService.class); }

        @Bean AdministratorIncidentController controller(AdministratorIncidentService incidents) {
            return new AdministratorIncidentController(incidents);
        }

        @Bean UserDetailsService users() {
            return name -> User.withUsername(name).password("synthetic").roles("ADMINISTRATOR").build();
        }
    }
}
