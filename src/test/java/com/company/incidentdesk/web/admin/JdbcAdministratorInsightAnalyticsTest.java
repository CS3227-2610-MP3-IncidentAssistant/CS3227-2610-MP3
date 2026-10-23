package com.company.incidentdesk.web.admin;

import static com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Direction.ASCENDING;
import static com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Direction.DESCENDING;
import static com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Metric.INCIDENT_COUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** `AI-ADM-QUERY-03` ranking rules: attribution, direction, ties, ordering, and the response ceiling. */
class JdbcAdministratorInsightAnalyticsTest {
    @Test
    void preservesEveryFirstPlaceTieWhenOneResultWasRequested() {
        List<AdministratorInsightAnalytics.Value> ranked = JdbcAdministratorInsightAnalytics.ranked(List.of(
                value("Security", 3),
                value("Facilities", 7),
                value("IT", 7)), DESCENDING, 1);

        assertEquals(List.of("Facilities", "IT"), labels(ranked));
    }

    @Test
    void doesNotExpandTheLimitForATieOutsideFirstPlace() {
        List<AdministratorInsightAnalytics.Value> ranked = JdbcAdministratorInsightAnalytics.ranked(List.of(
                value("IT", 9),
                value("Facilities", 7),
                value("Security", 7)), DESCENDING, 2);

        assertEquals(List.of("IT", "Facilities"), labels(ranked));
    }

    @Test
    void descendingRankingReportsTheLargestValueFirst() {
        List<AdministratorInsightAnalytics.Value> ranked = JdbcAdministratorInsightAnalytics.ranked(List.of(
                value("Facilities", 2),
                value("IT", 8),
                value("Security", 5)), DESCENDING, 10);

        assertEquals(List.of("IT", "Security", "Facilities"), labels(ranked));
    }

    @Test
    void ascendingRankingReportsTheSmallestValueFirst() {
        List<AdministratorInsightAnalytics.Value> ranked = JdbcAdministratorInsightAnalytics.ranked(List.of(
                value("Facilities", 2),
                value("IT", 8),
                value("Security", 5)), ASCENDING, 10);

        assertEquals(List.of("Facilities", "Security", "IT"), labels(ranked));
    }

    @Test
    void equalValuesUseCaseInsensitiveDisplayNameAsTheStableSecondaryOrder() {
        List<AdministratorInsightAnalytics.Value> ranked = JdbcAdministratorInsightAnalytics.ranked(List.of(
                value("security", 4),
                value("IT", 4),
                value("Facilities", 4)), DESCENDING, 10);

        assertEquals(List.of("Facilities", "IT", "security"), labels(ranked));
    }

    @Test
    void preservesTheReportedSampleSizeForEveryRankedRow() {
        List<AdministratorInsightAnalytics.Value> ranked = JdbcAdministratorInsightAnalytics.ranked(List.of(
                new AdministratorInsightAnalytics.Value("IT", INCIDENT_COUNT, 9d, 9),
                new AdministratorInsightAnalytics.Value("Facilities", INCIDENT_COUNT, 1d, 1)), DESCENDING, 10);

        assertEquals(List.of(9L, 1L), ranked.stream()
                .map(AdministratorInsightAnalytics.Value::sampleSize).toList());
    }

    @Test
    void rankingNeverExceedsTheTenRowResponseCeilingEvenWhenEveryRowTiesForFirstPlace() {
        List<AdministratorInsightAnalytics.Value> tied = IntStream.range(0, 14)
                .mapToObj(index -> value("category-" + (char) ('a' + index), 5))
                .toList();

        List<AdministratorInsightAnalytics.Value> ranked =
                JdbcAdministratorInsightAnalytics.ranked(tied, DESCENDING, 1);

        assertEquals(10, ranked.size());
        assertEquals("category-a", ranked.getFirst().label());
        assertEquals("category-j", ranked.getLast().label());
    }

    @Test
    void noQualifyingDataProducesAnEmptyRankingRatherThanAFabricatedRow() {
        assertTrue(JdbcAdministratorInsightAnalytics.ranked(List.of(), DESCENDING, 10).isEmpty());
    }

    private static List<String> labels(List<AdministratorInsightAnalytics.Value> values) {
        return values.stream().map(AdministratorInsightAnalytics.Value::label).toList();
    }

    private static AdministratorInsightAnalytics.Value value(String label, double value) {
        return new AdministratorInsightAnalytics.Value(label, INCIDENT_COUNT, value, (long) value);
    }
}
