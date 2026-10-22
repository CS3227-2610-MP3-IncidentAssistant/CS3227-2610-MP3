package com.company.incidentdesk.application.account;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.company.incidentdesk.application.audit.AuditEventFactory;
import com.company.incidentdesk.application.authorization.AccountAuthorizationPolicy;
import com.company.incidentdesk.application.event.ApplicationEvent;
import com.company.incidentdesk.application.result.ApplicationError;
import com.company.incidentdesk.application.result.ApplicationErrorCode;
import com.company.incidentdesk.application.result.ApplicationResult;
import com.company.incidentdesk.application.session.SessionProvider;
import com.company.incidentdesk.application.validation.ValidationError;
import com.company.incidentdesk.application.validation.ValidationErrorCode;
import com.company.incidentdesk.application.validation.ValidationField;
import com.company.incidentdesk.application.validation.ValidationResult;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.PromotionRequestStatus;
import com.company.incidentdesk.domain.account.ResponderAccess;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.audit.AuditAction;
import com.company.incidentdesk.domain.audit.AuditActorVisibility;
import com.company.incidentdesk.domain.audit.AuditChange;
import com.company.incidentdesk.domain.audit.AuditChangeField;
import com.company.incidentdesk.domain.audit.AuditOutcome;
import com.company.incidentdesk.domain.audit.AuditTarget;
import com.company.incidentdesk.domain.audit.AuditTargetType;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.persistence.AccountRepository;
import com.company.incidentdesk.persistence.AuditedMutation;
import com.company.incidentdesk.persistence.RepositoryException;
import com.company.incidentdesk.persistence.StorageFailureCode;

/** Authorized promotion-request submission, decision, and responder-access workflow. */
public final class PromotionRequestService {
    private final SessionProvider sessions;
    private final AccountAuthorizationPolicy authorization;
    private final AccountRepository accounts;
    private final PromotionWorkflowStore store;
    private final AuditEventFactory auditEvents;
    private final Clock clock;
    private final Supplier<PromotionRequestId> identifiers;
    private final Consumer<ApplicationEvent> eventPublisher;

    public PromotionRequestService(SessionProvider sessions, AccountAuthorizationPolicy authorization,
            AccountRepository accounts, PromotionWorkflowStore store, AuditEventFactory auditEvents,
            Clock clock, Supplier<PromotionRequestId> identifiers, Consumer<ApplicationEvent> eventPublisher) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.store = Objects.requireNonNull(store, "store");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    /** Submits a request for the current reporter with one or more desired categories. */
    public ApplicationResult<ResponderPromotionRequest> submit(
            Set<IncidentCategory> requestedCategories, Optional<String> comments) {
        Optional<Account> actor = sessions.currentAccount();
        if (actor.isEmpty() || !authorization.authorizePromotionRequest(actor.orElseThrow().id()).isAllowed()) {
            return unavailable();
        }
        return submitAs(actor.orElseThrow(), requestedCategories, comments);
    }

    /** Submits a request for the enabled Reporter resolved from the authenticated web principal. */
    public ApplicationResult<ResponderPromotionRequest> submitAs(
            Account actor, Set<IncidentCategory> requestedCategories, Optional<String> comments) {
        Objects.requireNonNull(requestedCategories, "requestedCategories");
        Objects.requireNonNull(comments, "comments");
        Optional<Account> currentReporter = currentAccount(actor, Role.REPORTER);
        if (currentReporter.isEmpty()) {
            return unavailable();
        }
        if (requestedCategories.isEmpty()) {
            return ApplicationResult.failure(ApplicationError.validation(ValidationResult.invalid(
                    new ValidationError(new ValidationField("requestedCategories"), ValidationErrorCode.REQUIRED))));
        }
        if (comments.filter(value -> value.length() > 2_000).isPresent()) {
            return ApplicationResult.failure(ApplicationError.validation(ValidationResult.invalid(
                    new ValidationError(new ValidationField("comments"), ValidationErrorCode.OUT_OF_RANGE))));
        }
        Account reporter = currentReporter.orElseThrow();
        if (store.hasPendingPromotionRequest(reporter.id())) {
            return ApplicationResult.failure(ApplicationError.of(ApplicationErrorCode.INVALID_STATE));
        }
        ResponderPromotionRequest request = ResponderPromotionRequest.pending(
                Objects.requireNonNull(identifiers.get(), "generated promotion request identifier"), reporter.id(),
                EnumSet.copyOf(requestedCategories), comments, clock.instant());
        var audit = auditEvents.create(reporter, AuditActorVisibility.STANDARD,
                AuditAction.RESPONDER_PROMOTION_REQUESTED,
                new AuditTarget(AuditTargetType.PROMOTION_REQUEST, request.id().value().toString()),
                AuditOutcome.SUCCESS,
                List.of(AuditChange.added(AuditChangeField.RESPONDER_CATEGORIES, categories(requestedCategories)),
                        AuditChange.added(AuditChangeField.PROMOTION_STATUS, PromotionRequestStatus.PENDING.name())),
                Optional.empty());
        try {
            store.commitPromotion(new AuditedMutation<>(
                    new PromotionWorkflowMutation(Optional.of(request), Optional.empty()), audit));
        } catch (RepositoryException exception) {
            return persistenceFailure(exception);
        }
        eventPublisher.accept(new PromotionRequestedEvent(request.id(), reporter.id()));
        return ApplicationResult.success(request);
    }

