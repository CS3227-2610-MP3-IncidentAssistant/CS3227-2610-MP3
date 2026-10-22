package com.company.incidentdesk.web.admin;

import static com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Direction.DESCENDING;
import static com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Metric.INCIDENT_COUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class JdbcAdministratorInsightAnalyticsTest {
    @Test
    void preservesEveryFirstPlaceTieWhenOneResultWasRequested() {
        List<AdministratorInsightAnalytics.Value> ranked = JdbcAdministratorInsightAnalytics.ranked(List.of(
                value("Security", 3),
                value("Facilities", 7),
                value("IT", 7)), DESCENDING, 1);

        assertEquals(List.of("Facilities", "IT"), ranked.stream()
                .map(AdministratorInsightAnalytics.Value::label)
                .toList());
    }

    @Test
    void doesNotExpandTheLimitForATieOutsideFirstPlace() {
        List<AdministratorInsightAnalytics.Value> ranked = JdbcAdministratorInsightAnalytics.ranked(List.of(
                value("IT", 9),
                value("Facilities", 7),
                value("Security", 7)), DESCENDING, 2);

        assertEquals(List.of("IT", "Facilities"), ranked.stream()
                .map(AdministratorInsightAnalytics.Value::label)
                .toList());
    }

    private static AdministratorInsightAnalytics.Value value(String label, double value) {
        return new AdministratorInsightAnalytics.Value(label, INCIDENT_COUNT, value, (long) value);
    }
}
