package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.json.JsonMapper;

class AiUsagePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");

    @Test
    void roundsCombinedPriceUpWithoutFloatingPointOrOverflow() {
        var price = new AiUsagePolicy.Price("test-model", 411810, 1456571);
        assertEquals(5043, price.cost(8000, 1200));
        assertEquals(1, new AiUsagePolicy.Price("test", 1, 1).cost(1, 1));
        assertEquals(0, price.cost(0, 0));
        var enormousPrice = new AiUsagePolicy.Price("test", Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(84855022739063938L, enormousPrice.cost(8000, 1200));
        assertThrows(IllegalArgumentException.class, () -> enormousPrice.cost(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void defaultsAndLowerOnlyEnvironmentLimitsDoNotExposeSuppliedValues() {
        var defaults = AiUsagePolicy.Limits.from(new MockEnvironment());
        assertEquals(5, defaults.accountMinute());
        assertEquals(30, defaults.accountHour());
        assertEquals(100, defaults.accountDay());
        assertEquals(60, defaults.globalMinute());
        assertEquals(8, defaults.concurrent());
        assertEquals(60000000, defaults.daily());
        assertEquals(600000000, defaults.monthly());
        var lower = AiUsagePolicy.Limits.from(new MockEnvironment()
                .withProperty("AI_GLOBAL_REQUESTS_PER_MINUTE", "7")
                .withProperty("AI_DAILY_MICRODOLLARS", "19"));
        assertEquals(7, lower.globalMinute());
        assertEquals(19, lower.daily());
        for (String value : new String[]{"61", "0", "-1", "sensitive-value"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> AiUsagePolicy.Limits.from(
                    new MockEnvironment().withProperty("AI_GLOBAL_REQUESTS_PER_MINUTE", value)));
            assertFalse(failure.toString().contains(value));
            assertNull(failure.getCause());
        }
    }

    @Test
    void snapshotRequiresFreshCompleteVerifiedInputsAndDistinguishesZeroLifetime() {
        var price = new AiUsagePolicy.Price("test", 1, 1);
        var noLifetime = new AiUsagePolicy.Snapshot(NOW, 30, 50000000, 500000000,
                null, 0, 0, 0, price);
        assertTrue(noLifetime.freshAt(NOW));
        assertFalse(noLifetime.freshAt(NOW.plusSeconds(900)));
        assertFalse(noLifetime.freshAt(NOW.minusSeconds(1)));
        assertEquals(20, noLifetime.requestCeiling());
        assertEquals(37500000, AiUsagePolicy.reserveCeiling(noLifetime.daily()));
        assertEquals(0, new AiUsagePolicy.Snapshot(NOW, 30, 1, 1, 0L, 0, 0, 0, price).lifetime());
        assertThrows(IllegalArgumentException.class,
                () -> new AiUsagePolicy.Snapshot(NOW, 30, -1, 1, null, 0, 0, 0, price));
        assertThrows(IllegalArgumentException.class, () -> new AiUsagePolicy.Price("unsafe model\n", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AiUsagePolicy.Price("test", -1, 1));
    }

    @Test
    void verifiedBudgetMustBeRefreshedAtUtcWindowBoundary() {
        Instant beforeReset = Instant.parse("2026-10-31T23:59:59Z");
        var snapshot = new AiUsagePolicy.Snapshot(beforeReset, 30, 100, 1000, null,
                0, 0, 0, new AiUsagePolicy.Price("test", 1, 1));
        assertFalse(snapshot.freshAt(beforeReset.plusSeconds(1)));
    }

    @Test
    void priceUsesVerifiedCatalogFieldsAndRejectsMissingFractionalOrOutOfRangeRates() {
        var json = JsonMapper.builder().build();
        var metadata = json.readTree("""
                {"id":"test","soclaas":{"input_microdollars_per_million_tokens":411810,
                "output_microdollars_per_million_tokens":1456571}}
                """);
        assertEquals(5043, AiUsagePolicy.Price.fromCatalog(new SoclaasClient.Model("test", metadata)).cost(8000, 1200));
        for (String invalid : new String[]{"{}", "null", "1.5", "-1", "9223372036854775808", "\"12\""}) {
            var bad = metadata.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) bad.path("soclaas"))
                    .set("input_microdollars_per_million_tokens", json.readTree(invalid));
            var failure = assertThrows(AiUsageException.class,
                    () -> AiUsagePolicy.Price.fromCatalog(new SoclaasClient.Model("test", bad)));
            assertEquals(AiUsageException.Category.AI_UNAVAILABLE, failure.category());
            assertNull(failure.getCause());
        }
        assertThrows(AiUsageException.class, () -> AiUsagePolicy.Price.fromCatalog(
                new SoclaasClient.Model("different", metadata)));
    }
}
