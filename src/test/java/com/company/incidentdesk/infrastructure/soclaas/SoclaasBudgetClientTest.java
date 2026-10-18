package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SoclaasBudgetClientTest {
    private static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void parsesObservedAllowancePolicyWithoutTreatingZeroFallbackCreditsAsAnExhaustedCap() throws Exception {
        var budget = SoclaasBudgetClient.parse(fixture(), NOW);
        assertEquals(30, budget.requestsPerMinute());
        assertEquals(50000000, budget.daily());
        assertEquals(500000000, budget.monthly());
        assertTrue(budget.lifetime().isEmpty());
        assertEquals(36, budget.daySpent());
        assertEquals(36, budget.monthSpent());
        assertEquals(NOW, budget.verifiedAt());
    }

    @Test
    void providerAndEffectiveSpendCapsCanOnlyLowerAllowances() throws Exception {
        var root = (ObjectNode) json.readTree(fixture());
        ((ObjectNode) root.path("effective_limits")).put("daily_microdollar_spend_cap", 100);
        ((ObjectNode) root.path("policy")).put("monthly_microdollar_spend_cap", 200);
        var budget = SoclaasBudgetClient.parse(json.writeValueAsBytes(root), NOW);
        assertEquals(100, budget.daily());
        assertEquals(200, budget.monthly());
    }

    @Test
    void unknownFundingCreditUnitsMissingFieldsAndWrongWindowsFailClosed() throws Exception {
        for (String path : List.of("api_key.is_active", "policy.requests_per_minute_limit",
                "effective_limits.daily_microdollar_allowance", "credits.remaining", "windows.day_start")) {
            var root = (ObjectNode) json.readTree(fixture());
            String[] parts = path.split("\\.");
            ((ObjectNode) root.path(parts[0])).remove(parts[1]);
            assertThrows(SoclaasException.class, () -> SoclaasBudgetClient.parse(json.writeValueAsBytes(root), NOW));
        }
        for (String mode : List.of("unverified_mode", "credits_only")) {
            var root = (ObjectNode) json.readTree(fixture());
            ((ObjectNode) root.path("policy")).put("normal_service_funding_mode", mode);
            assertThrows(SoclaasException.class, () -> SoclaasBudgetClient.parse(json.writeValueAsBytes(root), NOW));
        }
        var root = (ObjectNode) json.readTree(fixture());
        ((ObjectNode) root.path("credits")).put("total_granted", 100);
        assertThrows(SoclaasException.class, () -> SoclaasBudgetClient.parse(json.writeValueAsBytes(root), NOW));
        assertThrows(SoclaasException.class, () -> SoclaasBudgetClient.parse(fixtureUnchecked(), NOW.plusSeconds(86400)));
    }

    @Test
    void fixedHostBudgetTransportRejectsRedirectsAndNeverEchoesCredentialsOrBodies() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        AtomicInteger leaked = new AtomicInteger();
        server.createContext("/budget", exchange -> {
            assertEquals("Bearer synthetic-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location", "/leaked");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/leaked", exchange -> { leaked.incrementAndGet(); exchange.close(); });
        server.start();
        try (var client = new SoclaasBudgetClient(settings(), Clock.fixed(NOW, ZoneOffset.UTC),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/budget"))) {
            var failure = assertThrows(SoclaasException.class, client::load);
            assertFalse(failure.toString().contains("synthetic-secret"));
            assertNull(failure.getCause());
            assertEquals(0, leaked.get());
        } finally { server.stop(0); executor.shutdownNow(); }
    }

    @Test
    void budgetTransportBoundsStalledAndOversizedBodies() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/huge", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (exchange) { exchange.getResponseBody().write(new byte[20000]); }
        });
        server.createContext("/stalled", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            for (String path : List.of("huge", "stalled")) {
                try (var client = new SoclaasBudgetClient(settings(), Clock.fixed(NOW, ZoneOffset.UTC),
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + path))) {
                    assertThrows(SoclaasException.class, client::load);
                }
            }
        } finally { server.stop(0); executor.shutdownNow(); }
    }

    private SoclaasSettings settings() {
        return SoclaasSettings.from(new MockEnvironment().withProperty("AI_ENABLED", "true")
                .withProperty("SOCLAAS_API_KEY", "synthetic-secret").withProperty("SOCLAAS_MODEL", "test")
                .withProperty("AI_REQUEST_TIMEOUT_MS", "200"));
    }

    private byte[] fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/soclaas/budget.json")) { return input.readAllBytes(); }
    }

    private byte[] fixtureUnchecked() {
        try { return fixture(); } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
