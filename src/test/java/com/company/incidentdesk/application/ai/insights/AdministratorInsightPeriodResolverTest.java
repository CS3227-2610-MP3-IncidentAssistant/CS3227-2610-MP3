package com.company.incidentdesk.application.ai.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.*;

class AdministratorInsightPeriodResolverTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:34:56Z");
    private final AdministratorInsightPeriodResolver resolver =
            new AdministratorInsightPeriodResolver(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void resolvesRollingAndCurrentUtcPeriodsAgainstOneRequestInstant() {
        var rolling = resolver.resolve(plan(new Named(NamedPeriod.ROLLING_7_DAYS), Optional.empty()));
        assertEquals(Instant.parse("2026-10-16T12:34:56Z"), rolling.period().startInclusive());
        assertEquals(NOW, rolling.period().endExclusive());
        assertEquals(NOW, rolling.requestInstant());

        var week = resolver.resolve(plan(new Named(NamedPeriod.THIS_UTC_WEEK), Optional.empty()));
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), week.period().startInclusive());
        assertEquals(NOW, week.period().endExclusive());

        var month = resolver.resolve(plan(new Named(NamedPeriod.PREVIOUS_UTC_MONTH), Optional.empty()));
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), month.period().startInclusive());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), month.period().endExclusive());

        var rollingMonth = resolver.resolve(plan(new Named(NamedPeriod.ROLLING_30_DAYS), Optional.empty()));
        assertEquals(Instant.parse("2026-09-23T12:34:56Z"), rollingMonth.period().startInclusive());
        assertEquals(NOW, rollingMonth.period().endExclusive());

        var previousWeek = resolver.resolve(plan(new Named(NamedPeriod.PREVIOUS_UTC_WEEK), Optional.empty()));
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), previousWeek.period().startInclusive());
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), previousWeek.period().endExclusive());

        var currentMonth = resolver.resolve(plan(new Named(NamedPeriod.THIS_UTC_MONTH), Optional.empty()));
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), currentMonth.period().startInclusive());
        assertEquals(NOW, currentMonth.period().endExclusive());
    }

    @Test
    void convertsInclusiveExplicitDatesToExclusiveUtcBounds() {
        var resolved = resolver.resolve(plan(
                new Explicit(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-22")), Optional.empty()));

        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), resolved.period().startInclusive());
        assertEquals(Instant.parse("2026-10-23T00:00:00Z"), resolved.period().endExclusive());
    }

    @Test
    void rejectsFutureAndOver366DayPeriods() {
        var maximum = resolver.resolve(plan(
                new Explicit(LocalDate.parse("2025-10-22"), LocalDate.parse("2026-10-22")), Optional.empty()));
        assertEquals(366, maximum.period().duration().toDays());

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(plan(
                new Explicit(LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-23")), Optional.empty())));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(plan(
                new Explicit(LocalDate.parse("2025-10-21"), LocalDate.parse("2026-10-22")), Optional.empty())));
    }

    @Test
    void acceptsAdjacentEqualExplicitComparisonAndRejectsGaps() {
        Period primary = new Explicit(LocalDate.parse("2026-01-08"), LocalDate.parse("2026-01-14"));
        Period adjacent = new Explicit(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-07"));
        var resolved = resolver.resolve(plan(primary, Optional.of(adjacent)));
        assertEquals(Instant.parse("2026-01-08T00:00:00Z"), resolved.period().startInclusive());
        assertEquals(Instant.parse("2026-01-08T00:00:00Z"),
                resolved.comparisonPeriod().orElseThrow().endExclusive());

        Period gap = new Explicit(LocalDate.parse("2025-12-30"), LocalDate.parse("2026-01-05"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(plan(primary, Optional.of(gap))));

        Period unequal = new Explicit(LocalDate.parse("2025-12-31"), LocalDate.parse("2026-01-07"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(plan(primary, Optional.of(unequal))));
    }

    @Test
    void permitsAdjacentCalendarMonthsWithDifferentLengths() {
        var resolved = resolver.resolve(plan(new Named(NamedPeriod.THIS_UTC_MONTH),
                Optional.of(new Named(NamedPeriod.PREVIOUS_UTC_MONTH))));

        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), resolved.period().startInclusive());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"),
                resolved.comparisonPeriod().orElseThrow().endExclusive());
    }

    @Test
    void permitsAnEmptyCurrentCalendarWindowAtItsExactUtcBoundary() {
        var boundaryResolver = new AdministratorInsightPeriodResolver(Clock.fixed(
                Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));

        var resolved = boundaryResolver.resolve(plan(
                new Named(NamedPeriod.THIS_UTC_WEEK), Optional.empty()));

        assertEquals(resolved.period().startInclusive(), resolved.period().endExclusive());
    }

    private static AdministratorInsightPlan plan(Period period, Optional<Period> comparison) {
        return new AdministratorInsightPlan(Outcome.QUERY, Optional.of(QueryType.OPERATIONAL_OVERVIEW),
                Optional.of(Metric.INCIDENT_COUNT), Optional.of(period), comparison,
                Optional.empty(), Optional.empty(), Optional.of(Grouping.COMPANY), Optional.empty());
    }
}
