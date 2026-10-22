package com.company.incidentdesk.application.ai.insights;

import static com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.*;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.Optional;

/** Resolves relative plan periods against one application-owned request instant in UTC. */
public final class AdministratorInsightPeriodResolver {
    private static final Duration MAXIMUM_PERIOD = Duration.ofDays(366);
    private final Clock clock;

    public AdministratorInsightPeriodResolver(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ResolvedPlan resolve(AdministratorInsightPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.outcome() != Outcome.QUERY) {
            throw new IllegalArgumentException("A clarification has no analytics period");
        }
        Instant requestInstant = clock.instant();
        ResolvedPeriod primary = resolve(plan.period().orElseThrow(), requestInstant);
        Optional<ResolvedPeriod> comparison = plan.comparisonPeriod().map(value -> resolve(value, requestInstant));
        if (comparison.isPresent()) {
            validateComparison(plan.period().orElseThrow(), plan.comparisonPeriod().orElseThrow(),
                    primary, comparison.orElseThrow());
        }
        return new ResolvedPlan(requestInstant, primary, comparison);
    }

    private static ResolvedPeriod resolve(Period period, Instant requestInstant) {
        ResolvedPeriod result = switch (period) {
        case Named named -> resolve(named.value(), requestInstant);
        case Explicit explicit -> new ResolvedPeriod(
                explicit.fromDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
                explicit.throughDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        };
        if (result.endExclusive().isAfter(requestInstant)
                || Duration.between(result.startInclusive(), result.endExclusive()).compareTo(MAXIMUM_PERIOD) > 0) {
            throw new IllegalArgumentException("Invalid administrator insight period");
        }
        return result;
    }

    private static ResolvedPeriod resolve(NamedPeriod period, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return switch (period) {
        case ROLLING_7_DAYS -> new ResolvedPeriod(now.minus(Duration.ofDays(7)), now);
        case ROLLING_30_DAYS -> new ResolvedPeriod(now.minus(Duration.ofDays(30)), now);
        case THIS_UTC_WEEK -> new ResolvedPeriod(weekStart, now);
        case PREVIOUS_UTC_WEEK -> new ResolvedPeriod(weekStart.minus(Duration.ofDays(7)), weekStart);
        case THIS_UTC_MONTH -> new ResolvedPeriod(monthStart, now);
        case PREVIOUS_UTC_MONTH -> {
            Instant previousStart = today.withDayOfMonth(1).minusMonths(1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            yield new ResolvedPeriod(previousStart, monthStart);
        }
        };
    }

    private static void validateComparison(Period primarySource, Period comparisonSource,
            ResolvedPeriod primary, ResolvedPeriod comparison) {
        boolean adjacent = primary.startInclusive().equals(comparison.endExclusive())
                || comparison.startInclusive().equals(primary.endExclusive());
        if (!adjacent) {
            throw new IllegalArgumentException("Comparison periods must be adjacent");
        }
        if (!(isCalendar(primarySource) && isCalendar(comparisonSource))
                && !primary.duration().equals(comparison.duration())) {
            throw new IllegalArgumentException("Comparison periods must have equal elapsed length");
        }
    }

    private static boolean isCalendar(Period period) {
        return period instanceof Named named && switch (named.value()) {
        case THIS_UTC_WEEK, PREVIOUS_UTC_WEEK, THIS_UTC_MONTH, PREVIOUS_UTC_MONTH -> true;
        case ROLLING_7_DAYS, ROLLING_30_DAYS -> false;
        };
    }

    public record ResolvedPlan(
            Instant requestInstant,
            ResolvedPeriod period,
            Optional<ResolvedPeriod> comparisonPeriod) {
        public ResolvedPlan {
            Objects.requireNonNull(requestInstant, "requestInstant");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(comparisonPeriod, "comparisonPeriod");
        }
    }

    public record ResolvedPeriod(Instant startInclusive, Instant endExclusive) {
        public ResolvedPeriod {
            Objects.requireNonNull(startInclusive, "startInclusive");
            Objects.requireNonNull(endExclusive, "endExclusive");
            if (startInclusive.isAfter(endExclusive)) {
                throw new IllegalArgumentException("Period start must not follow end");
            }
        }

        public Duration duration() {
            return Duration.between(startInclusive, endExclusive);
        }
    }
}
