package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class ResponderAttachmentControllerTest {
    @Test
    void downloadStreamsBytesWithSafeHeadersAndGeneratedFilename() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] bytes = new byte[] { 1, 2, 3 };
        var metadata = new ResponderAttachmentService.AttachmentSummary(
                attachmentId, "image/png", bytes.length, Instant.EPOCH);
        var service = new ResponderAttachmentService(null, null, "") {
            @Override
            AttachmentDownload open(String loginName, UUID requestedIncident, UUID requestedAttachment) {
                assertEquals("responder", loginName);
                assertEquals(incidentId, requestedIncident);
                assertEquals(attachmentId, requestedAttachment);
                return new AttachmentDownload(metadata, new ByteArrayInputStream(bytes));
            }
        };

        var response = new ResponderAttachmentController(service).download(
                () -> "responder", incidentId, attachmentId);
        var body = new ByteArrayOutputStream();
        response.getBody().writeTo(body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals("attachment; filename=\"attachment.png\"", response.getHeaders().getFirst("Content-Disposition"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertArrayEquals(bytes, body.toByteArray());
    }

    @Test
    void forbiddenAndMissingAttachmentUseTheSameSafeResponse() {
        var controller = new ResponderAttachmentController(new ResponderAttachmentService(null, null, ""));

        var response = controller.denied();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("Resource unavailable", ((java.util.Map<?, ?>) response.getBody()).get("message"));
    }

    @Test
    void streamStopsWhenObjectBytesExceedValidatedMetadataLength() {
        UUID incidentId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        var metadata = new ResponderAttachmentService.AttachmentSummary(
                attachmentId, "image/jpeg", 2, Instant.EPOCH);
        var service = new ResponderAttachmentService(null, null, "") {
            @Override
            AttachmentDownload open(String loginName, UUID requestedIncident, UUID requestedAttachment) {
                return new AttachmentDownload(metadata, new ByteArrayInputStream(new byte[] { 1, 2, 3 }));
            }
        };
        var response = new ResponderAttachmentController(service).download(
                () -> "responder", incidentId, attachmentId);
        var body = new ByteArrayOutputStream();

        org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class,
                () -> response.getBody().writeTo(body));

        assertEquals(0, body.size());
    }
}
