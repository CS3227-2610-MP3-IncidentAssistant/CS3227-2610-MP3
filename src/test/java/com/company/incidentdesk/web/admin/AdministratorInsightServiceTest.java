package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Metric;
import com.company.incidentdesk.infrastructure.soclaas.AiRequest;
import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;

class AdministratorInsightServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T08:30:00Z");

    @Test
    void rendersOnlyApplicationOwnedAnalyticsValues() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        AdministratorInsightService service = service(analytics, """
                {"outcome":"QUERY","queryType":"RANK_RESPONDERS","metric":"AVERAGE_TIME_IN_PROGRESS",
                 "period":"ROLLING_30_DAYS","direction":"DESCENDING","limit":3}
                """);

        var answer = service.answer("admin", new AdministratorInsightService.Request(
                "Who was slowest during the past 30 days?", List.of("IT"), List.of()));

        assertEquals("ANSWER", answer.outcome());
        assertEquals("application-name", answer.rows().getFirst().label());
        assertEquals("2 minutes", answer.rows().getFirst().displayValue());
        assertEquals(NOW.minusSeconds(30L * 86400), analytics.query.periods().period().startInclusive());
        assertEquals(NOW, analytics.query.periods().period().endExclusive());
        assertEquals("AVERAGE_TIME_IN_PROGRESS", answer.metric());
        assertEquals("DESCENDING", answer.rankingDirection());
        assertEquals(3, answer.limit());
        assertEquals("RESPONDER", answer.grouping());
        assertEquals(List.of("IT"), answer.categoryFilters());
        assertEquals(List.of(), answer.responderFilters());
    }

    @Test
    void rendersUnavailableValueForSelectedResponderWithoutQualifyingData() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.values = List.of(new AdministratorInsightAnalytics.Value(
                "responder-without-data", Metric.AVERAGE_TIME_IN_PROGRESS, null, 0));
        AdministratorInsightService service = service(analytics, """
                {"outcome":"QUERY","queryType":"COMPARE_SELECTED_RESPONDERS",
                 "metric":"AVERAGE_TIME_IN_PROGRESS","period":"ROLLING_30_DAYS"}
                """);

        var answer = service.answer("admin", new AdministratorInsightService.Request(
                "Compare the selected responders during the past 30 days", List.of(),
                List.of(UUID.randomUUID(), UUID.randomUUID())));

        assertEquals(1, answer.rows().size());
        assertEquals("Not available", answer.rows().getFirst().displayValue());
        assertNull(answer.rows().getFirst().numericValue());
        assertEquals(0, answer.rows().getFirst().sampleSize());
        assertNull(answer.rankingDirection());
        assertNull(answer.limit());
        assertEquals(List.of("responder-without-data"), answer.responderFilters());
    }

    @Test
    void clarificationRunsNoAnalyticsHandler() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        AdministratorInsightService service = service(analytics,
                "{\"outcome\":\"NEEDS_CLARIFICATION\",\"clarificationCode\":\"MISSING_PERIOD\"}");

        var answer = service.answer("admin", new AdministratorInsightService.Request(
                "Who had the longest average time?", List.of(), List.of()));

        assertEquals("NEEDS_CLARIFICATION", answer.outcome());
        assertEquals("MISSING_PERIOD", answer.clarificationCode());
        assertNull(analytics.query);
    }

    @Test
    void rejectsKnownLoginBeforeProviderDisclosure() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.logins = List.of("named-responder");
        SecuredAiGateway gateway = mock(SecuredAiGateway.class);
        AdministratorInsightService service = service(analytics, gateway);

        assertThrows(RuntimeException.class, () -> service.answer("admin",
                new AdministratorInsightService.Request("Compare named-responder this month", List.of(), List.of())));
        verify(gateway, never()).assist(any());
    }

    @Test
    void selectedResponderComparisonRequiresTwoThroughFiveStructuredSelections() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        AdministratorInsightService service = service(analytics, """
                {"outcome":"QUERY","queryType":"COMPARE_SELECTED_RESPONDERS","metric":"REOPEN_RATE",
                 "period":"THIS_UTC_MONTH"}
                """);

        assertThrows(AdministratorInsightService.InvalidInsightRequestException.class,
                () -> service.answer("admin", new AdministratorInsightService.Request(
                        "Compare the selected responders this month", List.of(), List.of(UUID.randomUUID()))));
        assertNull(analytics.query);
    }

    @Test
    void reportsAiUnavailableWhenNoGatewayIsConfigured() {
        RecordingAnalytics analytics = new RecordingAnalytics();
        AdministratorInsightService service = service(analytics, (SecuredAiGateway) null);

        assertThrows(AdministratorInsightService.AiUnavailableException.class,
                () -> service.answer("admin", new AdministratorInsightService.Request(
                        "What changed this month?", List.of(), List.of())));
    }

    private static AdministratorInsightService service(RecordingAnalytics analytics, String providerJson) {
        SecuredAiGateway gateway = mock(SecuredAiGateway.class);
        when(gateway.assist(any(AiRequest.class))).thenReturn(new SecuredAiGateway.Suggestion(providerJson));
        return service(analytics, gateway);
    }

    @SuppressWarnings("unchecked")
    private static AdministratorInsightService service(RecordingAnalytics analytics, SecuredAiGateway gateway) {
        ObjectProvider<SecuredAiGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        return new AdministratorInsightService(analytics, provider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class RecordingAnalytics implements AdministratorInsightAnalytics {
        Query query;
        List<String> logins = List.of();
        List<Value> values = List.of(new Value(
                "application-name", Metric.AVERAGE_TIME_IN_PROGRESS, 120_000d, 5));

        @Override public List<String> knownLoginNames(String actor) { return logins; }
        @Override public Result execute(String actor, Query next) {
            query = next;
            return new Result(values, next.periods().requestInstant());
        }
    }
}
