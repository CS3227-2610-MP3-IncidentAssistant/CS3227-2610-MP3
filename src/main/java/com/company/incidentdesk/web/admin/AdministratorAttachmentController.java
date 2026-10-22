package com.company.incidentdesk.web.admin;

import java.io.IOException;
import java.security.Principal;
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

/** Safe attachment delivery for authorized Administrator incident detail reads. */
@RestController
@RequestMapping("/api/v1/admin/incidents/{incidentId}/attachments")
@ConditionalOnBean({DataSource.class, software.amazon.awssdk.services.s3.S3Client.class})
final class AdministratorAttachmentController {
    private final AdministratorAttachmentService attachments;

    AdministratorAttachmentController(AdministratorAttachmentService attachments) {
        this.attachments = attachments;
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
            }
        };
        String extension = download.metadata().mediaType().equals("image/png") ? "png" : "jpg";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.metadata().mediaType()))
                .contentLength(download.metadata().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attachment." + extension + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(AdministratorIncidentService.IncidentUnavailableException.class)
    ResponseEntity<?> denied() {
        return error(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
    }

    @ExceptionHandler({AdministratorIncidentService.IncidentDataAccessException.class,
            AdministratorAttachmentService.AttachmentUnavailableException.class})
    ResponseEntity<?> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "ATTACHMENT_UNAVAILABLE",
                "Attachment is temporarily unavailable.");
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
