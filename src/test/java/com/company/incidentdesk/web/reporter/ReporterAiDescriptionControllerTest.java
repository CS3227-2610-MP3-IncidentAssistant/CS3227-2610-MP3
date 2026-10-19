package com.company.incidentdesk.web.reporter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

import com.company.incidentdesk.infrastructure.soclaas.AiRequest;
import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;
import com.company.incidentdesk.web.security.WebSecurityConfiguration;

class ReporterAiDescriptionControllerTest {
    private static final String PATH = "/api/v1/reporter/ai/description";
    private static final String VALID = "{\"title\":\"Broken display\",\"category\":\"IT\",\"description\":\"Flickering screen\"}";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private SecuredAiGateway gateway;

    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        gateway = context.getBean(SecuredAiGateway.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
    @AfterEach void close() { context.close(); }

    @Test void requiresReporterAuthenticationAndCsrfBeforeGateway() throws Exception {
        mvc.perform(post(PATH).with(csrf()).contentType("application/json").content(VALID)).andExpect(status().isUnauthorized());
        for (String role : new String[] {"RESPONDER", "ADMINISTRATOR"}) {
            mvc.perform(post(PATH).with(user("other").roles(role)).with(csrf()).contentType("application/json").content(VALID))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post(PATH).with(user("reporter").roles("REPORTER")).contentType("application/json").content(VALID))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SECURITY_SESSION_EXPIRED"));
        verifyNoInteractions(gateway);
    }

    @Test void returnsOnlyDescriptionWithoutSavingAnything() throws Exception {
        when(gateway.assist(any())).thenReturn(new SecuredAiGateway.Suggestion("<script>synthetic()</script>"));
        mvc.perform(post(PATH).with(user("reporter").roles("REPORTER")).with(csrf()).contentType("application/json").content(VALID))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"description\":\"<script>synthetic()</script>\"}"));
        var captor = org.mockito.ArgumentCaptor.forClass(AiRequest.class);
        verify(gateway).assist(captor.capture());
        var draft = assertInstanceOf(AiRequest.ReporterDraft.class, captor.getValue());
        assertEquals("Broken display", draft.title());
        assertEquals("Flickering screen", draft.description());
        assertEquals("IT", draft.category().name());
    }

    @Test void rejectsUnknownForgedDuplicateMalformedAndOversizedFieldsBeforeGateway() throws Exception {
        for (String invalid : new String[] {
                VALID.replace("}", ",\"actor\":\"another-user\"}"),
                VALID.replace("}", ",\"role\":\"ADMINISTRATOR\"}"),
                VALID.replace("}", ",\"anonymous\":true}"),
                VALID.replace("}", ",\"model\":\"unapproved\"}"),
                VALID.replace("}", ",\"description\":\"duplicate\"}"),
                VALID.replace("\"IT\"", "\"UNSUPPORTED\""),
                VALID.replace("\"Flickering screen\"", "null"),
                VALID.replace("\"Broken display\"", "\" \""),
                VALID.replace("\"Broken display\"", "\"" + "x".repeat(201) + "\""),
                VALID.replace("\"Flickering screen\"", "\"" + "x".repeat(10001) + "\""),
                "[]", "{}", VALID + "{}", "{", " ".repeat(16385) }) {
            mvc.perform(post(PATH).with(user("reporter").roles("REPORTER")).with(csrf()).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AI_REJECTED"));
        }
        verifyNoInteractions(gateway);
    }

    @Test void rejectsUnsupportedContentType() throws Exception {
        mvc.perform(post(PATH).with(user("reporter").roles("REPORTER")).with(csrf()).contentType("text/plain").content(VALID))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(gateway);
    }

    @Test void unexpectedFailuresDoNotExposeInternalDiagnostics() throws Exception {
        when(gateway.assist(any())).thenThrow(new IllegalStateException("synthetic-sensitive-diagnostics"));
        var result = mvc.perform(post(PATH).with(user("reporter").roles("REPORTER")).with(csrf())
                .contentType("application/json").content(VALID)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("synthetic-sensitive"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc @EnableWebSecurity
    @Import({WebSecurityConfiguration.class, ReporterAiDescriptionController.class})
    static class TestConfiguration {
        @Bean SecuredAiGateway gateway() { return mock(SecuredAiGateway.class); }
        @Bean UserDetailsService users() { return name -> User.withUsername(name).password("synthetic").roles("REPORTER").build(); }
    }
}
