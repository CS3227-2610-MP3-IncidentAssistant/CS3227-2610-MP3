package com.company.incidentdesk.application.ai.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.*;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlanParser.PlanRejectedException;

class AdministratorInsightPlanParserTest {
    private final AdministratorInsightPlanParser parser = new AdministratorInsightPlanParser();

    @Test
    void parsesAClosedRankingPlanAndDerivesItsGrouping() {
        AdministratorInsightPlan plan = parser.parse("""
                {"outcome":"QUERY","queryType":"RANK_RESPONDERS",
                 "metric":"AVERAGE_TIME_IN_PROGRESS","period":"ROLLING_30_DAYS",
                 "direction":"DESCENDING","limit":5}
                """);

        assertEquals(QueryType.RANK_RESPONDERS, plan.queryType().orElseThrow());
        assertEquals(Metric.AVERAGE_TIME_IN_PROGRESS, plan.metric().orElseThrow());
        assertEquals(Direction.DESCENDING, plan.direction().orElseThrow());
        assertEquals(5, plan.limit().orElseThrow());
        assertEquals(Grouping.RESPONDER, plan.effectiveGrouping());
    }

    @Test
    void parsesOnlyApplicationOwnedClarificationCodes() {
        AdministratorInsightPlan plan = parser.parse(
                "{\"outcome\":\"NEEDS_CLARIFICATION\",\"clarificationCode\":\"MISSING_PERIOD\"}");

        assertEquals(ClarificationCode.MISSING_PERIOD, plan.clarificationCode().orElseThrow());
    }

    @Test
    void parsesAnExplicitInclusiveDateRange() {
        AdministratorInsightPlan plan = parser.parse("""
                {"outcome":"QUERY","queryType":"BACKLOG_SNAPSHOT",
                 "metric":"UNASSIGNED_BACKLOG_COUNT",
                 "period":{"fromDate":"2026-01-01","throughDate":"2026-01-31"}}
                """);

        Explicit period = assertInstanceOf(Explicit.class, plan.period().orElseThrow());
        assertEquals(LocalDate.parse("2026-01-01"), period.fromDate());
        assertEquals(LocalDate.parse("2026-01-31"), period.throughDate());
    }

    @ParameterizedTest
    @MethodSource("invalidPlans")
    void rejectsMalformedOpenOrSemanticallyInvalidPlans(String output) {
        assertThrows(PlanRejectedException.class, () -> parser.parse(output));
    }

    private static Stream<String> invalidPlans() {
        return Stream.of(
                "not json",
                "{}",
                "{\"outcome\":\"QUERY\",\"queryType\":\"runSql\",\"metric\":\"INCIDENT_COUNT\",\"period\":\"ROLLING_7_DAYS\"}",
                "{\"outcome\":\"QUERY\",\"queryType\":\"BACKLOG_SNAPSHOT\",\"metric\":\"UNASSIGNED_BACKLOG_COUNT\",\"period\":\"ROLLING_7_DAYS\",\"sql\":\"select 1\"}",
                "{\"outcome\":\"QUERY\",\"outcome\":\"NEEDS_CLARIFICATION\"}",
                "{\"outcome\":\"QUERY\",\"queryType\":\"RANK_RESPONDERS\",\"metric\":\"AVERAGE_TIME_TO_CLAIM\",\"period\":\"ROLLING_30_DAYS\",\"direction\":\"DESCENDING\",\"limit\":5}",
                "{\"outcome\":\"QUERY\",\"queryType\":\"RANK_CATEGORIES\",\"metric\":\"INCIDENT_COUNT\",\"period\":\"ROLLING_30_DAYS\",\"direction\":\"DESCENDING\",\"limit\":11}",
                "{\"outcome\":\"QUERY\",\"queryType\":\"OPERATIONAL_OVERVIEW\",\"metric\":\"INCIDENT_COUNT\",\"period\":\"ROLLING_30_DAYS\",\"direction\":\"ASCENDING\"}",
                "{\"outcome\":\"QUERY\",\"queryType\":\"BACKLOG_SNAPSHOT\",\"metric\":\"UNASSIGNED_BACKLOG_COUNT\",\"period\":{\"fromDate\":\"2026-01-01\",\"throughDate\":\"2026-01-31\",\"timezone\":\"UTC\"}}",
                "{\"outcome\":\"NEEDS_CLARIFICATION\",\"clarificationCode\":\"write your own message\"}",
                "{\"outcome\":\"NEEDS_CLARIFICATION\",\"clarificationCode\":\"MISSING_PERIOD\"} trailing prose");
    }
}
