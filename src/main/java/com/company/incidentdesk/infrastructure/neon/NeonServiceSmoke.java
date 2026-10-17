package com.company.incidentdesk.infrastructure.neon;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.IncidentDeskWebApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Executes a bounded live service check without reading or modifying incident data. */
public final class NeonServiceSmoke {
    private NeonServiceSmoke() {}

    public static void main(String[] arguments) {
        try (var context = new SpringApplicationBuilder(IncidentDeskWebApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments)) {
            verifyPostgres(context.getBean(DataSource.class));
            verifyObjectStorage(
                    context.getBean(S3Client.class), context.getBean("neonAttachmentBucket", String.class));
        }
    }

    private static void verifyPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT 1");
                var result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new IllegalStateException("Neon PostgreSQL returned an unexpected probe result");
            }
            System.out.println("Neon PostgreSQL smoke check: PASS (SELECT 1)");
        } catch (Exception exception) {
            throw new IllegalStateException("Neon PostgreSQL smoke check failed", exception);
        }
    }

    private static void verifyObjectStorage(S3Client client, String bucket) {
        String key = "smoke-tests/" + UUID.randomUUID();
        byte[] expected = ("incident-desk-neon-smoke:" + UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/octet-stream")
                    .build(), RequestBody.fromBytes(expected));
            byte[] actual = client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray();
            if (!Arrays.equals(expected, actual)) {
                throw new IllegalStateException("Neon Object Storage returned different bytes");
            }
            System.out.println("Neon Object Storage smoke check: PASS (put/get)");
        } finally {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            System.out.println("Neon Object Storage smoke cleanup: PASS (delete)");
        }
    }
}
