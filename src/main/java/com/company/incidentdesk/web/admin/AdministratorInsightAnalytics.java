package com.company.incidentdesk.web.admin;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPeriodResolver.ResolvedPlan;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Metric;

/** Typed, read-only analytics boundary. Implementations expose no generic query or invocation facility. */
interface AdministratorInsightAnalytics {
    List<String> knownLoginNames(String administratorLoginName);
    Result execute(String administratorLoginName, Query query);

    record Query(AdministratorInsightPlan plan, ResolvedPlan periods, Set<String> categories,
            Set<UUID> responderIds) {
        public Query {
            categories = Set.copyOf(categories);
            responderIds = Set.copyOf(responderIds);
        }
    }

    record Result(List<Value> values, Instant snapshotAt) {
        public Result { values = List.copyOf(values); }
    }

    record Value(String label, Metric metric, Double numericValue, long sampleSize) { }
}
