package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class AiGatewayFakeProvider implements AutoCloseable {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> received = new AtomicReference<>();
    volatile String body = """
            {"choices":[{"message":{"role":"assistant","content":"suggestion"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
            """;
    volatile int status = 200;
    volatile String retryAfter;
    volatile CountDownLatch hold;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    AiGatewayFakeProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/models", exchange -> respond(exchange, 200, """
                {"data":[{"id":"test","soclaas":{"input_microdollars_per_million_tokens":1000000,
                "output_microdollars_per_million_tokens":1000000}}]}
                """, null));
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.incrementAndGet();
            CountDownLatch pending = hold;
            if (pending != null) {
                try {
                    pending.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
            }
            respond(exchange, status, body, retryAfter);
        });
        server.start();
    }

    URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    void waitForCalls(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (calls.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(calls.get() >= expected, "Expected provider calls did not arrive within five seconds");
    }

    private static void respond(HttpExchange exchange, int status, String body, String retryAfter)
            throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (retryAfter != null) {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
            }
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        CountDownLatch pending = hold;
        if (pending != null) {
            pending.countDown();
        }
        server.stop(0);
        executor.shutdownNow();
    }
}
