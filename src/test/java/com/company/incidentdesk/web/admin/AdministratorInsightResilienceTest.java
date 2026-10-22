package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Metric;
import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;
import com.company.incidentdesk.infrastructure.soclaas.SoclaasException;
import com.company.incidentdesk.infrastructure.soclaas.SoclaasFailures;

/**
 * AI-ADM-SAFE-05: every provider, database, and cancellation failure produces the specified safe
 * response, runs no analytics handler, and never carries provider or question content outward.
 */
class AdministratorInsightResilienceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T08:30:00Z");
    private static final String QUESTION = "What changed in the past 30 days?";

    @ParameterizedTest
    @CsvSource({
        "AI_TIMEOUT,504,AI_TIMEOUT",
        "AI_RATE_LIMITED,429,AI_RATE_LIMITED",
        "AI_BUDGET_EXHAUSTED,429,AI_RATE_LIMITED",
        "AI_UNAVAILABLE,503,AI_UNAVAILABLE",
        "AI_REJECTED,404,UNAVAILABLE",
        "AI_INVALID_RESPONSE,502,AI_INVALID_RESPONSE",
        "AI_OUTPUT_TOO_LARGE,502,AI_OUTPUT_TOO_LARGE"
    })
    void eachProviderFailureMapsToItsSafeResponseAndRunsNoAnalyticsHandler(
            String category, int expectedStatus, String expectedCode) {
        RecordingAnalytics analytics = new RecordingAnalytics();
        SecuredAiGateway gateway = mock(SecuredAiGateway.class);
        when(gateway.assist(any())).thenThrow(
                SoclaasFailures.of(SoclaasException.Category.valueOf(category)));

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.valueOf(expectedStatus), response.getStatusCode());
        assertEquals(expectedCode, body(response).get("code"));
        assertFalse(analytics.executed, "no analytics handler may run after a provider failure");
        assertSafeMessage(body(response).get("message"));
    }

    @Test
    void malformedProviderOutputIsRejectedWithoutRunningAnAnalyticsHandler() {
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, gatewayReturning("not json at all"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("AI_INVALID_RESPONSE", body(response).get("code"));
        assertFalse(analytics.executed);
    }

    @Test
    void unsupportedProviderPlanIsRejectedWithoutRunningAnAnalyticsHandler() {
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, gatewayReturning("""
                {"outcome":"QUERY","queryType":"RANK_RESPONDERS","metric":"UNASSIGNED_BACKLOG_COUNT",
                 "period":"ROLLING_30_DAYS","direction":"DESCENDING","limit":3}
                """));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("AI_INVALID_RESPONSE", body(response).get("code"));
        assertFalse(analytics.executed);
    }

    @Test
    void circuitOpenStateIsReportedAsTemporarilyUnavailableWithStatisticsStillOffered() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        SecuredAiGateway gateway = mock(SecuredAiGateway.class);
        when(gateway.assist(any())).thenThrow(
                SoclaasFailures.of(SoclaasException.Category.AI_UNAVAILABLE));

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(body(response).get("message").contains("Operational statistics remain available"));
    }

    @Test
    void databaseFailureDuringAnalyticsReturnsTheGenericUnavailableResponse() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.failExecution = true;

        var response = answer(analytics, gatewayReturning(backlogPlan()));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("UNAVAILABLE", body(response).get("code"));
    }

    @Test
    void databaseFailureBeforeProviderDisclosureReturnsTheGenericUnavailableResponse() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.failLoginNames = true;
        SecuredAiGateway gateway = mock(SecuredAiGateway.class);

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("UNAVAILABLE", body(response).get("code"));
        assertFalse(analytics.executed);
    }

    @Test
    void queryTimeoutIsReportedAsUnavailableAndNeverRetriesTheProvider() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.failExecution = true;
        SecuredAiGateway gateway = gatewayReturning(backlogPlan());

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(1, analytics.loginNameReads, "one admitted request reads trusted state once");
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).assist(any());
    }

    @Test
    void cancellationDuringAnalyticsDoesNotLeakAnInternalFailureToTheBrowser() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.cancelExecution = true;

        var response = answer(analytics, gatewayReturning(backlogPlan()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("AI_UNAVAILABLE", body(response).get("code"));
    }

    @Test
    void anEmptyAnalyticsResultIsASuccessfulNoQualifyingDataAnswerRatherThanAFailure() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.values = List.of();

        var response = answer(analytics, gatewayReturning(backlogPlan()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var answer = assertInstanceOf(AdministratorInsightRenderer.Response.class, response.getBody());
        assertEquals("ANSWER", answer.outcome());
        assertTrue(answer.rows().isEmpty());
        assertEquals("No qualifying data was found.", answer.summary());
    }

    /** The safe message must describe the outcome without echoing the question or provider text. */
    private static void assertSafeMessage(String message) {
        assertNotNull(message);
        assertFalse(message.contains(QUESTION), "the safe error must not echo the question");
        assertFalse(message.toLowerCase(java.util.Locale.ROOT).contains("provider"),
                "the safe error must not describe provider internals");
    }

    private static String backlogPlan() {
        return """
                {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT","metric":"UNASSIGNED_BACKLOG_COUNT",
                 "period":"ROLLING_30_DAYS"}
                """;
    }

    private static SecuredAiGateway gatewayReturning(String providerText) {
        SecuredAiGateway gateway = mock(SecuredAiGateway.class);
        when(gateway.assist(any())).thenReturn(new SecuredAiGateway.Suggestion(providerText));
        return gateway;
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<?> answer(RecordingAnalytics analytics, SecuredAiGateway gateway) {
        ObjectProvider<SecuredAiGateway> gateways = mock(ObjectProvider.class);
        when(gateways.getIfAvailable()).thenReturn(gateway);
        AdministratorInsightService service = new AdministratorInsightService(analytics, gateways,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(("{\"question\":\"" + QUESTION + "\"}").getBytes(StandardCharsets.UTF_8));
        Principal principal = () -> "admin";
        return new AdministratorInsightController(service).answer(principal, request);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> body(ResponseEntity<?> response) {
        return (Map<String, String>) response.getBody();
    }

    private static final class RecordingAnalytics implements AdministratorInsightAnalytics {
        boolean executed;
        boolean failExecution;
        boolean failLoginNames;
        boolean cancelExecution;
        int loginNameReads;
        List<Value> values = List.of(new Value("IT unassigned", Metric.UNASSIGNED_BACKLOG_COUNT, 2d, 2));

        @Override public List<String> knownLoginNames(String actor) {
            loginNameReads++;
            if (failLoginNames) throw new JdbcAdministratorInsightAnalytics.AnalyticsUnavailableException();
            return List.of();
        }

        @Override public Result execute(String actor, Query query) {
            if (cancelExecution) throw new CancellationException("cancelled");
            if (failExecution) throw new JdbcAdministratorInsightAnalytics.AnalyticsUnavailableException();
            executed = true;
            return new Result(values, query.periods().requestInstant());
        }
    }
}
