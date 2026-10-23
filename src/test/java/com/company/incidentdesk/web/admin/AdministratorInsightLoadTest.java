package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Metric;
import com.company.incidentdesk.infrastructure.soclaas.AiRequest;
import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;

/**
 * AI-ADM-SAFE-06: one provider call per user action, bounded query and result sizes, no response
 * chaining, and no service-held lock that a slow provider could turn into an outage.
 *
 * <p>Continued availability of the normal authenticated incident endpoints while AI requests are
 * slow or exhausted is proved end to end over HTTP by {@code AiGatewayWebAvailabilityPostgresTest};
 * this class covers the per-action call budget and the bounds that hold whatever the untrusted
 * provider returns.
 */
class AdministratorInsightLoadTest {
    private static final Instant NOW = Instant.parse("2026-10-23T08:30:00Z");
    private static final String QUESTION = "What changed in the past 30 days?";

    @Test
    void oneUserActionIssuesExactlyOneProviderCall() {
        CountingGateway gateway = new CountingGateway(rankPlan(10));
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, gateway.calls.get(), "one administrator action must cost exactly one provider call");
        assertEquals(1, analytics.executions(), "one action runs exactly one analytics query");
    }

    @Test
    void aClarificationOutcomeIssuesNoSecondProviderCallAndRunsNoAnalyticsQuery() {
        CountingGateway gateway = new CountingGateway(
                "{\"outcome\":\"NEEDS_CLARIFICATION\",\"clarificationCode\":\"MISSING_PERIOD\"}");
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, gateway.calls.get(), "a clarification must not be re-asked of the provider");
        assertEquals(0, analytics.executions());
    }

    @Test
    void aRejectedPlanIssuesNoRetryProviderCall() {
        CountingGateway gateway = new CountingGateway("not a plan at all");
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals(1, gateway.calls.get(), "a rejected plan must fail safe rather than retry the provider");
        assertEquals(0, analytics.executions());
    }

    /**
     * Response chaining would mean feeding an analytics result back to the provider for a second
     * opinion. The provider must therefore never be called again once analytics has run, and the
     * only request it ever receives is the administrator's own question.
     */
    @Test
    void theAnalyticsResultIsNeverFedBackToTheProvider() {
        CountingGateway gateway = new CountingGateway(rankPlan(10));
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.values = manyValues(10);

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, gateway.calls.get());
        assertEquals(1, analytics.executions());
        assertTrue(gateway.requests.stream().allMatch(request -> request instanceof AiRequest.AdministratorInsight),
                "only the administrator insight question may ever be sent");
    }

    @Test
    void theProviderNeverReceivesAnalyticsLabelsOrValues() {
        CountingGateway gateway = new CountingGateway(rankPlan(3));
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.values = manyValues(4);

        answer(analytics, gateway);

        var sent = assertInstanceOf(AiRequest.AdministratorInsight.class, gateway.requests.peek());
        assertEquals(QUESTION, sent.question());
        assertFalse(sent.question().contains("responder-"), "no analytics label may reach the provider");
    }

    @Test
    void aProviderLimitAboveTheTenRowCeilingIsRejectedBeforeAnalyticsRuns() {
        CountingGateway gateway = new CountingGateway(rankPlan(11));
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("AI_INVALID_RESPONSE", body(response).get("code"));
        assertEquals(0, analytics.executions(), "an out-of-bounds limit must not reach the database");
    }

    @Test
    void anOversizedProviderPlanIsRejectedBeforeAnalyticsRuns() {
        CountingGateway gateway = new CountingGateway(
                "{\"outcome\":\"QUERY\",\"queryType\":\"" + "X".repeat(5000) + "\"}");
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, gateway);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals(0, analytics.executions());
    }

    @Test
    void theRenderedResponseStaysWithinTheTenRowCeiling() {
        CountingGateway gateway = new CountingGateway(backlogPlan());
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.values = manyValues(10);

        var response = answer(analytics, gateway);

        var answer = assertInstanceOf(AdministratorInsightRenderer.Response.class, response.getBody());
        assertTrue(answer.rows().size() <= 10, "the browser response must stay bounded");
    }

    @Test
    void anOversizedRequestBodyIsRejectedWithoutAnyProviderCall() {
        CountingGateway gateway = new CountingGateway(backlogPlan());
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, gateway, "q".repeat(9000));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(0, gateway.calls.get(), "a body over the boundary limit must never reach the provider");
        assertEquals(0, analytics.executions());
    }

    @Test
    void anOverlongQuestionIsRejectedWithoutAnyProviderCall() {
        CountingGateway gateway = new CountingGateway(backlogPlan());
        RecordingAnalytics analytics = new RecordingAnalytics();

        var response = answer(analytics, gateway, "q".repeat(501));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(0, gateway.calls.get());
        assertEquals(0, analytics.executions());
    }

    @Test
    void concurrentAdministratorActionsEachIssueExactlyOneProviderCallAndOneQuery() throws Exception {
        int callers = 16;
        CountingGateway gateway = new CountingGateway(rankPlan(5));
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.values = manyValues(5);
        var controller = controller(analytics, gateway);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(callers)) {
            List<Future<ResponseEntity<?>>> results = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                results.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return controller.answer(principal(), jsonRequest(QUESTION));
                }));
            }
            start.countDown();
            for (var result : results) {
                var response = result.get(10, TimeUnit.SECONDS);
                assertEquals(HttpStatus.OK, response.getStatusCode());
                var answer = assertInstanceOf(AdministratorInsightRenderer.Response.class, response.getBody());
                assertEquals("ANSWER", answer.outcome());
                assertTrue(answer.rows().size() <= 10);
            }
        }

        assertEquals(callers, gateway.calls.get(), "no concurrent action may issue an extra provider call");
        assertEquals(callers, analytics.executions(), "each concurrent action runs exactly one analytics query");
    }

    @Test
    void aSlowProviderDoesNotSerializeConcurrentAdministratorActions() throws Exception {
        int callers = 8;
        CountingGateway gateway = new CountingGateway(rankPlan(5));
        gateway.arrival = new CountDownLatch(callers);
        gateway.release = new CountDownLatch(1);
        RecordingAnalytics analytics = new RecordingAnalytics();
        analytics.values = manyValues(5);
        var controller = controller(analytics, gateway);

        try (var pool = Executors.newFixedThreadPool(callers)) {
            List<Future<ResponseEntity<?>>> results = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                results.add(pool.submit(() -> controller.answer(principal(), jsonRequest(QUESTION))));
            }
            // Every caller must reach the provider while the others are still in flight. If the
            // service held a lock across the provider call this would time out instead.
            assertTrue(gateway.arrival.await(10, TimeUnit.SECONDS),
                    "a slow provider call must not block other administrator actions in the service");
            gateway.release.countDown();
            for (var result : results) {
                assertEquals(HttpStatus.OK, result.get(10, TimeUnit.SECONDS).getStatusCode());
            }
        }

        assertEquals(callers, gateway.calls.get());
        assertEquals(callers, analytics.executions());
    }

    private static List<AdministratorInsightAnalytics.Value> manyValues(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new AdministratorInsightAnalytics.Value(
                        "responder-" + index, Metric.AVERAGE_TIME_IN_PROGRESS, 60_000d * (index + 1), index + 1))
                .toList();
    }

    private static String rankPlan(int limit) {
        return """
                {"outcome":"QUERY","queryType":"RANK_RESPONDERS","metric":"AVERAGE_TIME_IN_PROGRESS",
                 "period":"ROLLING_30_DAYS","direction":"DESCENDING","limit":%d}
                """.formatted(limit);
    }

    private static String backlogPlan() {
        return """
                {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT","metric":"UNASSIGNED_BACKLOG_COUNT",
                 "period":"ROLLING_30_DAYS"}
                """;
    }

    private static Principal principal() {
        return () -> "admin";
    }

    private static MockHttpServletRequest jsonRequest(String question) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(("{\"question\":\"" + question + "\"}").getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static ResponseEntity<?> answer(RecordingAnalytics analytics, CountingGateway gateway) {
        return answer(analytics, gateway, QUESTION);
    }

    private static ResponseEntity<?> answer(RecordingAnalytics analytics, CountingGateway gateway, String question) {
        return controller(analytics, gateway).answer(principal(), jsonRequest(question));
    }

    @SuppressWarnings("unchecked")
    private static AdministratorInsightController controller(RecordingAnalytics analytics, CountingGateway gateway) {
        // Build the provider mock first: stubbing one mock inside another's open when(...) call
        // leaves Mockito with unfinished stubbing.
        SecuredAiGateway stubbed = gateway.asGateway();
        ObjectProvider<SecuredAiGateway> gateways = mock(ObjectProvider.class);
        when(gateways.getIfAvailable()).thenReturn(stubbed);
        return new AdministratorInsightController(new AdministratorInsightService(analytics, gateways,
                Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> body(ResponseEntity<?> response) {
        return (Map<String, String>) response.getBody();
    }

    /** Counts provider calls and records every request, so response chaining cannot go unnoticed. */
    private static final class CountingGateway {
        final AtomicInteger calls = new AtomicInteger();
        final ConcurrentLinkedQueue<AiRequest> requests = new ConcurrentLinkedQueue<>();
        private final String response;
        volatile CountDownLatch arrival;
        volatile CountDownLatch release;

        CountingGateway(String response) {
            this.response = response;
        }

        SecuredAiGateway asGateway() {
            SecuredAiGateway gateway = mock(SecuredAiGateway.class);
            when(gateway.assist(any())).thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                calls.incrementAndGet();
                CountDownLatch arrived = arrival;
                if (arrived != null) {
                    arrived.countDown();
                    CountDownLatch waiting = release;
                    if (waiting != null) {
                        assertTrue(waiting.await(10, TimeUnit.SECONDS), "provider release timed out");
                    }
                }
                return new SecuredAiGateway.Suggestion(response);
            });
            return gateway;
        }
    }

    private static final class RecordingAnalytics implements AdministratorInsightAnalytics {
        private final AtomicInteger executionCount = new AtomicInteger();
        volatile List<Value> values = List.of(
                new Value("IT unassigned", Metric.UNASSIGNED_BACKLOG_COUNT, 2d, 2));

        int executions() {
            return executionCount.get();
        }

        @Override public List<String> knownLoginNames(String actor) {
            return List.of();
        }

        @Override public Result execute(String actor, Query query) {
            executionCount.incrementAndGet();
            return new Result(values, query.periods().requestInstant());
        }
    }
}
