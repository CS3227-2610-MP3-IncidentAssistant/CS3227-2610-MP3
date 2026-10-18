package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.company.incidentdesk.web.security.ReporterAccountRegistrationService.RegistrationOutcome;

@ExtendWith(OutputCaptureExtension.class)
class ReporterAccountRegistrationHttpSecurityTest {
    private static final String PASSWORD = "plain-text-registration-secret";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private RecordingStore store;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        store = context.getBean(RecordingStore.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void rejectsRegistrationWithoutCsrfBeforePersistence() throws Exception {
        mvc.perform(post("/api/v1/auth/reporter/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Your security session expired. Try again."));

        assertNull(store.saved);
    }

    @Test
    void acceptsCsrfProtectedRegistrationWithoutDisclosingPlaintext(CapturedOutput output) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/reporter/register")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals("encoded-registration-password", store.saved.passwordHash());
        assertFalse(response.contains(PASSWORD));
        assertFalse(output.getAll().contains(PASSWORD));
    }

    private static String validRequest() {
        return """
                {
                  "loginName": "csrf-reporter",
                  "password": "%s",
                  "passwordConfirmation": "%s"
                }
                """.formatted(PASSWORD, PASSWORD);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(WebSecurityConfiguration.class)
    static class TestConfiguration {
        @Bean
        RecordingStore registrationStore() {
            return new RecordingStore();
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new PasswordEncoder() {
                @Override
                public String encode(CharSequence rawPassword) {
                    return "encoded-registration-password";
                }

                @Override
                public boolean matches(CharSequence rawPassword, String encodedPassword) {
                    return false;
                }
            };
        }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> {
                throw new IllegalArgumentException("No test users");
            };
        }

        @Bean
        ReporterAccountRegistrationService registrationService(
                RecordingStore store,
                PasswordEncoder encoder) {
            return new ReporterAccountRegistrationService(store, encoder);
        }

        @Bean
        ReporterAccountRegistrationController registrationController(
                ReporterAccountRegistrationService service) {
            return new ReporterAccountRegistrationController(service);
        }
    }

    private static final class RecordingStore implements ReporterAccountRegistrationStore {
        private ReporterAccountRegistration saved;

        @Override
        public RegistrationOutcome register(ReporterAccountRegistration registration) {
            saved = registration;
            return RegistrationOutcome.CREATED;
        }
    }
}
