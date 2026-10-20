package com.company.incidentdesk.web.responder;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Privacy-safe, per-request authorized Responder attachment delivery. */
@RestController
@RequestMapping("/api/v1/responder/incidents/{incidentId}/attachments")
@ConditionalOnBean({DataSource.class, software.amazon.awssdk.services.s3.S3Client.class})
final class ResponderAttachmentController {
    private final ResponderAttachmentService attachments;

    ResponderAttachmentController(ResponderAttachmentService attachments) {
        this.attachments = attachments;
    }

    @GetMapping
    ResponseEntity<List<ResponderAttachmentService.AttachmentSummary>> list(
            Principal principal, @PathVariable UUID incidentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(attachments.list(principal.getName(), incidentId));
    }

    @GetMapping("/{attachmentId}")
    ResponseEntity<StreamingResponseBody> download(
            Principal principal, @PathVariable UUID incidentId, @PathVariable UUID attachmentId) {
        var download = attachments.open(principal.getName(), incidentId, attachmentId);
        StreamingResponseBody body = output -> {
            try (download) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = download.content().read(buffer)) != -1) {
                    total += read;
                    if (total > download.metadata().sizeBytes()) throw new IOException("Attachment length mismatch");
                    output.write(buffer, 0, read);
                }
                if (total != download.metadata().sizeBytes()) throw new IOException("Attachment length mismatch");
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        };
        String extension = download.metadata().mediaType().equals("image/png") ? "png" : "jpg";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.metadata().mediaType()))
                .contentLength(download.metadata().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attachment." + extension + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(ResponderIncidentService.ResponderAccessDeniedException.class)
    ResponseEntity<?> denied() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                .body(Map.of("code", "UNAVAILABLE", "message", "Resource unavailable"));
    }

    @ExceptionHandler({ResponderIncidentService.ResponderDataAccessException.class,
            ResponderAttachmentService.AttachmentUnavailableException.class})
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(Map.of("code", "ATTACHMENT_UNAVAILABLE", "message", "Attachment is temporarily unavailable."));
    }
}
