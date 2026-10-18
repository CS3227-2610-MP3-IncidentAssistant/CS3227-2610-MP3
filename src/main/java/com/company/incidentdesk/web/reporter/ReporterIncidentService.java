package com.company.incidentdesk.web.reporter;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.incident.Incident;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.domain.incident.IncidentId;
import com.company.incidentdesk.domain.incident.IncidentLifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** Validates Reporter input and applies the canonical direct-submission lifecycle. */
@Service
@ConditionalOnBean(DataSource.class)
final class ReporterIncidentService {
    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_DESCRIPTION_LENGTH = 10_000;

    private final ReporterIncidentGateway incidents;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    @Autowired
    ReporterIncidentService(ReporterIncidentGateway incidents) {
        this(incidents, Clock.systemUTC(), UUID::randomUUID);
    }

    ReporterIncidentService(ReporterIncidentGateway incidents, Clock clock, Supplier<UUID> identifiers) {
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    IncidentPage findOwned(String loginName, ListCriteria criteria) {
        String trustedLoginName = requiredLoginName(loginName);
        Objects.requireNonNull(criteria, "criteria");
        if (incidents.findEnabledReporter(trustedLoginName).isEmpty()) {
            throw new ReporterAccessDeniedException();
        }
        return incidents.findOwned(trustedLoginName, criteria);
    }

    SubmissionResult submit(String loginName, SubmissionCommand command) {
        String trustedLoginName = requiredLoginName(loginName);
        Objects.requireNonNull(command, "command");
        Map<String, String> validation = validate(command);
        if (!validation.isEmpty()) {
            return SubmissionResult.validation(validation);
        }

        AccountId reporterId = incidents.findEnabledReporter(trustedLoginName).orElse(null);
        if (reporterId == null) {
            return SubmissionResult.denied();
        }

        Incident incident = new IncidentLifecycle(clock).submit(
                new IncidentId(identifiers.get()),
                reporterId,
                command.title(),
                command.description(),
                IncidentCategory.valueOf(command.category()),
                command.anonymous());
        return incidents.submit(trustedLoginName, command.submissionKey(), incident, identifiers.get());
    }

    private static Map<String, String> validate(SubmissionCommand command) {
        LinkedHashMap<String, String> errors = new LinkedHashMap<>();
        if (command.title() == null || command.title().isBlank()) {
            errors.put("title", "Title is required.");
        } else if (command.title().strip().length() > MAX_TITLE_LENGTH) {
            errors.put("title", "Title must be 200 characters or fewer.");
        }
        if (command.description() == null || command.description().isBlank()) {
            errors.put("description", "Description is required.");
        } else if (command.description().length() > MAX_DESCRIPTION_LENGTH) {
            errors.put("description", "Description must be 10000 characters or fewer.");
        }
        if (command.category() == null || !isCategory(command.category())) {
            errors.put("category", "Select a valid category.");
        }
        if (command.submissionKey() == null) {
            errors.put("submissionKey", "Submission key is required.");
        }
        return Map.copyOf(errors);
    }

    private static boolean isCategory(String value) {
        try {
            IncidentCategory.valueOf(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String requiredLoginName(String loginName) {
        if (loginName == null || loginName.isBlank()) {
            throw new IllegalArgumentException("authenticated login name is required");
        }
        return loginName;
    }

    record SubmissionCommand(
            String title,
            String description,
            String category,
            boolean anonymous,
            UUID submissionKey) {
    }

    record ListCriteria(String search, int page, int size) {
        ListCriteria {
            search = search == null ? "" : search.strip();
            page = Math.max(0, page);
            size = size <= 0 ? 25 : Math.min(size, 100);
        }
    }

    record IncidentPage(List<IncidentRow> incidents, int page, int size, long totalElements, long totalPages) {
        IncidentPage {
            incidents = List.copyOf(incidents);
        }
    }

    record IncidentRow(
            UUID id,
            String title,
            String category,
            String status,
            boolean anonymous,
            Instant createdAt,
            Instant submittedAt,
            String assigneeLabel,
            long version) {
    }

    record SubmissionResult(Status status, IncidentRow incident, Map<String, String> fieldErrors) {
        SubmissionResult {
            fieldErrors = Map.copyOf(fieldErrors);
        }

        static SubmissionResult created(IncidentRow incident) {
            return new SubmissionResult(Status.CREATED, incident, Map.of());
        }

        static SubmissionResult duplicate(IncidentRow incident) {
            return new SubmissionResult(Status.DUPLICATE, incident, Map.of());
        }

        static SubmissionResult validation(Map<String, String> errors) {
            return new SubmissionResult(Status.VALIDATION_FAILED, null, errors);
        }

        static SubmissionResult denied() {
            return new SubmissionResult(Status.DENIED, null, Map.of());
        }

        static SubmissionResult unavailable() {
            return new SubmissionResult(Status.UNAVAILABLE, null, Map.of());
        }
    }

    enum Status { CREATED, DUPLICATE, VALIDATION_FAILED, DENIED, UNAVAILABLE }

    static final class ReporterAccessDeniedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
