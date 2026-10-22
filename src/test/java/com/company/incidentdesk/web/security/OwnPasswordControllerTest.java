package com.company.incidentdesk.web.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Own-password replacement is the only workflow a temporary credential may reach. */
class OwnPasswordControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private OwnPasswordService passwords;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        passwords = context.getBean(OwnPasswordService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void requiresAuthenticationAndCsrfBeforeAnyCredentialChange() throws Exception {
        String body = "{\"currentPassword\":\"old-secret-1\",\"newPassword\":\"new-secret-value-1\"}";

        mvc.perform(post("/api/v1/auth/password").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/password").with(user("reporter").roles("REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));

        verifyNoInteractions(passwords);
    }

    @Test
    void changesPasswordForTheTrustedPrincipalOnly() throws Exception {
        when(passwords.changeOwnPassword("reporter", "old-secret-1", "new-secret-value-1"))
                .thenReturn(OwnPasswordService.Outcome.CHANGED);

        mvc.perform(post("/api/v1/auth/password").with(user("reporter").roles("REPORTER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-secret-1\",\"newPassword\":\"new-secret-value-1\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(passwords).changeOwnPassword("reporter", "old-secret-1", "new-secret-value-1");
    }

    @Test
    void successfulReplacementRemovesTheTemporaryAuthorityFromTheActiveSession() {
        when(passwords.changeOwnPassword("reporter", "old-secret-1", "new-secret-value-1"))
                .thenReturn(OwnPasswordService.Outcome.CHANGED);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "reporter", null, List.of(
                        new SimpleGrantedAuthority("ROLE_REPORTER"),
                        new SimpleGrantedAuthority(DatabaseAccountDetailsService.TEMPORARY_CREDENTIAL_AUTHORITY)));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var response = new OwnPasswordController(passwords).changePassword(
                authentication,
                new OwnPasswordController.ChangeBody("old-secret-1", "new-secret-value-1"));

        assertFalse(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> DatabaseAccountDetailsService.TEMPORARY_CREDENTIAL_AUTHORITY
                        .equals(authority.getAuthority())));
        org.junit.jupiter.api.Assertions.assertEquals(204, response.getStatusCode().value());
    }

    @Test
    void rejectsMissingAndTooShortValuesBeforeTouchingPersistence() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"currentPassword\":\"\",\"newPassword\":\"new-secret-value-1\"}",
                "{\"currentPassword\":\"old-secret-1\"}",
                "{\"currentPassword\":\"old-secret-1\",\"newPassword\":\"short\"}")) {
            mvc.perform(post("/api/v1/auth/password").with(user("reporter").roles("REPORTER")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        verifyNoInteractions(passwords);
    }

    @Test
    void mapsWrongCurrentPasswordAndFailuresWithoutLeakingDetail() throws Exception {
        when(passwords.changeOwnPassword("reporter", "wrong-secret-1", "new-secret-value-1"))
                .thenReturn(OwnPasswordService.Outcome.REJECTED);
        when(passwords.changeOwnPassword("reporter", "old-secret-1", "new-secret-value-1"))
                .thenReturn(OwnPasswordService.Outcome.PERSISTENCE_FAILURE);

        mvc.perform(post("/api/v1/auth/password").with(user("reporter").roles("REPORTER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-secret-1\",\"newPassword\":\"new-secret-value-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        mvc.perform(post("/api/v1/auth/password").with(user("reporter").roles("REPORTER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-secret-1\",\"newPassword\":\"new-secret-value-1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERSISTENCE_FAILURE"));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(WebSecurityConfiguration.class)
    static class TestConfiguration {
        @Bean
        OwnPasswordService ownPasswordService() {
            return mock(OwnPasswordService.class);
        }

        // Registered directly because the production bean is gated on a DataSource.
        @Bean
        OwnPasswordController ownPasswordController(OwnPasswordService passwords) {
            return new OwnPasswordController(passwords);
        }

        @Bean
        UserDetailsService userDetailsService() {
            return mock(UserDetailsService.class);
        }
    }
}
