package com.company.incidentdesk.web.reporter;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.company.incidentdesk.web.security.SessionController;
import com.company.incidentdesk.web.security.WebSecurityConfiguration;
import com.company.incidentdesk.web.admin.PromotionRequestController;
import com.company.incidentdesk.application.account.PromotionRequestService;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.ResponderAccess;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.persistence.AccountRepository;

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
        AccountRepository accounts = context.getBean(AccountRepository.class);
        when(accounts.findByLoginName("admin")).thenReturn(Optional.of(account("admin", Role.ADMINISTRATOR)));
        when(accounts.findByLoginName("reporter")).thenReturn(Optional.of(account("reporter", Role.REPORTER)));
        PromotionRequestService promotions = context.getBean(PromotionRequestService.class);
        when(promotions.pendingRequestsAs(any(Account.class), anyLong(), anyInt())).thenReturn(java.util.List.of());
        when(promotions.countPendingRequestsAs(any(Account.class))).thenReturn(0L);
        when(promotions.visibleHistoryAs(any(Account.class))).thenReturn(java.util.List.of());
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
    void responderApiRequiresResponderRoleWithoutRevealingItToOtherRoles() throws Exception {
        mvc.perform(post("/api/v1/responder/incidents/query").with(csrf())
                        .contentType("application/json")
                        .content("{\"search\":\"\",\"categories\":[],\"statuses\":[],\"assignment\":\"ALL\",\"page\":0,\"size\":25}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/responder/incidents/query")
                        .with(user("reporter").roles("REPORTER"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"search\":\"private incident text\",\"categories\":[],\"statuses\":[],\"assignment\":\"ALL\",\"page\":0,\"size\":25}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/responder/incidents/query")
                        .with(user("admin").roles("ADMINISTRATOR"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"search\":\"private incident text\",\"categories\":[],\"statuses\":[],\"assignment\":\"ALL\",\"page\":0,\"size\":25}"))
                .andExpect(status().isNotFound());
        MvcResult responderQuery = mvc.perform(post("/api/v1/responder/incidents/query")
                        .with(user("responder").roles("RESPONDER"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"search\":\"private incident text\",\"categories\":[],\"statuses\":[],\"assignment\":\"ALL\",\"page\":0,\"size\":25}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("/api/v1/responder/incidents/query", responderQuery.getRequest().getRequestURI());
        assertNull(responderQuery.getRequest().getQueryString());
        mvc.perform(post("/api/v1/responder/incidents/query")
                        .with(user("responder").roles("RESPONDER"))
                        .contentType("application/json")
                        .content("{\"search\":\"private incident text\",\"categories\":[],\"statuses\":[],\"assignment\":\"ALL\",\"page\":0,\"size\":25}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));
    }

    @Test
    void responderClaimRequiresResponderRoleAndCsrf() throws Exception {
        String claimPath = "/api/v1/responder/incidents/60000000-0000-0000-0000-000000000001/claim";
        mvc.perform(post(claimPath).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(claimPath).with(user("reporter").roles("REPORTER")).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(claimPath).with(user("admin").roles("ADMINISTRATOR")).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(claimPath).with(user("responder").roles("RESPONDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));
        mvc.perform(post(claimPath).with(user("responder").roles("RESPONDER")).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void stateChangeRequiresCsrfForReporter() throws Exception {
        mvc.perform(post("/api/v1/reporter/incidents")
                        .with(user("reporter").roles("REPORTER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));
        mvc.perform(post("/api/v1/reporter/incidents")
                        .with(user("reporter").roles("REPORTER"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void roleSpecificLoginPathsAcceptOnlyTheirPersistedRole() throws Exception {
        mvc.perform(post("/api/v1/auth/reporter/login")
                        .with(csrf())
                        .param("username", "reporter")
                        .param("password", "test-secret"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/administrator/login")
                        .with(csrf())
                        .param("username", "reporter")
                        .param("password", "test-secret"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/administrator/login")
                        .with(csrf())
                        .param("username", "administrator")
                        .param("password", "test-secret"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/reporter/login")
                        .with(csrf())
                        .param("username", "administrator")
                        .param("password", "test-secret"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/responder/login")
                        .with(csrf())
                        .param("username", "responder")
                        .param("password", "test-secret"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/responder/login")
                        .with(csrf())
                        .param("username", "reporter")
                        .param("password", "test-secret"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .param("username", "reporter")
                        .param("password", "test-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void promotionEndpointsRequireTheCorrectRoleAndCsrfForDecisions() throws Exception {
        mvc.perform(get("/api/v1/admin/promotion-requests"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/promotion-requests")
                        .with(user("reporter").roles("REPORTER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/promotion-requests")
                        .with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/promotion-requests/00000000-0000-0000-0000-000000000001/decision")
                        .with(user("admin").roles("ADMINISTRATOR"))
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"approvedCategories\":[\"IT\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));
    }

    @Test
    void spaCsrfCookieRotatesAtLoginAndFreshTokenAllowsLogout() throws Exception {
        MvcResult anonymousSession = mvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(jsonPath("$.csrfToken").doesNotExist())
                .andReturn();
        Cookie anonymousToken = anonymousSession.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(anonymousToken);

        MvcResult login = mvc.perform(post("/api/v1/auth/reporter/login")
                        .cookie(anonymousToken)
                        .header("X-XSRF-TOKEN", anonymousToken.getValue())
                        .param("username", "reporter")
                        .param("password", "test-secret"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0))
                .andReturn();
        MockHttpSession authenticatedSession = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(authenticatedSession);

        MvcResult refreshed = mvc.perform(get("/api/v1/auth/session")
                        .session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        Cookie authenticatedToken = refreshed.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(authenticatedToken);

        mvc.perform(post("/api/v1/auth/logout")
                        .session(authenticatedSession)
                        .cookie(authenticatedToken)
                        .header("X-XSRF-TOKEN", anonymousToken.getValue()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));

        mvc.perform(post("/api/v1/auth/logout")
                        .session(authenticatedSession)
                        .cookie(authenticatedToken)
                        .header("X-XSRF-TOKEN", authenticatedToken.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));
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
                    .roles("administrator".equals(username) ? "ADMINISTRATOR"
                            : "responder".equals(username) ? "RESPONDER" : "REPORTER")
                    .build();
        }

        @Bean
        ProtectedReporterEndpoint endpoint() {
            return new ProtectedReporterEndpoint();
        }

        @Bean
        ProtectedResponderEndpoint responderEndpoint() {
            return new ProtectedResponderEndpoint();
        }

        @Bean
        SessionController sessionController() {
            return new SessionController();
        }

        @Bean
        javax.sql.DataSource testDataSource() {
            return new DriverManagerDataSource();
        }

        @Bean
        AccountRepository accountRepository() {
            return mock(AccountRepository.class);
        }

        @Bean
        PromotionRequestService promotionRequestService() {
            return mock(PromotionRequestService.class);
        }

        @Bean
        PromotionRequestController promotionRequestController(
                PromotionRequestService promotions, AccountRepository accounts) {
            return new PromotionRequestController(promotions, accounts);
        }
    }

    private static Account account(String name, Role role) {
        return new Account(new AccountId(java.util.UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))),
                name, role, AccountStatus.ENABLED, ResponderAccess.NONE);
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

    @RestController
    static class ProtectedResponderEndpoint {
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/responder/incidents/query")
        void queryIncidents() {
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/responder/incidents/{id}/claim")
        void claimIncident() {
        }
    }
}
