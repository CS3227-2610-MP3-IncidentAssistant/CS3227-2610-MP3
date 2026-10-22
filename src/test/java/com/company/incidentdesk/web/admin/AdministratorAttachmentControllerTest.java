package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class AdministratorAttachmentControllerTest {
    @Test
    void downloadUsesGenericNameSafeHeadersAndTrustedPrincipal() {
        UUID incidentId = UUID.fromString("74000000-0000-0000-0000-000000000001");
        UUID attachmentId = UUID.fromString("74000000-0000-0000-0000-000000000002");
        byte[] content = {1, 2, 3};
        AdministratorAttachmentService attachments = mock(AdministratorAttachmentService.class);
        var metadata = new AdministratorAttachmentService.AttachmentMetadata(
                attachmentId, "image/png", content.length);
        when(attachments.open("administrator", incidentId, attachmentId)).thenReturn(
                new AdministratorAttachmentService.AttachmentDownload(metadata, new ByteArrayInputStream(content)));

        var response = new AdministratorAttachmentController(attachments)
                .download(() -> "administrator", incidentId, attachmentId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertEquals("attachment; filename=\"attachment.png\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(content.length, response.getHeaders().getContentLength());
    }

    @Test
    void deniedAndStorageFailureResponsesDoNotRevealProviderOrIdentity() {
        var controller = new AdministratorAttachmentController(mock(AdministratorAttachmentService.class));

        assertEquals(HttpStatus.NOT_FOUND, controller.denied().getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.unavailable().getStatusCode());
        assertEquals("no-store", controller.denied().getHeaders().getCacheControl());
        assertEquals("no-store", controller.unavailable().getHeaders().getCacheControl());
    }
}
