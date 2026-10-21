package com.company.incidentdesk.web.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
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

import com.company.incidentdesk.web.security.WebSecurityConfiguration;

class AdministratorAccountControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AdministratorAccountService accounts;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        accounts = context.getBean(AdministratorAccountService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void directoryRequiresAdministratorAndUsesTrustedPrincipal() throws Exception {
        var page = new AdministratorAccountService.AccountPage(true, List.of(), 0, 25, 0, 1);
        when(accounts.accounts("admin", 0, 25)).thenReturn(page);

        mvc.perform(get("/api/v1/admin/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/accounts").with(user("reporter").roles("REPORTER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/accounts").with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.authorized").value(true));

        verify(accounts).accounts("admin", 0, 25);
    }

    @Test
    void tombstoneRequiresAdministratorAndCsrfBeforeMutation() throws Exception {
        UUID target = UUID.randomUUID();
        String path = "/api/v1/admin/accounts/" + target + "/tombstone";

        mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user("reporter").roles("REPORTER")).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));

        verifyNoInteractions(accounts);
    }

    @Test
    void tombstoneMapsSuccessSelfDenialAndConcurrentConflictSafely() throws Exception {
        UUID success = UUID.randomUUID();
        UUID self = UUID.randomUUID();
        UUID conflict = UUID.randomUUID();
        when(accounts.tombstone("admin", success)).thenReturn(AccountTombstoneOutcome.TOMBSTONED);
        when(accounts.tombstone("admin", self)).thenReturn(AccountTombstoneOutcome.INVALID_STATE);
        when(accounts.tombstone("admin", conflict)).thenReturn(AccountTombstoneOutcome.CONFLICT);

        mvc.perform(post(path(success)).with(user("admin").roles("ADMINISTRATOR")).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(path(self)).with(user("admin").roles("ADMINISTRATOR")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(post(path(conflict)).with(user("admin").roles("ADMINISTRATOR")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    private static String path(UUID accountId) {
        return "/api/v1/admin/accounts/" + accountId + "/tombstone";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(WebSecurityConfiguration.class)
    static class TestConfiguration {
        @Bean
        AdministratorAccountService accounts() {
            return mock(AdministratorAccountService.class);
        }

        @Bean
        AdministratorAccountController controller(AdministratorAccountService accounts) {
            return new AdministratorAccountController(accounts);
        }

        @Bean
        UserDetailsService users() {
            return name -> User.withUsername(name).password("synthetic").roles("ADMINISTRATOR").build();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