    /** Approves or rejects a pending request as the current administrator. */
    public ApplicationResult<ResponderPromotionRequest> decide(
            PromotionRequestId requestId,
            boolean approve,
            Set<IncidentCategory> approvedCategories) {
        Optional<Account> actor = sessions.currentAccount();
        if (actor.isEmpty() || !authorization.authorizePromotionDecision().isAllowed()) {
            return unavailable();
        }
        return decideAs(actor.orElseThrow(), requestId, approve, approvedCategories);
    }

    /** Decides a request using the selected categories supplied by the authorized Administrator. */
    public ApplicationResult<ResponderPromotionRequest> decideAs(
            Account actor,
            PromotionRequestId requestId,
            boolean approve,
            Set<IncidentCategory> approvedCategories) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(approvedCategories, "approvedCategories");
        Optional<Account> currentAdministrator = currentAccount(actor, Role.ADMINISTRATOR);
        if (currentAdministrator.isEmpty()) {
            return unavailable();
        }
        Optional<ResponderPromotionRequest> located = store.findPromotionRequest(requestId);
        if (located.isEmpty()) {
            return unavailable();
        }
        ResponderPromotionRequest current = located.orElseThrow();
        if (current.status() != PromotionRequestStatus.PENDING) {
            return ApplicationResult.failure(ApplicationError.of(ApplicationErrorCode.INVALID_STATE));
        }
        Optional<Account> requester = accounts.findById(current.requesterId())
                .filter(Account::isEnabled).filter(account -> account.role() == Role.REPORTER);
        if (requester.isEmpty()) {
            return ApplicationResult.failure(ApplicationError.of(ApplicationErrorCode.INVALID_STATE));
        }

        if (approve && (approvedCategories.isEmpty()
                || !current.requestedCategories().containsAll(approvedCategories))) {
            return invalidApprovedCategories();
        }
        if (!approve && !approvedCategories.isEmpty()) {
            return invalidApprovedCategories();
        }

