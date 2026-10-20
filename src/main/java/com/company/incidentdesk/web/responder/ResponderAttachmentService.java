package com.company.incidentdesk.web.responder;

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

/** Reads only attachments whose incident is currently visible to this Responder. */
@Service
@ConditionalOnBean({DataSource.class, S3Client.class})
class ResponderAttachmentService {
    private static final String AUTHORIZED_INCIDENT = """
            ((i.status = 'SUBMITTED' AND i.assignee_id IS NULL AND EXISTS (
                    SELECT 1 FROM responder_category_access access
                     WHERE access.account_id = responder.id AND access.category = i.category
                )) OR (i.status = 'ASSIGNED' AND i.assignee_id = responder.id))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final S3Client storage;
    private final String bucket;

    ResponderAttachmentService(NamedParameterJdbcTemplate jdbc, S3Client storage, String neonAttachmentBucket) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.bucket = neonAttachmentBucket;
    }

    List<AttachmentSummary> list(String loginName, UUID incidentId) {
        try {
            return jdbc.query("""
                    SELECT attachment.id, attachment.media_type, attachment.size_bytes, attachment.created_at
                      FROM incidents i
                      JOIN accounts responder
                        ON responder.login_name = :loginName
                       AND responder.role = 'RESPONDER'
                       AND responder.status = 'ENABLED'
                      JOIN incident_attachments attachment ON attachment.incident_id = i.id
                     WHERE i.id = :incidentId
                       AND %s
                     ORDER BY attachment.created_at, attachment.id
                    """.formatted(AUTHORIZED_INCIDENT),
                    Map.of("loginName", loginName, "incidentId", incidentId), (row, index) ->
                    new AttachmentSummary(row.getObject("id", UUID.class), row.getString("media_type"),
                            row.getLong("size_bytes"), row.getObject("created_at", java.time.OffsetDateTime.class)
                                    .toInstant()));
        } catch (DataAccessException exception) {
            throw new ResponderIncidentService.ResponderDataAccessException(exception);
        }
    }

    AttachmentDownload open(String loginName, UUID incidentId, UUID attachmentId) {
        AttachmentSummary metadata;
        try {
            List<AttachmentSummary> results = jdbc.query("""
                    SELECT attachment.id, attachment.media_type, attachment.size_bytes, attachment.created_at
                      FROM incidents i
                      JOIN accounts responder
                        ON responder.login_name = :loginName
                       AND responder.role = 'RESPONDER'
                       AND responder.status = 'ENABLED'
                      JOIN incident_attachments attachment
                        ON attachment.incident_id = i.id AND attachment.id = :attachmentId
                     WHERE i.id = :incidentId
                       AND %s
                    """.formatted(AUTHORIZED_INCIDENT),
                    Map.of("loginName", loginName, "incidentId", incidentId, "attachmentId", attachmentId),
                    (row, index) -> new AttachmentSummary(row.getObject("id", UUID.class),
                            row.getString("media_type"), row.getLong("size_bytes"),
                            row.getObject("created_at", java.time.OffsetDateTime.class).toInstant()));
            if (results.isEmpty()) throw new ResponderIncidentService.ResponderAccessDeniedException();
            metadata = results.getFirst();
        } catch (DataAccessException exception) {
            throw new ResponderIncidentService.ResponderDataAccessException(exception);
        }

        String extension = metadata.mediaType().equals("image/png") ? "png" : "jpg";
        String key = metadata.id() + "." + extension;
        try {
            ResponseInputStream<GetObjectResponse> stream = storage.getObject(GetObjectRequest.builder()
                    .bucket(bucket).key(key).build());
            return new AttachmentDownload(metadata, stream);
        } catch (RuntimeException exception) {
            throw new AttachmentUnavailableException();
        }
    }

    record AttachmentSummary(UUID id, String mediaType, long sizeBytes, java.time.Instant createdAt) {
        String displayName() { return "Attachment"; }
    }

    record AttachmentDownload(AttachmentSummary metadata, InputStream content) implements AutoCloseable {
        @Override public void close() throws java.io.IOException { content.close(); }
    }

    static final class AttachmentUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
