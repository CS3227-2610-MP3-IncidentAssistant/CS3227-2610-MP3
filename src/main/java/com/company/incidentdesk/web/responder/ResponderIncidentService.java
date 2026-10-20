package com.company.incidentdesk.web.responder;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.domain.incident.IncidentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** Reads the authenticated Responder's server-authorized incident queue. */
@Service
@ConditionalOnBean(DataSource.class)
final class ResponderIncidentService {
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 200;
    private static final Set<String> ALLOWED_CATEGORIES = Arrays.stream(IncidentCategory.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            IncidentStatus.SUBMITTED.name(), IncidentStatus.ASSIGNED.name());

    private final ResponderIncidentGateway incidents;

    ResponderIncidentService(ResponderIncidentGateway incidents) {
        this.incidents = Objects.requireNonNull(incidents, "incidents");
    }

    IncidentPage queue(String loginName, int page, int size) {
        return queue(loginName, "", List.of(), List.of(), Assignment.ALL, page, size);
    }

    IncidentPage queue(
            String loginName, String search, List<String> categories, List<String> statuses,
            Assignment assignment, int page, int size) {
        String trustedLoginName = requireLoginName(loginName);
        ListCriteria criteria = new ListCriteria(search, categories, statuses, assignment, page, size);
        return incidents.findQueue(trustedLoginName, criteria).orElseThrow(ResponderAccessDeniedException::new);
    }

    ClaimResult claim(String loginName, UUID incidentId) {
        String trustedLoginName = requireLoginName(loginName);
        Objects.requireNonNull(incidentId, "incidentId");
        return incidents.claim(trustedLoginName, incidentId);
    }

    IncidentDetail detail(String loginName, UUID incidentId) {
        String trustedLoginName = requireLoginName(loginName);
        Objects.requireNonNull(incidentId, "incidentId");
        return incidents.findDetail(trustedLoginName, incidentId).orElseThrow(ResponderAccessDeniedException::new);
    }

    private static String requireLoginName(String loginName) {
        if (loginName == null || loginName.isBlank()) {
            throw new ResponderAccessDeniedException();
        }
        return loginName;
    }

    record ListCriteria(String search, List<String> categories, List<String> statuses,
            Assignment assignment, int page, int size) {
        ListCriteria {
            search = search == null ? "" : search.strip();
            if (search.length() > MAX_SEARCH_LENGTH) throw new InvalidQueueFilterException();
            categories = normalizeFilter(categories, ALLOWED_CATEGORIES);
            statuses = normalizeFilter(statuses, ALLOWED_STATUSES);
            assignment = assignment == null ? Assignment.ALL : assignment;
            page = Math.max(0, page);
            size = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        }

        private static List<String> normalizeFilter(List<String> values, Set<String> allowed) {
            List<String> normalized = values == null ? List.of() : values.stream().distinct().sorted().toList();
            if (!allowed.containsAll(normalized)) throw new InvalidQueueFilterException();
            return normalized;
        }

        long offset() {
            return Math.multiplyExact((long) page, size);
        }
    }

    enum Assignment { ALL, ASSIGNED, UNASSIGNED }

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

    record ClaimResult(boolean claimed) {
    }

    /** Core persisted fields only; excludes raw reporter ID and content not wired to web storage. */
    record IncidentDetail(
            UUID id,
            String title,
            String description,
            String reporterLabel,
            String category,
            String status,
            Instant submittedAt,
            Instant queueEnteredAt) {
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

    static final class InvalidQueueFilterException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
