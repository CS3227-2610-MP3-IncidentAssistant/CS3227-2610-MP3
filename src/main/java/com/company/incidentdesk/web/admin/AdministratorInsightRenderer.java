package com.company.incidentdesk.web.admin;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPeriodResolver.ResolvedPlan;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.ClarificationCode;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Metric;

/** Converts application-owned aggregate values into escaped browser DTO fields. */
final class AdministratorInsightRenderer {
    Response answer(AdministratorInsightPlan plan, ResolvedPlan periods, AdministratorInsightAnalytics.Result result) {
        List<Row> rows = result.values().stream().map(this::row).toList();
        String summary = rows.isEmpty() ? "No qualifying data was found." : summary(plan);
        return new Response("ANSWER", null, summary, plan.metric().orElseThrow().name(),
                periods.period().startInclusive(), periods.period().endExclusive(), result.snapshotAt(), rows,
                result.eligibleCount(), result.excludedCount(),
                "AI interpreted the question; application code calculated every value.",
                plan.effectiveGrouping().name());
    }

    Response clarification(ClarificationCode code, Instant at) {
        return new Response("NEEDS_CLARIFICATION", code.name(), clarificationText(code), null,
                null, null, at, List.of(), 0, 0, "No analytics query was run.", null);
    }

    private Row row(AdministratorInsightAnalytics.Value value) {
        return new Row(value.label(), value.numericValue(), display(value.metric(), value.numericValue()), value.sampleSize());
    }

    private static String summary(AdministratorInsightPlan plan) {
        return switch (plan.queryType().orElseThrow()) {
        case RANK_RESPONDERS, COMPARE_SELECTED_RESPONDERS -> "Responder operational indicator.";
        case RANK_CATEGORIES -> "Category ranking.";
        case CATEGORY_TREND -> "Category trend.";
        case PERIOD_COMPARISON -> "Adjacent period comparison.";
        case BACKLOG_SNAPSHOT -> "Current unassigned backlog snapshot.";
        case SLO_TARGET_COMPARISON -> "Observed metric compared with applicable SLO targets; values show observed minus target.";
        case OPERATIONAL_OVERVIEW -> "Operational overview.";
        };
    }

    private static String display(Metric metric, double value) {
        return switch (metric) {
        case AVERAGE_TIME_TO_CLAIM, AVERAGE_TIME_IN_PROGRESS, OLDEST_UNASSIGNED_AGE -> Math.round(value / 60000d) + " minutes";
        case REOPEN_RATE -> String.format(Locale.ROOT, "%.1f%%", value * 100d);
        default -> Long.toString(Math.round(value));
        };
    }

    private static String clarificationText(ClarificationCode code) {
        return switch (code) {
        case MISSING_PERIOD -> "Specify a reporting period.";
        case AMBIGUOUS_METRIC -> "Specify the operational metric to analyze.";
        case UNSUPPORTED_QUESTION -> "Ask about one supported operational statistic.";
        case CONFLICTING_PERIODS -> "Specify one primary period and, if needed, one adjacent comparison period.";
        };
    }

    record Row(String label, double numericValue, String displayValue, long sampleSize) { }
    record Response(String outcome, String clarificationCode, String summary, String metric,
            Instant periodStart, Instant periodEndExclusive, Instant snapshotAt, List<Row> rows,
            int eligibleCount, int excludedCount, String advisoryNotice, String grouping) {
        Response { rows = List.copyOf(rows); }
    }
}
