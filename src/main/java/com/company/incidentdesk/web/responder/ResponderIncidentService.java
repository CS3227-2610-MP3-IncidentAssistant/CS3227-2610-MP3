package com.company.incidentdesk.web.responder;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** Reads the authenticated Responder's server-authorized incident queue. */
@Service
@ConditionalOnBean(DataSource.class)
final class ResponderIncidentService {
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final ResponderIncidentGateway incidents;

    ResponderIncidentService(ResponderIncidentGateway incidents) {
        this.incidents = Objects.requireNonNull(incidents, "incidents");
    }

    IncidentPage queue(String loginName, int page, int size) {
        String trustedLoginName = requireLoginName(loginName);
        ListCriteria criteria = new ListCriteria(page, size);
        return incidents.findQueue(trustedLoginName, criteria).orElseThrow(ResponderAccessDeniedException::new);
    }

    private static String requireLoginName(String loginName) {
        if (loginName == null || loginName.isBlank()) {
            throw new ResponderAccessDeniedException();
        }
        return loginName;
    }

    record ListCriteria(int page, int size) {
        ListCriteria {
            page = Math.max(0, page);
            size = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        }

        long offset() {
            return Math.multiplyExact((long) page, size);
        }
    }

    record IncidentPage(
            List<IncidentRow> incidents,
            int page,
            int size,
            long totalElements,
            long totalPages,
            boolean categoriesConfigured) {
        IncidentPage {
            incidents = List.copyOf(incidents);
        }
    }

    /** Deliberately excludes reporter, assignee, anonymity, and storage identifiers. */
    record IncidentRow(
            java.util.UUID id,
            String title,
            String category,
            String status,
            java.time.Instant submittedAt,
            java.time.Instant queueEnteredAt) {
    }

    static final class ResponderAccessDeniedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static final class ResponderDataAccessException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ResponderDataAccessException(Throwable cause) {
            super(cause);
        }
    }
}
