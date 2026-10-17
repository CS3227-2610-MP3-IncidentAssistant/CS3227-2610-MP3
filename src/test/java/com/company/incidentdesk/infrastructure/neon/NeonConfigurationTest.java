package com.company.incidentdesk.infrastructure.neon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.s3.S3Client;

class NeonConfigurationTest {
    private final NeonConfiguration configuration = new NeonConfiguration();

    @Test
    void localConfigurationMayOmitNeonServices() throws Exception {
        configuration.requiredNeonConfiguration(new MockEnvironment())
                .run(new DefaultApplicationArguments(new String[0]));
    }

    @Test
    void productionConfigurationRejectsMissingServicesWithoutPrintingValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("INCIDENT_DESK_NEON_REQUIRED", "true")
                .withProperty("DATABASE_URL", "secret-value");

        Exception failure = assertThrows(Exception.class,
                () -> configuration.requiredNeonConfiguration(environment)
                        .run(new DefaultApplicationArguments(new String[0])));

        assertTrue(failure.getMessage().contains("AWS_ACCESS_KEY_ID"));
        assertFalse(failure.getMessage().contains("secret-value"));
    }

    @Test
    void createsSmallPoolAndAggregateOnlyHealthResult() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("DATABASE_URL",
                "postgresql://user:pass@localhost:5432/neondb?sslmode=require");
        DataSource dataSource = configuration.neonDataSource(environment);
        try {
            assertEquals(5, ((com.zaxxer.hikari.HikariDataSource) dataSource).getMaximumPoolSize());
            HealthIndicator health = configuration.postgresHealth(dataSource);
            assertEquals("DOWN", health.health().getStatus().getCode());
            assertTrue(health.health().getDetails().isEmpty());
        } finally {
            ((AutoCloseable) dataSource).close();
        }
    }

    @Test
    void createsPathStyleObjectStorageClientFromStandardVariables() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AWS_ENDPOINT_URL_S3", "https://storage.example.test")
                .withProperty("AWS_REGION", "ap-southeast-1")
                .withProperty("AWS_ACCESS_KEY_ID", "test-access")
                .withProperty("AWS_SECRET_ACCESS_KEY", "test-secret");

        try (S3Client client = configuration.neonObjectStorageClient(environment)) {
            assertNotNull(client);
            assertEquals("https://storage.example.test",
                    client.serviceClientConfiguration().endpointOverride().orElseThrow().toString());
            assertEquals("ap-southeast-1", client.serviceClientConfiguration().region().id());
        }
    }
}
