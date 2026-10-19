package com.company.incidentdesk.web.admin;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.application.account.PromotionRequestService;
import com.company.incidentdesk.application.result.ApplicationError;
import com.company.incidentdesk.application.result.ApplicationResult;
import com.company.incidentdesk.application.validation.ValidationError;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.persistence.AccountRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for Reporter promotion requests and Administrator decisions. */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnBean(DataSource.class)
public final class PromotionRequestController {
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_COMMENT_LENGTH = 2_000;

    private final PromotionRequestService promotions;
    private final AccountRepository accounts;

    public PromotionRequestController(PromotionRequestService promotions, AccountRepository accounts) {
        this.promotions = promotions;
        this.accounts = accounts;
    }

    @GetMapping("/reporter/promotion-request")
    ResponseEntity<?> reporterHistory(Principal principal) {
        Optional<Account> reporter = currentPrincipal(principal, Role.REPORTER);
        if (reporter.isEmpty()) return unavailable();
        List<PromotionRequestRecord> requests = promotions.visibleHistoryAs(reporter.orElseThrow()).stream()
                .filter(request -> request.requesterId().equals(reporter.orElseThrow().id()))
                .map(PromotionRequestController::toReporterRecord)
                .toList();
        return noStore(HttpStatus.OK, new ReporterRequestHistory(requests));
    }

    @PostMapping("/reporter/promotion-request")
    ResponseEntity<?> submit(Principal principal, @RequestBody PromotionRequestBody body) {
        Optional<Account> reporter = currentPrincipal(principal, Role.REPORTER);
        if (reporter.isEmpty()) return unavailable();
        if (body == null) return validation(List.of("requestedCategories"));
        List<String> validation = new ArrayList<>();
        Set<IncidentCategory> requested = parseCategories(body.requestedCategories(), "requestedCategories", validation);
        if (body.comments() != null && body.comments().length() > MAX_COMMENT_LENGTH) {
            validation.add("comments");
        }
        if (!validation.isEmpty()) return validation(validation);

        ApplicationResult<ResponderPromotionRequest> result = promotions.submitAs(
                reporter.orElseThrow(), requested, Optional.ofNullable(body.comments()));
        if (!result.isSuccess()) return applicationError(result.error().orElseThrow());
        return noStore(HttpStatus.CREATED, toReporterRecord(result.value().orElseThrow()));
    }

    @GetMapping("/admin/promotion-requests")
    ResponseEntity<?> pendingRequests(
            Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Optional<Account> administrator = currentPrincipal(principal, Role.ADMINISTRATOR);
        if (administrator.isEmpty()) return unavailable();
        int boundedPage = Math.max(0, page);
        int boundedSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long offset = (long) boundedPage * boundedSize;
        List<AdministratorPromotionRequestRecord> requests = promotions
                .pendingRequestsAs(administrator.orElseThrow(), offset, boundedSize).stream()
                .map(request -> toAdministratorRecord(request, requesterLabel(request)))
                .toList();
        long total = promotions.countPendingRequestsAs(administrator.orElseThrow());
        long totalPages = Math.max(1, (total + boundedSize - 1) / boundedSize);
        return noStore(HttpStatus.OK,
                new AdministratorRequestPage(requests, boundedPage, boundedSize, total, totalPages));
    }

    @GetMapping("/admin/promotion-requests/{requestId}")
    ResponseEntity<?> requestDetail(Principal principal, @PathVariable UUID requestId) {
        Optional<Account> administrator = currentPrincipal(principal, Role.ADMINISTRATOR);
        if (administrator.isEmpty()) return unavailable();
        Optional<ResponderPromotionRequest> request = promotions.findRequestAs(
                administrator.orElseThrow(), new PromotionRequestId(requestId));
        if (request.isEmpty()) return unavailable();
        return noStore(HttpStatus.OK,
                toAdministratorRecord(request.orElseThrow(), requesterLabel(request.orElseThrow())));
    }

    @PostMapping("/admin/promotion-requests/{requestId}/decision")
    ResponseEntity<?> decide(
            Principal principal,
            @PathVariable UUID requestId,
            @RequestBody DecisionBody body) {
        Optional<Account> administrator = currentPrincipal(principal, Role.ADMINISTRATOR);
        if (administrator.isEmpty()) return unavailable();
        if (body == null) return validation(List.of("decision"));
        if (body.decision() == null || !Set.of("APPROVE", "REJECT").contains(body.decision())) {
            return validation(List.of("decision"));
        }

        boolean approve = "APPROVE".equals(body.decision());
        List<String> validation = new ArrayList<>();
        Set<IncidentCategory> approved = parseCategories(body.approvedCategories(), "approvedCategories", validation);
        if (approve && (body.approvedCategories() == null || approved.isEmpty())) {
            validation.add("approvedCategories");
        }
        if (!approve && !approved.isEmpty()) validation.add("approvedCategories");
        if (!validation.isEmpty()) return validation(validation);

        ApplicationResult<ResponderPromotionRequest> result = promotions.decideAs(
                administrator.orElseThrow(), new PromotionRequestId(requestId), approve, approved);
        if (!result.isSuccess()) return applicationError(result.error().orElseThrow());
        ResponderPromotionRequest decided = result.value().orElseThrow();
        return noStore(HttpStatus.OK, toAdministratorRecord(decided, requesterLabel(decided)));
    }

