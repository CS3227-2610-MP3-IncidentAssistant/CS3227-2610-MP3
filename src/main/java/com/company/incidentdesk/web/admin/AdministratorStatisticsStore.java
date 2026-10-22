package com.company.incidentdesk.web.admin;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistence boundary for privacy-safe administrator operational statistics. */
interface AdministratorStatisticsStore {
    ReadResult read(String administratorLoginName, StatisticsFilter filter);

    enum ReadOutcome { AVAILABLE, RESOURCE_UNAVAILABLE, PERSISTENCE_FAILURE }

    record StatisticsFilter(
            Set<String> categories,
            UUID responderId,
            UUID reporterId,
            Instant periodFrom,
            Instant periodThrough) {
        public StatisticsFilter {
            categories = Set.copyOf(categories);
        }
    }

    record ReadResult(
            ReadOutcome outcome,
            Summary summary,
            List<ResponderBreakdown> responders,
            FilterOptions filterOptions) {
        public ReadResult {
            responders = List.copyOf(responders);
        }

        static ReadResult unavailable() {
            return new ReadResult(ReadOutcome.RESOURCE_UNAVAILABLE, Summary.empty(), List.of(), FilterOptions.empty());
        }

        static ReadResult persistenceFailure() {
            return new ReadResult(ReadOutcome.PERSISTENCE_FAILURE, Summary.empty(), List.of(), FilterOptions.empty());
        }
    }

    record Summary(
            long resolvedIncidentCount,
            Long averageTimeToClaimMillis,
            long claimedCycleCount,
            Long averageTimeInProgressMillis,
            long completedCycleCount,
            long reopenedIncidentCount,
            Double reopenRate,
            long reopenEventCount,
            long administratorResolvedCount) {
        static Summary empty() {
            return new Summary(0, null, 0, null, 0, 0, null, 0, 0);
        }
    }

    record ResponderBreakdown(
            UUID responderId,
            String displayName,
            long resolvedCycleCount,
            Long averageTimeInProgressMillis,
            long reopenedCycleCount,
            Double reopenRate,
            long administratorResolvedCount) { }

    record FilterOptions(List<IdentityOption> responders, List<IdentityOption> reporters) {
        public FilterOptions {
            responders = List.copyOf(responders);
            reporters = List.copyOf(reporters);
        }

        static FilterOptions empty() {
            return new FilterOptions(List.of(), List.of());
        }
    }

    record IdentityOption(UUID id, String displayName) { }
}
