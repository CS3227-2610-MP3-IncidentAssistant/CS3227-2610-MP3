package com.company.incidentdesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointIntegrationTest {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void exposesOnlyAggregateHealthStatus() throws Exception {
        HttpResponse<String> response = get("/health");

        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
        assertFalse(response.body().contains("components"));
        assertFalse(response.body().contains("details"));
    }

    @Test
    void doesNotExposeOtherActuatorEndpoints() throws Exception {
        assertEquals(404, get("/env").statusCode());
    }

    @Test
    void servesTheReactApplicationFromTheWebArtifact() throws Exception {
        HttpResponse<String> response = get("/", "text/html");

        assertEquals(200, response.statusCode());
        assertEquals("text/html", response.headers().firstValue("content-type")
                .orElseThrow().split(";")[0]);
        assertTrue(response.body().contains("Incident Desk Administration"));
        assertTrue(response.body().contains("/assets/"));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(path, "application/json");
    }

    private HttpResponse<String> get(String path, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", accept)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
