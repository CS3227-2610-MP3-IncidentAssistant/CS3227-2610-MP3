package com.company.incidentdesk.application.ai.insights;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;

/** Closed, immutable provider plan. It contains no SQL, names, identifiers, or free-form output. */
public record AdministratorInsightPlan(
        Outcome outcome,
        Optional<QueryType> queryType,
        Optional<Metric> metric,
        Optional<Period> period,
        Optional<Period> comparisonPeriod,
        Optional<Direction> direction,
        Optional<Integer> limit,
        Optional<Grouping> grouping,
        Optional<ClarificationCode> clarificationCode) {

    private static final EnumSet<Metric> RESPONDER_METRICS = EnumSet.of(
            Metric.RESOLVED_CYCLE_COUNT, Metric.AVERAGE_TIME_IN_PROGRESS, Metric.REOPEN_RATE);
    private static final EnumSet<Metric> CATEGORY_METRICS = EnumSet.of(
            Metric.INCIDENT_COUNT, Metric.AVERAGE_TIME_TO_CLAIM, Metric.AVERAGE_TIME_IN_PROGRESS,
            Metric.REOPEN_RATE, Metric.REOPEN_EVENT_COUNT, Metric.UNASSIGNED_BACKLOG_COUNT,
            Metric.OLDEST_UNASSIGNED_AGE);
    private static final EnumSet<Metric> COMPANY_OR_CATEGORY_METRICS = EnumSet.copyOf(CATEGORY_METRICS);
    private static final EnumSet<Metric> SLO_METRICS = EnumSet.of(
            Metric.AVERAGE_TIME_TO_CLAIM, Metric.AVERAGE_TIME_IN_PROGRESS, Metric.REOPEN_RATE);

    public AdministratorInsightPlan {
        Objects.requireNonNull(outcome, "outcome");
        queryType = requiredOptional(queryType);
        metric = requiredOptional(metric);
        period = requiredOptional(period);
        comparisonPeriod = requiredOptional(comparisonPeriod);
        direction = requiredOptional(direction);
        limit = requiredOptional(limit);
        grouping = requiredOptional(grouping);
        clarificationCode = requiredOptional(clarificationCode);
        if (outcome == Outcome.NEEDS_CLARIFICATION) {
            require(clarificationCode.isPresent() && queryType.isEmpty() && metric.isEmpty() && period.isEmpty()
                    && comparisonPeriod.isEmpty() && direction.isEmpty() && limit.isEmpty() && grouping.isEmpty());
        } else {
            require(clarificationCode.isEmpty() && queryType.isPresent() && metric.isPresent() && period.isPresent());
            validateQuery(queryType.orElseThrow(), metric.orElseThrow(), comparisonPeriod, direction, limit, grouping);
        }
    }

    private static void validateQuery(QueryType type, Metric metric, Optional<Period> comparison,
            Optional<Direction> direction, Optional<Integer> limit, Optional<Grouping> grouping) {
        EnumSet<Metric> allowed = switch (type) {
        case RANK_RESPONDERS, COMPARE_SELECTED_RESPONDERS -> RESPONDER_METRICS;
        case RANK_CATEGORIES, CATEGORY_TREND, BACKLOG_SNAPSHOT -> CATEGORY_METRICS;
        case PERIOD_COMPARISON, OPERATIONAL_OVERVIEW -> COMPANY_OR_CATEGORY_METRICS;
        case SLO_TARGET_COMPARISON -> SLO_METRICS;
        };
        require(allowed.contains(metric));

        boolean ranked = type == QueryType.RANK_RESPONDERS || type == QueryType.RANK_CATEGORIES;
        require(ranked == direction.isPresent());
        require(ranked == limit.isPresent());
        limit.ifPresent(value -> require(value >= 1 && value <= 10));

        boolean permitsComparison = type == QueryType.PERIOD_COMPARISON || type == QueryType.OPERATIONAL_OVERVIEW;
        require(permitsComparison || comparison.isEmpty());
        require(type != QueryType.PERIOD_COMPARISON || comparison.isPresent());

        Grouping effective = grouping.orElseGet(() -> impliedGrouping(type));
        require(switch (type) {
        case RANK_RESPONDERS, COMPARE_SELECTED_RESPONDERS -> effective == Grouping.RESPONDER;
        case RANK_CATEGORIES, CATEGORY_TREND, BACKLOG_SNAPSHOT, SLO_TARGET_COMPARISON -> effective == Grouping.CATEGORY;
        case PERIOD_COMPARISON, OPERATIONAL_OVERVIEW -> effective == Grouping.COMPANY || effective == Grouping.CATEGORY;
        });
    }

    public Grouping effectiveGrouping() {
        require(outcome == Outcome.QUERY);
        return grouping.orElseGet(() -> impliedGrouping(queryType.orElseThrow()));
    }

    private static Grouping impliedGrouping(QueryType type) {
        return switch (type) {
        case RANK_RESPONDERS, COMPARE_SELECTED_RESPONDERS -> Grouping.RESPONDER;
        case RANK_CATEGORIES, CATEGORY_TREND, BACKLOG_SNAPSHOT, SLO_TARGET_COMPARISON -> Grouping.CATEGORY;
        case PERIOD_COMPARISON, OPERATIONAL_OVERVIEW -> Grouping.COMPANY;
        };
    }

    private static <T> Optional<T> requiredOptional(Optional<T> value) {
        return Objects.requireNonNull(value, "plan field");
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid administrator insight plan");
        }
    }

    public enum Outcome { QUERY, NEEDS_CLARIFICATION }
    public enum QueryType {
        RANK_RESPONDERS, COMPARE_SELECTED_RESPONDERS, RANK_CATEGORIES, CATEGORY_TREND,
        PERIOD_COMPARISON, BACKLOG_SNAPSHOT, SLO_TARGET_COMPARISON, OPERATIONAL_OVERVIEW
    }
    public enum Metric {
        RESOLVED_CYCLE_COUNT, AVERAGE_TIME_TO_CLAIM, AVERAGE_TIME_IN_PROGRESS, REOPEN_RATE,
        REOPEN_EVENT_COUNT, SLO_TARGET_COMPARISON, INCIDENT_COUNT, UNASSIGNED_BACKLOG_COUNT,
        OLDEST_UNASSIGNED_AGE, ADMINISTRATOR_RESOLVED_COUNT
    }
    public enum Direction { ASCENDING, DESCENDING }
    public enum Grouping { COMPANY, CATEGORY, RESPONDER }
    public enum ClarificationCode {
        MISSING_PERIOD, AMBIGUOUS_METRIC, UNSUPPORTED_QUESTION, CONFLICTING_PERIODS
    }
    public enum NamedPeriod {
        ROLLING_7_DAYS, ROLLING_30_DAYS, THIS_UTC_WEEK, PREVIOUS_UTC_WEEK,
        THIS_UTC_MONTH, PREVIOUS_UTC_MONTH
    }

    public sealed interface Period permits Named, Explicit { }

    public record Named(NamedPeriod value) implements Period {
        public Named { Objects.requireNonNull(value, "value"); }
    }

    public record Explicit(LocalDate fromDate, LocalDate throughDate) implements Period {
        public Explicit {
            Objects.requireNonNull(fromDate, "fromDate");
            Objects.requireNonNull(throughDate, "throughDate");
            require(!throughDate.isBefore(fromDate));
        }
    }
}