        Account administrator = currentAdministrator.orElseThrow();
        ResponderPromotionRequest decided = current.decide(
                approve, approvedCategories, administrator.id(), clock.instant());
        Optional<Account> updatedAccount = approve
                ? Optional.of(new Account(requester.orElseThrow().id(), requester.orElseThrow().loginName(),
                        Role.RESPONDER, requester.orElseThrow().status(),
                        ResponderAccess.to(approvedCategories)))
                : Optional.empty();
        AuditAction action = approve ? AuditAction.RESPONDER_PROMOTION_APPROVED
                : AuditAction.RESPONDER_PROMOTION_REJECTED;
        List<AuditChange> changes = approve
                ? List.of(AuditChange.changed(AuditChangeField.PROMOTION_STATUS, current.status().name(), decided.status().name()),
                        AuditChange.changed(AuditChangeField.ROLE, Role.REPORTER.name(), Role.RESPONDER.name()),
                        AuditChange.added(AuditChangeField.RESPONDER_CATEGORIES, categories(approvedCategories)))
                : List.of(AuditChange.changed(AuditChangeField.PROMOTION_STATUS,
                        current.status().name(), decided.status().name()));
        var audit = auditEvents.create(administrator, AuditActorVisibility.STANDARD, action,
                new AuditTarget(AuditTargetType.PROMOTION_REQUEST, requestId.value().toString()),
                AuditOutcome.SUCCESS, changes, Optional.empty());
        try {
            store.commitPromotion(new AuditedMutation<>(
                    new PromotionWorkflowMutation(Optional.of(decided), updatedAccount), audit));
        } catch (RepositoryException exception) {
            return persistenceFailure(exception);
        }
        eventPublisher.accept(new PromotionDecisionEvent(current.requesterId(), administrator.id(), approve));
        return ApplicationResult.success(decided);
    }

    /** Returns retained request history visible to the current account. */
    public List<ResponderPromotionRequest> visibleHistory() {
        return sessions.currentAccount().map(this::visibleHistoryAs).orElseGet(List::of);
    }

    /** Returns retained request history visible to the persisted authenticated account. */
    public List<ResponderPromotionRequest> visibleHistoryAs(Account actor) {
        Optional<Account> currentActor = currentAccount(actor, null);
        if (currentActor.isEmpty()) return List.of();
        Account current = currentActor.orElseThrow();
        return current.role() == Role.ADMINISTRATOR
                ? store.findPromotionRequests()
                : store.findPromotionRequestsByRequester(current.id());
    }

    /** Returns one bounded history page for an enabled Reporter. */
    public List<ResponderPromotionRequest> reporterHistoryAs(Account actor, long offset, int limit) {
        if (offset < 0 || limit < 1) return List.of();
        Optional<Account> reporter = currentAccount(actor, Role.REPORTER);
        return reporter.map(account -> store.findPromotionRequestsByRequester(account.id(), offset, limit))
                .orElseGet(List::of);
    }

    /** Returns the retained history count for an enabled Reporter. */
    public long countReporterHistoryAs(Account actor) {
        return currentAccount(actor, Role.REPORTER)
                .map(account -> store.countPromotionRequestsByRequester(account.id()))
                .orElse(0L);
    }

    /** Returns one bounded page of pending requests only to an enabled Administrator. */
    public List<ResponderPromotionRequest> pendingRequestsAs(Account actor, long offset, int limit) {
        if (offset < 0 || limit < 1 || currentAccount(actor, Role.ADMINISTRATOR).isEmpty()) {
            return List.of();
        }
        return store.findPendingPromotionRequests(offset, limit);
    }

    /** Returns a promotion record only to an enabled Administrator. */
    public Optional<ResponderPromotionRequest> findRequestAs(Account actor, PromotionRequestId requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (currentAccount(actor, Role.ADMINISTRATOR).isEmpty()) {
            return Optional.empty();
        }
        return store.findPromotionRequest(requestId);
    }

    /** Returns the pending request count only to an enabled Administrator. */
    public long countPendingRequestsAs(Account actor) {
        return currentAccount(actor, Role.ADMINISTRATOR).isPresent()
                ? store.countPendingPromotionRequests() : 0;
    }

    private Optional<Account> currentAccount(Account actor, Role requiredRole) {
        if (actor == null || !actor.isEnabled()) return Optional.empty();
        return accounts.findById(actor.id())
                .filter(Account::isEnabled)
                .filter(current -> current.loginName().equals(actor.loginName()))
                .filter(current -> requiredRole == null || current.role() == requiredRole);
    }

    private static <T> ApplicationResult<T> invalidApprovedCategories() {
        return ApplicationResult.failure(ApplicationError.validation(ValidationResult.invalid(
                new ValidationError(new ValidationField("approvedCategories"), ValidationErrorCode.OUT_OF_RANGE))));
    }

    private static String categories(Set<IncidentCategory> categories) {
        return categories.stream().sorted().map(Enum::name).reduce((left, right) -> left + "," + right).orElse("");
    }

    private static <T> ApplicationResult<T> unavailable() {
        return ApplicationResult.failure(ApplicationError.of(ApplicationErrorCode.RESOURCE_UNAVAILABLE));
    }

    private static <T> ApplicationResult<T> persistenceFailure(RepositoryException exception) {
        ApplicationErrorCode code = switch (exception.code()) {
        case ALREADY_EXISTS -> ApplicationErrorCode.INVALID_STATE;
        case CORRUPT_DATA, UNSUPPORTED_SCHEMA -> ApplicationErrorCode.CORRUPT_DATA;
        case NOT_FOUND -> ApplicationErrorCode.RESOURCE_UNAVAILABLE;
        case STORAGE_UNAVAILABLE, WRITER_ALREADY_ACTIVE -> ApplicationErrorCode.PERSISTENCE_FAILURE;
        };
        return ApplicationResult.failure(ApplicationError.of(code));
    }
}
