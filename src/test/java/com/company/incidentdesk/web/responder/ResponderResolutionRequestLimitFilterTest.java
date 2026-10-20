package com.company.incidentdesk.web.responder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ResponderResolutionRequestLimitFilterTest {
    private final ResponderResolutionRequestLimitFilter filter = new ResponderResolutionRequestLimitFilter();

    @Test
    void resolvesSmallJsonWithOriginalBodyAndNoStoreLimitMetadata() throws Exception {
        String json = "{\"requestId\":\"50000000-0000-0000-0000-000000000099\",\"remarks\":\"fixed\"}";
        MockHttpServletRequest request = request(json);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
            continued.set(true);
            assertEquals(json, new String(wrappedRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });

        assertTrue(continued.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsOversizedChunkedJsonBeforePassingItToTheController() throws Exception {
        MockHttpServletRequest request = request("x".repeat(ResponderResolutionRequestLimitFilter.MAX_BODY_BYTES + 1), true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> continued.set(true));

        assertFalse(continued.get());
        assertEquals(413, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getContentAsString().contains("REQUEST_TOO_LARGE"));
        assertFalse(response.getContentAsString().contains("xxx"));
    }

    private static MockHttpServletRequest request(String body) {
        return request(body, false);
    }

    private static MockHttpServletRequest request(String body, boolean unknownLength) {
        String path = "/api/v1/responder/incidents/50000000-0000-0000-0000-000000000001/resolve";
        MockHttpServletRequest request = unknownLength
                ? new MockHttpServletRequest("POST", path) {
                    @Override
                    public int getContentLength() {
                        return -1;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                }
                : new MockHttpServletRequest("POST", path);
        request.setServletPath(request.getRequestURI());
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