    private Optional<Account> currentPrincipal(Principal principal, Role requiredRole) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) return Optional.empty();
        return accounts.findByLoginName(principal.getName())
                .filter(Account::isEnabled)
                .filter(account -> account.role() == requiredRole);
    }

    private String requesterLabel(ResponderPromotionRequest request) {
        return accounts.findById(request.requesterId())
                .map(Account::loginName)
                .orElse("Unavailable account");
    }

    private static PromotionRequestRecord toReporterRecord(ResponderPromotionRequest request) {
        return commonRecord(request);
    }

    private static AdministratorPromotionRequestRecord toAdministratorRecord(
            ResponderPromotionRequest request, String requesterLabel) {
        PromotionRequestRecord common = commonRecord(request);
        return new AdministratorPromotionRequestRecord(
                common.requestId(), requesterLabel, common.requestedCategories(), common.approvedCategories(),
                common.comments(), common.requestedAt(), common.status(), common.decidedAt());
    }

    private static PromotionRequestRecord commonRecord(ResponderPromotionRequest request) {
        List<String> approved = request.approvedCategories()
                .map(PromotionRequestController::sortedCategoryNames)
                .orElse(null);
        return new PromotionRequestRecord(
                request.id().value(), sortedCategoryNames(request.requestedCategories()), approved,
                request.comments().orElse(null), request.requestedAt(), request.status().name(),
                request.decidedAt().orElse(null));
    }

    private static List<String> sortedCategoryNames(Set<IncidentCategory> categories) {
        return categories.stream().sorted().map(Enum::name).toList();
    }

    private static Set<IncidentCategory> parseCategories(
            List<String> values, String field, List<String> validation) {
        if (values == null) return Set.of();
        EnumSet<IncidentCategory> categories = EnumSet.noneOf(IncidentCategory.class);
        for (String value : values) {
            try {
                IncidentCategory category = IncidentCategory.valueOf(value);
                if (!categories.add(category)) validation.add(field);
            } catch (IllegalArgumentException | NullPointerException exception) {
                validation.add(field);
            }
        }
        return Set.copyOf(categories);
    }

    private static ResponseEntity<?> applicationError(ApplicationError error) {
        return switch (error.code()) {
        case VALIDATION -> validation(error.validation().errors().stream().map(ValidationError::field)
                .map(field -> field.value()).toList());
        case INVALID_STATE -> error(HttpStatus.CONFLICT, "INVALID_STATE", "The request has changed. Refresh and try again.");
        case RESOURCE_UNAVAILABLE, ACCESS_DENIED -> unavailable();
        case PERSISTENCE_FAILURE, CORRUPT_DATA -> error(HttpStatus.SERVICE_UNAVAILABLE,
                "PROMOTION_UNAVAILABLE", "Promotion data is temporarily unavailable. Try again.");
        };
    }

    private static ResponseEntity<?> validation(List<String> fields) {
        Map<String, String> errors = fields.stream().distinct()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(field -> field, ignored -> "Invalid value."));
        return noStore(HttpStatus.BAD_REQUEST, Map.of(
                "code", "VALIDATION_FAILED", "fieldErrors", errors));
    }

    private static ResponseEntity<?> unavailable() {
        return error(HttpStatus.NOT_FOUND, "UNAVAILABLE", "Resource unavailable");
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return noStore(status, Map.of("code", code, "message", message));
    }

    private static ResponseEntity<?> noStore(HttpStatus status, Object body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    record PromotionRequestBody(List<String> requestedCategories, String comments) {
    }

    record DecisionBody(String decision, List<String> approvedCategories) {
    }

    record PromotionRequestRecord(
            UUID requestId,
            List<String> requestedCategories,
            List<String> approvedCategories,
            String comments,
            Instant requestedAt,
            String status,
            Instant decidedAt) {
    }

    record AdministratorPromotionRequestRecord(
            UUID requestId,
            String requesterLabel,
            List<String> requestedCategories,
            List<String> approvedCategories,
            String comments,
            Instant requestedAt,
            String status,
            Instant decidedAt) {
    }

    record ReporterRequestHistory(List<PromotionRequestRecord> requests) {
    }

    record AdministratorRequestPage(
            List<AdministratorPromotionRequestRecord> requests,
            int page,
            int size,
            long totalElements,
            long totalPages) {
    }
}
