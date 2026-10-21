package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdministratorSloServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final UUID VERSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AUDIT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private AdministratorSloStore store;
    private AdministratorSloService service;

    @BeforeEach
    void setUp() {
        store = mock(AdministratorSloStore.class);
        var identifiers = List.of(VERSION_ID, AUDIT_ID).iterator();
        service = new AdministratorSloService(store, Clock.fixed(NOW, ZoneOffset.UTC), identifiers::next);
    }

    @Test
    void readsEveryCategoryAndPreservesChronologicalHistory() {
        var first = version(UUID.randomUUID(), "IT", Instant.parse("2026-10-20T00:00:00Z"));
        var second = version(UUID.randomUUID(), "IT", NOW);
        when(store.readAll("admin")).thenReturn(new AdministratorSloStore.ReadResult(
                AdministratorSloStore.ReadOutcome.AVAILABLE, List.of(first, second)));

        AdministratorSloService.SloConfiguration result = service.configuration("admin");

        assertEquals(3, result.categories().size());
        assertEquals(second.id(), result.categories().stream()
                .filter(category -> category.category().equals("IT")).findFirst().orElseThrow().current().id());
        assertNull(result.categories().stream()
                .filter(category -> category.category().equals("FACILITIES")).findFirst().orElseThrow().current());
    }

    @Test
    void rejectsInvalidValuesBeforePersistence() {
        var request = new AdministratorSloService.ChangeRequest(-1L, 5L, new BigDecimal("100.001"), null);

        AdministratorSloService.ChangeResult result = service.change("admin", "UNKNOWN", request);

        assertEquals(3, result.fieldErrors().size());
        verify(store, never()).change(any(), any(), any(), any(Long.class), any(Long.class), any(), any(), any(), any());
    }

    @Test
    void passesTrustedActorNormalizedValuesIdentifiersAndUtcTime() {
        var request = new AdministratorSloService.ChangeRequest(10L, 20L, new BigDecimal("4.5"), null);
        when(store.change("admin", "IT", null, 10, 20, new BigDecimal("4.50"), VERSION_ID, AUDIT_ID, NOW))
                .thenReturn(AdministratorSloStore.ChangeOutcome.CHANGED);

        AdministratorSloService.ChangeResult result = service.change("admin", "IT", request);

        assertEquals(AdministratorSloStore.ChangeOutcome.CHANGED, result.outcome());
        verify(store).change("admin", "IT", null, 10, 20, new BigDecimal("4.50"), VERSION_ID, AUDIT_ID, NOW);
    }

    private static AdministratorSloStore.SloVersion version(UUID id, String category, Instant effectiveAt) {
        return new AdministratorSloStore.SloVersion(
                id, category, 10, 20, new BigDecimal("5.00"), effectiveAt, UUID.randomUUID());
    }
}
