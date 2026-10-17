package com.company.incidentdesk.infrastructure.neon;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/** Creates server-side clients for the Neon services declared in {@code neon.ts}. */
@Configuration(proxyBeanMethods = false)
public class NeonConfiguration {
    public static final String DEFAULT_BUCKET = "incident-attachments";
    private static final List<String> REQUIRED_VARIABLES = List.of(
            "DATABASE_URL", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_ENDPOINT_URL_S3", "AWS_REGION");

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "DATABASE_URL")
    DataSource neonDataSource(Environment environment) {
        NeonDatabaseUrl database = NeonDatabaseUrl.parse(required(environment, "DATABASE_URL"));
        HikariConfig config = new HikariConfig();
        config.setPoolName("incident-desk-neon");
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        config.setValidationTimeout(Duration.ofSeconds(3).toMillis());
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "AWS_ENDPOINT_URL_S3")
    S3Client neonObjectStorageClient(Environment environment) {
        URI endpoint = URI.create(required(environment, "AWS_ENDPOINT_URL_S3"));
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(required(environment, "AWS_REGION")))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .build())
                .build();
    }

    @Bean
    String neonAttachmentBucket(Environment environment) {
        return environment.getProperty("INCIDENT_DESK_ATTACHMENT_BUCKET", DEFAULT_BUCKET);
    }

    @Bean
    ApplicationRunner requiredNeonConfiguration(Environment environment) {
        return new RequiredConfigurationCheck(environment);
    }

    @Bean(name = "postgres")
    @ConditionalOnBean(DataSource.class)
    HealthIndicator postgresHealth(DataSource dataSource) {
        return () -> {
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement("SELECT 1");
                    var result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1 ? Health.up().build() : Health.down().build();
            } catch (Exception exception) {
                return Health.down().build();
            }
        };
    }

    @Bean(name = "objectStorage")
    @ConditionalOnBean(S3Client.class)
    HealthIndicator objectStorageHealth(S3Client client, String neonAttachmentBucket) {
        return () -> {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(neonAttachmentBucket).build());
                return Health.up().build();
            } catch (Exception exception) {
                return Health.down().build();
            }
        };
    }

    private static String required(Environment environment, String name) {
        return Objects.requireNonNull(environment.getProperty(name), name + " is required");
    }

    private static final class RequiredConfigurationCheck implements ApplicationRunner {
        private final Environment environment;

        private RequiredConfigurationCheck(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void run(ApplicationArguments arguments) {
            if (!environment.getProperty("INCIDENT_DESK_NEON_REQUIRED", Boolean.class, false)) {
                return;
            }
            List<String> missing = REQUIRED_VARIABLES.stream()
                    .filter(name -> environment.getProperty(name) == null
                            || environment.getProperty(name).isBlank())
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Required Neon configuration is missing: " + String.join(", ", missing));
            }
        }
    }
}
