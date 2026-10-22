package com.company.incidentdesk.web.admin;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Streams an attachment only after current Administrator and incident authorization. */
@Service
@ConditionalOnBean({DataSource.class, S3Client.class})
final class AdministratorAttachmentService {
    private final NamedParameterJdbcTemplate jdbc;
    private final S3Client storage;
    private final String bucket;

    AdministratorAttachmentService(
            NamedParameterJdbcTemplate jdbc, S3Client storage, String neonAttachmentBucket) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.bucket = neonAttachmentBucket;
    }

    AttachmentDownload open(String loginName, UUID incidentId, UUID attachmentId) {
        AttachmentMetadata metadata;
        try {
            List<AttachmentMetadata> results = jdbc.query("""
                    SELECT attachment.id, attachment.media_type, attachment.size_bytes
                      FROM incident_attachments attachment
                      JOIN incidents incident ON incident.id = attachment.incident_id
                      JOIN accounts administrator
                        ON administrator.login_name = :loginName
                       AND administrator.role = 'ADMINISTRATOR'
                       AND administrator.status = 'ENABLED'
                     WHERE incident.id = :incidentId AND attachment.id = :attachmentId
                    """, Map.of("loginName", loginName, "incidentId", incidentId, "attachmentId", attachmentId),
                    (result, row) -> new AttachmentMetadata(result.getObject("id", UUID.class),
                            result.getString("media_type"), result.getLong("size_bytes")));
            if (results.isEmpty()) throw new AdministratorIncidentService.IncidentUnavailableException();
            metadata = results.getFirst();
        } catch (DataAccessException exception) {
            throw new AdministratorIncidentService.IncidentDataAccessException(exception);
        }
        String extension = metadata.mediaType().equals("image/png") ? "png" : "jpg";
        try {
            ResponseInputStream<GetObjectResponse> content = storage.getObject(GetObjectRequest.builder()
                    .bucket(bucket).key(metadata.id() + "." + extension).build());
            return new AttachmentDownload(metadata, content);
        } catch (RuntimeException exception) {
            throw new AttachmentUnavailableException();
        }
    }

    record AttachmentMetadata(UUID id, String mediaType, long sizeBytes) { }

    record AttachmentDownload(AttachmentMetadata metadata, InputStream content) implements AutoCloseable {
        @Override public void close() throws java.io.IOException { content.close(); }
    }

    static final class AttachmentUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
