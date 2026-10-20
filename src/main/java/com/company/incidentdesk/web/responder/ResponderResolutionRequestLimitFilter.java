package com.company.incidentdesk.web.responder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import javax.sql.DataSource;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds Responder resolution JSON before Jackson allocates the request object. */
@Component
@ConditionalOnBean(DataSource.class)
@Order(Ordered.LOWEST_PRECEDENCE)
final class ResponderResolutionRequestLimitFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 32 * 1024;
    private static final String RESOLVE_PATH = "/api/v1/responder/incidents/";
    private static final byte[] TOO_LARGE = "{\"code\":\"REQUEST_TOO_LARGE\",\"message\":\"Request body is too large.\"}"
            .getBytes(StandardCharsets.UTF_8);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !request.getServletPath().startsWith(RESOLVE_PATH)
                || !request.getServletPath().endsWith("/resolve");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            jakarta.servlet.FilterChain filterChain) throws IOException, jakarta.servlet.ServletException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response);
            return;
        }

        byte[] body = readBounded(request.getInputStream());
        if (body.length > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int remaining = MAX_BODY_BYTES + 1;
        while (remaining > 0) {
            int count = input.read(chunk, 0, Math.min(chunk.length, remaining));
            if (count < 0) break;
            buffer.write(chunk, 0, count);
            remaining -= count;
        }
        return buffer.toByteArray();
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(TOO_LARGE.length);
        response.getOutputStream().write(TOO_LARGE);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Asynchronous request reading is unsupported");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new java.io.BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
