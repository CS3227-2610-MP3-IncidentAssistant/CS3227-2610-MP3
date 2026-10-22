package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves ADM-ACC-04-D and ADM-ACC-04-E: an expired temporary credential cannot authenticate,
 * and an unexpired temporary credential reaches only password replacement and logout.
 */
class TemporaryCredentialGateTest {
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void expiredTemporaryCredentialCannotAuthenticate() {
        UserDetails expired = loadUser(true, NOW.minusSeconds(1));

        assertFalse(expired.isEnabled(), "an expired temporary credential must not authenticate");
    }

    @Test
    void unexpiredTemporaryCredentialAuthenticatesAndCarriesTheTemporaryMarker() {
        UserDetails temporary = loadUser(true, NOW.plusSeconds(3600));

        assertTrue(temporary.isEnabled());
        assertTrue(temporary.getAuthorities().stream().anyMatch(authority -> authority.getAuthority()
                .equals(DatabaseAccountDetailsService.TEMPORARY_CREDENTIAL_AUTHORITY)));
    }

    @Test
    void permanentCredentialCarriesOnlyItsRole() {
        UserDetails permanent = loadUser(false, null);

        assertTrue(permanent.isEnabled());
        assertFalse(permanent.getAuthorities().stream().anyMatch(authority -> authority.getAuthority()
                .equals(DatabaseAccountDetailsService.TEMPORARY_CREDENTIAL_AUTHORITY)));
    }

    @Test
    void temporaryCredentialIsRefusedEveryRoleWorkflowButMayReplaceItsPasswordAndSignOut() throws Exception {
        var temporaryAdmin = user("admin").roles("ADMINISTRATOR")
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_ADMINISTRATOR"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                DatabaseAccountDetailsService.TEMPORARY_CREDENTIAL_AUTHORITY));

        mvc.perform(get("/api/v1/admin/accounts").with(temporaryAdmin))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/auth/password").with(temporaryAdmin).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").with(temporaryAdmin).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void sessionResponseExposesOnlyTheTemporaryCredentialFlag() throws Exception {
        var temporaryAdmin = user("admin").roles("ADMINISTRATOR")
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_ADMINISTRATOR"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                DatabaseAccountDetailsService.TEMPORARY_CREDENTIAL_AUTHORITY));

        mvc.perform(get("/api/v1/auth/session").with(temporaryAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialTemporary").value(true))
                .andExpect(jsonPath("$.loginName").value("admin"));
        mvc.perform(get("/api/v1/auth/session").with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialTemporary").value(false));
    }

    @Test
    void permanentCredentialRetainsNormalRoleAccess() throws Exception {
        // Also proves the stub route is mapped, so the temporary-credential 404 above is a
        // deliberate authorization denial rather than a missing handler.
        mvc.perform(get("/api/v1/admin/accounts").with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isOk());
    }

    @SuppressWarnings("unchecked")
    private UserDetails loadUser(boolean temporary, Instant expiresAt) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        try {
            when(row.getString("login_name")).thenReturn("target");
            when(row.getString("role")).thenReturn("REPORTER");
            when(row.getString("status")).thenReturn("ENABLED");
            when(row.getString("password_hash")).thenReturn("{noop}hash");
            when(row.getBoolean("temporary")).thenReturn(temporary);
            when(row.getTimestamp("expires_at"))
                    .thenReturn(expiresAt == null ? null : Timestamp.from(expiresAt));
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
        when(jdbc.query(anyString(), any(RowMapper.class), eq("target"))).thenAnswer(invocation -> {
            RowMapper<UserDetails> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });
        return new DatabaseAccountDetailsService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC))
                .loadUserByUsername("target");
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ WebSecurityConfiguration.class, SessionController.class, StubEndpoints.class })
    static class TestConfiguration {
        @Bean
        UserDetailsService userDetailsService() {
            return mock(UserDetailsService.class);
        }
    }

    /** Minimal stand-ins so the route rules, not the real handlers, are under test. */
    @RestController
    static class StubEndpoints {
        @GetMapping("/api/v1/admin/accounts")
        String accounts() {
            return "{}";
        }

        @PostMapping("/api/v1/auth/password")
        org.springframework.http.ResponseEntity<Void> password() {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
    }
}
