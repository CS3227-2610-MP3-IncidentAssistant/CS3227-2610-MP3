package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.company.incidentdesk.infrastructure.soclaas.AiRequest;
import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;
import com.company.incidentdesk.infrastructure.soclaas.SoclaasException;
import com.company.incidentdesk.web.security.WebSecurityConfiguration;

class ResponderAiIncidentControllerTest {
    private final UUID incident = UUID.randomUUID();
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private SecuredAiGateway gateway;

    private String path() { return "/api/v1/responder/incidents/" + incident + "/ai/advice"; }

    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        gateway = context.getBean(SecuredAiGateway.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach void close() { context.close(); }

    @Test void requiresResponderRoleAndCsrfBeforeGateway() throws Exception {
        mvc.perform(post(path()).with(csrf())).andExpect(status().isUnauthorized());
        for (String role : new String[] {"REPORTER", "ADMINISTRATOR"}) {
            mvc.perform(post(path()).with(user("other").roles(role)).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(post(path()).with(user("responder").roles("RESPONDER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
    }

    @Test void sendsOnlyIncidentIdentifierAndReturnsAdvisoryText() throws Exception {
        when(gateway.assist(any())).thenReturn(new SecuredAiGateway.Suggestion("<script>literal</script>"));
        mvc.perform(post(path()).with(user("responder").roles("RESPONDER")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.advice").value("<script>literal</script>"));
        var capture = org.mockito.ArgumentCaptor.forClass(AiRequest.class);
        verify(gateway).assist(capture.capture());
        assertEquals(incident, ((AiRequest.ResponderIncident) capture.getValue()).incidentId());
    }

    @Test void rejectsAdditionalDataAndHidesDeniedIncidentAndProviderDetails() throws Exception {
        mvc.perform(post(path()).with(user("responder").roles("RESPONDER")).with(csrf())
                .contentType("application/json").content("{\"actor\":\"forged\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path() + "?model=forged").with(user("responder").roles("RESPONDER")).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gateway);
        var denied = assertThrows(SoclaasException.class, () -> new AiRequest.ResponderIncident(null));
        doThrow(denied).when(gateway).assist(any());
        mvc.perform(post(path()).with(user("responder").roles("RESPONDER")).with(csrf()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNAVAILABLE"));
        doThrow(new IllegalStateException("synthetic-sensitive-diagnostic")).when(gateway).assist(any());
        var result = mvc.perform(post(path()).with(user("responder").roles("RESPONDER")).with(csrf()))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("synthetic-sensitive"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc @EnableWebSecurity
    @Import({WebSecurityConfiguration.class, ResponderAiIncidentController.class})
    static class TestConfiguration {
        @Bean SecuredAiGateway gateway() { return mock(SecuredAiGateway.class); }
        @Bean UserDetailsService users() {
            return name -> User.withUsername(name).password("synthetic").roles("RESPONDER").build();
        }
    }
}
