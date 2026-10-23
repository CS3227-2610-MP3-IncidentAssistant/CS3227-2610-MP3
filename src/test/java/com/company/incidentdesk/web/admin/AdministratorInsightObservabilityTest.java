package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Metric;
import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * AI-ADM-SAFE-04: an admitted insight request emits no question, plan, result value, display name,
 * selection, prompt, or provider body into logs or the browser-visible response envelope.
 */
class AdministratorInsightObservabilityTest {
    private static final Instant NOW = Instant.parse("2026-10-23T08:30:00Z");
    private static final String QUESTION = "Which category had the largest backlog in the past 30 days?";
    private static final String RESPONDER_DISPLAY_NAME = "confidential-responder-name";
    private static final String PROVIDER_BODY = """
            {"outcome":"QUERY","queryType":"RANK_CATEGORIES","metric":"UNASSIGNED_BACKLOG_COUNT",
             "period":"ROLLING_30_DAYS","direction":"DESCENDING","limit":3}
            """;

    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        appender = new ListAppender<>();
        appender.start();
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        originalLevel = rootLogger.getLevel();
        // Capture at DEBUG for this class only, then restore, so no other suite sees a louder root
        // logger. A security suite asserting on captured output must not inherit this level.
        rootLogger.setLevel(Level.DEBUG);
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        rootLogger.detachAppender(appender);
        rootLogger.setLevel(originalLevel);
        appender.stop();
    }

    @Test
    void anAdmittedRequestWritesNoQuestionPlanOrResultContentToLogs() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.resultLabel = RESPONDER_DISPLAY_NAME;

        var response = answer(analytics, PROVIDER_BODY, List.of());

        assertEquals(200, response.getStatusCode().value());
        String captured = capturedLogText();
        assertFalse(captured.contains(QUESTION.toLowerCase(Locale.ROOT)), "the question must not be logged");
        assertFalse(captured.contains("rank_categories"), "the plan must not be logged");
        assertFalse(captured.contains("unassigned_backlog_count"), "the plan metric must not be logged");
        assertFalse(captured.contains(RESPONDER_DISPLAY_NAME), "a display name must not be logged");
        assertFalse(captured.contains("confidential"), "no result label may be logged");
    }

    @Test
    void aRejectedQuestionWritesNoQuestionContentToLogs() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.logins = List.of("named-responder");

        var response = answer(analytics, PROVIDER_BODY, List.of(),
                "Compare named-responder over the past 30 days");

        assertEquals(400, response.getStatusCode().value());
        String captured = capturedLogText();
        assertFalse(captured.contains("named-responder"), "a rejected question must not be logged");
    }

    @Test
    void aFailedRequestWritesNoProviderBodyToLogs() {
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, "{\"outcome\":\"QUERY\",\"queryType\":\"NOT_A_QUERY_TYPE\"}", List.of());

        assertEquals(502, response.getStatusCode().value());
        assertFalse(capturedLogText().contains("not_a_query_type"),
                "the provider body must not reach the logs");
    }

    @Test
    void theRequestObjectHandedToTheGatewayWithholdsItsContentFromAnyStringConversion() {
        var request = new com.company.incidentdesk.infrastructure.soclaas.AiRequest.AdministratorInsight(
                QUESTION, NOW);

        assertEquals("Administrator insight request [content withheld]", request.toString());
        assertFalse(request.toString().contains(QUESTION));
    }

    @Test
    void theBrowserResponseCarriesOnlyBoundedApplicationOwnedFieldsAndNoProviderText() {
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, PROVIDER_BODY, List.of());
        var answer = (AdministratorInsightRenderer.Response) response.getBody();

        assertEquals("ANSWER", answer.outcome());
        assertEquals("UNASSIGNED_BACKLOG_COUNT", answer.metric());
        assertEquals("AI interpreted the question; application code calculated every value.",
                answer.advisoryNotice());
        assertFalse(answer.summary().contains(QUESTION), "the answer must not echo the question");
        assertTrue(Set.of("COMPANY", "CATEGORY", "RESPONDER").contains(answer.grouping()),
                "grouping must be a bounded enum value");
        assertTrue(answer.rows().stream().allMatch(row -> row.label().equals("IT")),
                "rows carry only application-resolved labels");
    }

    private String capturedLogText() {
        return appender.list.stream()
                .map(event -> event.getFormattedMessage() + " " + String.valueOf(event.getThrowableProxy()))
                .collect(Collectors.joining("\n"))
                .toLowerCase(Locale.ROOT);
    }

    private static ResponseEntity<?> answer(RecordingAnalytics analytics, String providerBody,
            List<UUID> responderIds) {
        return answer(analytics, providerBody, responderIds, QUESTION);
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<?> answer(RecordingAnalytics analytics, String providerBody,
            List<UUID> responderIds, String question) {
        SecuredAiGateway gateway = mock(SecuredAiGateway.class);
        when(gateway.assist(any())).thenReturn(new SecuredAiGateway.Suggestion(providerBody));
        ObjectProvider<SecuredAiGateway> gateways = mock(ObjectProvider.class);
        when(gateways.getIfAvailable()).thenReturn(gateway);
        AdministratorInsightService service = new AdministratorInsightService(analytics, gateways,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(("{\"question\":\"" + question + "\"}").getBytes(StandardCharsets.UTF_8));
        Principal principal = () -> "admin";
        return new AdministratorInsightController(service).answer(principal, request);
    }

    private static final class RecordingAnalytics implements AdministratorInsightAnalytics {
        List<String> logins = List.of();
        String resultLabel = "IT";

        @Override public List<String> knownLoginNames(String actor) { return logins; }

        @Override public Result execute(String actor, Query query) {
            return new Result(List.of(new Value(resultLabel, Metric.UNASSIGNED_BACKLOG_COUNT, 4d, 4)),
                    query.periods().requestInstant());
        }
    }
}
