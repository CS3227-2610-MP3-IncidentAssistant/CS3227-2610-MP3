package com.company.incidentdesk.web.reporter;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.company.incidentdesk.web.security.WebSecurityConfiguration;

class ReporterWebSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void reporterApiRequiresReporterRoleWithoutRevealingItToOtherRoles() throws Exception {
        mvc.perform(get("/api/v1/reporter/incidents"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reporter/incidents")
                        .with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reporter/incidents")
                        .with(user("reporter").roles("REPORTER")))
                .andExpect(status().isOk());
    }

    @Test
    void stateChangeRequiresCsrfForReporter() throws Exception {
        mvc.perform(post("/api/v1/reporter/incidents")
                        .with(user("reporter").roles("REPORTER")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/reporter/incidents")
                        .with(user("reporter").roles("REPORTER"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void rolePortalAcceptsReporterAndGenericallyRejectsWrongRole() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .param("username", "reporter")
                        .param("password", "test-secret")
                        .param("portal", "REPORTER"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .param("username", "reporter")
                        .param("password", "test-secret")
                        .param("portal", "ADMINISTRATOR"))
                .andExpect(status().isUnauthorized());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(WebSecurityConfiguration.class)
    static class TestConfiguration {
        @Bean
        UserDetailsService users(PasswordEncoder passwords) {
            return username -> User.withUsername(username)
                    .password(passwords.encode("test-secret"))
                    .roles("REPORTER")
                    .build();
        }

        @Bean
        ProtectedReporterEndpoint endpoint() {
            return new ProtectedReporterEndpoint();
        }
    }

    @RestController
    static class ProtectedReporterEndpoint {
        @GetMapping("/api/v1/reporter/incidents")
        void getIncidents() {
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/reporter/incidents")
        void submitIncident() {
        }
    }
}
