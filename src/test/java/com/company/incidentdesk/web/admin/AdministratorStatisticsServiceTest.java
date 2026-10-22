package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AdministratorStatisticsServiceTest {
    private final RecordingStore store = new RecordingStore();
    private final AdministratorStatisticsService service = new AdministratorStatisticsService(store);

    @Test
    void validatesAndNormalizesFiltersBeforeReadingProtectedStatistics() {
        UUID responder = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant through = Instant.parse("2026-01-31T23:59:59Z");

        service.read("admin", new AdministratorStatisticsService.Query(
                List.of("IT", "", "IT"), responder, reporter, from, through));

        assertEquals(1, store.calls);
        assertEquals("admin", store.actor);
        assertEquals(java.util.Set.of("IT"), store.filter.categories());
        assertEquals(responder, store.filter.responderId());
        assertEquals(reporter, store.filter.reporterId());
        assertEquals(from, store.filter.periodFrom());
        assertEquals(through, store.filter.periodThrough());
    }

    @Test
    void rejectsUnknownCategoryAndInvertedPeriodBeforeStoreAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.read("admin",
                new AdministratorStatisticsService.Query(
                        List.of("UNKNOWN"), null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.read("admin",
                new AdministratorStatisticsService.Query(
                        List.of(), null, null,
                        Instant.parse("2026-02-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z"))));

        assertEquals(0, store.calls);
    }

    private static final class RecordingStore implements AdministratorStatisticsStore {
        private int calls;
        private String actor;
        private StatisticsFilter filter;

        @Override
        public ReadResult read(String administratorLoginName, StatisticsFilter query) {
            calls++;
            actor = administratorLoginName;
            filter = query;
            return new ReadResult(ReadOutcome.AVAILABLE, Summary.empty(), List.of(), FilterOptions.empty());
        }
    }
}
