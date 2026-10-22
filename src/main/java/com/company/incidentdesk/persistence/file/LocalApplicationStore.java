package com.company.incidentdesk.persistence.file;

import java.nio.file.Path;
import java.io.IOException;
import java.util.HashSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.company.incidentdesk.application.incident.IncidentMutation;
import com.company.incidentdesk.application.account.AccountRegistration;
import com.company.incidentdesk.application.account.AccountRegistrationStore;
import com.company.incidentdesk.application.account.AccountDeletionStore;
import com.company.incidentdesk.application.account.PasswordChangeStore;
import com.company.incidentdesk.application.account.PasswordResetStore;
import com.company.incidentdesk.application.account.PasswordCredential;
import com.company.incidentdesk.application.account.PromotionWorkflowMutation;
import com.company.incidentdesk.application.account.PromotionWorkflowStore;
import com.company.incidentdesk.application.attachment.AttachmentLimits;
import com.company.incidentdesk.application.attachment.AttachmentValidationException;
import com.company.incidentdesk.domain.attachment.AttachmentId;
import com.company.incidentdesk.domain.attachment.IncidentAttachment;
import com.company.incidentdesk.persistence.AttachmentStore;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.PromotionRequestStatus;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.domain.audit.AuditEvent;
import com.company.incidentdesk.domain.audit.AuditEventId;
import com.company.incidentdesk.domain.comment.IncidentComment;
import com.company.incidentdesk.domain.incident.Incident;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.domain.incident.IncidentId;
import com.company.incidentdesk.domain.incident.IncidentStatus;
import com.company.incidentdesk.domain.slo.SloTargetVersion;
import com.company.incidentdesk.domain.slo.SloTargetVersionId;
import com.company.incidentdesk.persistence.AccountRepository;
import com.company.incidentdesk.persistence.AssignmentState;
import com.company.incidentdesk.persistence.AuditQuery;
import com.company.incidentdesk.persistence.AuditRepository;
import com.company.incidentdesk.persistence.AuditSortDirection;
import com.company.incidentdesk.persistence.AuditedMutation;
import com.company.incidentdesk.persistence.IncidentQuery;
import com.company.incidentdesk.persistence.IncidentSearchCriteria;
import com.company.incidentdesk.persistence.IncidentSloClassifier;
import com.company.incidentdesk.persistence.IncidentSloState;
import com.company.incidentdesk.persistence.IncidentSort;
import com.company.incidentdesk.persistence.IncidentStore;
import com.company.incidentdesk.persistence.RepositoryException;
import com.company.incidentdesk.persistence.SloConfigurationStore;
import com.company.incidentdesk.persistence.StorageFailureCode;

/** Durable aggregate repository for accounts, incidents, comments, audits, and SLO configuration. */
public final class LocalApplicationStore
        implements AccountRepository, AccountRegistrationStore, AccountDeletionStore, PasswordChangeStore, PasswordResetStore,
        IncidentStore, AuditRepository, PromotionWorkflowStore,
        AutoCloseable {
    public static final String STATE_FILE_NAME = "incident-desk.dat";

    private final ApplicationProcessLock processLock;
    private final RecoverySafeFile<LocalApplicationState> file;
    private volatile IncidentSloClassifier sloClassifier;
    private final SloConfigurationStore sloConfigurationStore = new SloConfigurationStoreFacet();
    private LocalApplicationState state;
    private final AttachmentFiles attachmentFiles;
    private final AttachmentStore attachments = new AttachmentStoreFacet();

    public LocalApplicationStore(Path dataDirectory) {
        this(dataDirectory, incident -> IncidentSloState.NOT_APPLICABLE);
    }

    public LocalApplicationStore(Path dataDirectory, IncidentSloClassifier sloClassifier) {
        Path requiredDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.sloClassifier = Objects.requireNonNull(sloClassifier, "sloClassifier");
        processLock = ApplicationProcessLock.acquire(requiredDirectory);
        file = new RecoverySafeFile<>(requiredDirectory, STATE_FILE_NAME, new LocalApplicationStateCodec());
        try {
            state = file.load().orElseGet(LocalApplicationState::empty);
            attachmentFiles = new AttachmentFiles(requiredDirectory);
            if (attachmentFiles.hasPending()) {
                var referenced = new HashSet<>(state.attachments().keySet());
                file.loadBackup().ifPresent(backup -> referenced.addAll(backup.attachments().keySet()));
                attachmentFiles.recover(referenced);
            }
        } catch (IOException exception) {
            processLock.close();
            throw new RepositoryException(StorageFailureCode.STORAGE_UNAVAILABLE, "Attachment recovery unavailable", exception);
        } catch (RuntimeException exception) {
            processLock.close();
            throw exception;
        }
    }

    public static LocalApplicationStore openDefault() {
        return new LocalApplicationStore(ApplicationDataDirectory.resolve());
    }

    /** Installs the application SLO evaluator after the store's configuration facet is available. */
    public void setIncidentSloClassifier(IncidentSloClassifier classifier) {
        sloClassifier = Objects.requireNonNull(classifier, "classifier");
    }

    public static void recoverLastKnownGoodBackup(Path dataDirectory) {
        Path requiredDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        try (ApplicationProcessLock ignored = ApplicationProcessLock.acquire(requiredDirectory)) {
            RecoverySafeFile<LocalApplicationState> recoveryFile = new RecoverySafeFile<>(
                    requiredDirectory, STATE_FILE_NAME, new LocalApplicationStateCodec());
            recoveryFile.restoreBackup();
        }
    }

    @Override
    public synchronized void create(Account account) {
        Account required = Objects.requireNonNull(account, "account");
        Map<AccountId, Account> accounts = new LinkedHashMap<>(state.accounts());
        if (accounts.putIfAbsent(required.id(), required) != null
                || accounts.values().stream().anyMatch(existing -> existing != required
                        && existing.loginName().equals(required.loginName()))) {
            throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS, "account already exists");
        }
        persist(nextState(
                accounts, state.credentials(), state.incidents(), state.comments(), state.auditEvents(), state.sloTargetVersions()));
    }

    @Override
    public synchronized void update(Account account) {
        Account required = Objects.requireNonNull(account, "account");
        Map<AccountId, Account> accounts = new LinkedHashMap<>(state.accounts());
        if (accounts.replace(required.id(), required) == null) {
            throw new RepositoryException(StorageFailureCode.NOT_FOUND, "account does not exist");
        }
        boolean duplicateLogin = accounts.values().stream()
                .anyMatch(existing -> !existing.id().equals(required.id())
                        && existing.loginName().equals(required.loginName()));
        if (duplicateLogin) {
            throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS, "account login already exists");
        }
        persist(nextState(
                accounts, state.credentials(), state.incidents(), state.comments(), state.auditEvents(), state.sloTargetVersions()));
    }

    @Override
    public synchronized void register(AccountRegistration registration) {
        AccountRegistration required = Objects.requireNonNull(registration, "registration");
        if (state.accounts().containsKey(required.account().id()) || state.accounts().values().stream()
                .anyMatch(account -> account.loginName().equals(required.account().loginName()))) {
            throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS, "account already exists");
        }
        rejectDuplicateAudit(required.auditEvent());
        Map<AccountId, Account> accounts = new LinkedHashMap<>(state.accounts());
        accounts.put(required.account().id(), required.account());
        Map<AccountId, PasswordCredential> credentials = new LinkedHashMap<>(state.credentials());
        credentials.put(required.account().id(), required.credential());
        List<AuditEvent> audits = new ArrayList<>(state.auditEvents());
        audits.add(required.auditEvent());
        persist(nextState(accounts, credentials, state.incidents(), state.comments(), audits,
                state.sloTargetVersions()));
    }

    @Override
    public synchronized Optional<PasswordCredential> findCredential(AccountId accountId) {
        return Optional.ofNullable(state.credentials().get(Objects.requireNonNull(accountId, "accountId")));
    }

    @Override
    public synchronized void changePassword(AccountId accountId, PasswordCredential credential, AuditEvent auditEvent) {
        AccountId requiredId = Objects.requireNonNull(accountId, "accountId");
        PasswordCredential requiredCredential = Objects.requireNonNull(credential, "credential");
        AuditEvent requiredAudit = Objects.requireNonNull(auditEvent, "auditEvent");
        if (!state.accounts().containsKey(requiredId) || !state.credentials().containsKey(requiredId)) {
            throw new RepositoryException(StorageFailureCode.NOT_FOUND, "active account credential does not exist");
        }
        rejectDuplicateAudit(requiredAudit);
        Map<AccountId, PasswordCredential> credentials = new LinkedHashMap<>(state.credentials());
        credentials.put(requiredId, requiredCredential);
        List<AuditEvent> audits = new ArrayList<>(state.auditEvents());
        audits.add(requiredAudit);
        persist(new LocalApplicationState(state.accounts(), credentials, state.incidents(), state.comments(), audits,
                state.promotionRequests(), state.sloTargetVersions(), state.attachments(), state.schemaVersion()));
    }

    @Override
    public synchronized void resetPassword(AccountId accountId, PasswordCredential credential, AuditEvent auditEvent) {
        if (!Objects.requireNonNull(credential, "credential").temporary()) {
            throw new IllegalArgumentException("password reset requires a temporary credential");
        }
        changePassword(accountId, credential, auditEvent);
    }

    @Override
    public synchronized void delete(Account tombstone, AuditEvent auditEvent) {
        Account required = Objects.requireNonNull(tombstone, "tombstone");
        AuditEvent requiredAudit = Objects.requireNonNull(auditEvent, "auditEvent");
        if (!required.isDeleted()) {
            throw new IllegalArgumentException("account deletion requires a tombstone");
        }
        rejectDuplicateAudit(requiredAudit);
        Map<AccountId, Account> accounts = new LinkedHashMap<>(state.accounts());
        Account existing = accounts.get(required.id());
        if (existing == null || existing.isDeleted()) {
            throw new RepositoryException(StorageFailureCode.NOT_FOUND, "active account does not exist");
        }
        accounts.put(required.id(), required);
        Map<AccountId, PasswordCredential> credentials = new LinkedHashMap<>(state.credentials());
        credentials.remove(required.id());
        List<AuditEvent> audits = new ArrayList<>(state.auditEvents());
        audits.add(requiredAudit);
        persist(new LocalApplicationState(accounts, credentials, state.incidents(), state.comments(), audits,
                state.promotionRequests(), state.sloTargetVersions(), state.attachments(), state.schemaVersion()));
    }

    @Override
    public synchronized Optional<Account> findById(AccountId accountId) {
        return Optional.ofNullable(state.accounts().get(Objects.requireNonNull(accountId, "accountId")));
    }

    @Override
    public synchronized Optional<Account> findByLoginName(String loginName) {
        Objects.requireNonNull(loginName, "loginName");
        return state.accounts().values().stream().filter(account -> account.loginName().equals(loginName)).findFirst();
    }

    @Override
    public synchronized List<Account> findAll() {
        return state.accounts().values().stream()
                .sorted(Comparator.comparing(account -> account.id().value())).toList();
    }

    @Override
    public synchronized Optional<ResponderPromotionRequest> findPromotionRequest(PromotionRequestId requestId) {
        return Optional.ofNullable(state.promotionRequests().get(Objects.requireNonNull(requestId, "requestId")));
    }

    @Override
    public synchronized List<ResponderPromotionRequest> findPromotionRequests() {
        return state.promotionRequests().values().stream()
                .sorted(Comparator.comparing(ResponderPromotionRequest::requestedAt)
                        .thenComparing(request -> request.id().value()))
                .toList();
    }

    @Override
    public synchronized List<ResponderPromotionRequest> findPromotionRequestsByRequester(AccountId requesterId) {
        AccountId requiredRequesterId = Objects.requireNonNull(requesterId, "requesterId");
        return state.promotionRequests().values().stream()
                .filter(request -> request.requesterId().equals(requiredRequesterId))
                .sorted(Comparator.comparing(ResponderPromotionRequest::requestedAt)
                        .thenComparing(request -> request.id().value()))
                .toList();
    }

    @Override
    public synchronized List<ResponderPromotionRequest> findPromotionRequestsByRequester(
            AccountId requesterId, long offset, int limit) {
        return findPromotionRequestsByRequester(requesterId).stream()
                .sorted(Comparator.comparing(ResponderPromotionRequest::requestedAt).reversed()
                        .thenComparing(request -> request.id().value()))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized long countPromotionRequestsByRequester(AccountId requesterId) {
        return findPromotionRequestsByRequester(requesterId).size();
    }

    @Override
    public synchronized boolean hasPendingPromotionRequest(AccountId requesterId) {
        return state.promotionRequests().values().stream().anyMatch(request ->
                request.requesterId().equals(Objects.requireNonNull(requesterId, "requesterId"))
                        && request.status() == PromotionRequestStatus.PENDING);
    }

    @Override
    public synchronized void commitPromotion(AuditedMutation<PromotionWorkflowMutation> auditedMutation) {
        AuditedMutation<PromotionWorkflowMutation> required = Objects.requireNonNull(auditedMutation, "auditedMutation");
        rejectDuplicateAudit(required.auditEvent());
        Map<PromotionRequestId, ResponderPromotionRequest> requests = new LinkedHashMap<>(state.promotionRequests());
        required.nextState().request().ifPresent(request -> {
            ResponderPromotionRequest existing = requests.get(request.id());
            if (existing == null) {
                if (request.status() != PromotionRequestStatus.PENDING) {
                    throw new RepositoryException(StorageFailureCode.NOT_FOUND, "promotion request does not exist");
                }
                requests.put(request.id(), request);
            } else {
                if (existing.status() != PromotionRequestStatus.PENDING
                        || request.status() == PromotionRequestStatus.PENDING) {
                    throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS, "promotion request is not pending");
                }
                requests.put(request.id(), request);
            }
        });
        Map<AccountId, Account> accounts = new LinkedHashMap<>(state.accounts());
        required.nextState().updatedAccount().ifPresent(account -> {
            if (accounts.replace(account.id(), account) == null) {
                throw new RepositoryException(StorageFailureCode.NOT_FOUND, "account does not exist");
            }
        });
        List<AuditEvent> audits = new ArrayList<>(state.auditEvents());
        audits.add(required.auditEvent());
        persist(new LocalApplicationState(accounts, state.credentials(), state.incidents(), state.comments(), audits,
                requests, state.sloTargetVersions(), state.attachments(), LocalApplicationStateCodec.SCHEMA_VERSION));
    }

    @Override
    public synchronized void create(Incident incident) {
        replaceIncident(Objects.requireNonNull(incident, "incident"), false);
    }

    @Override
    public synchronized void update(Incident incident) {
        replaceIncident(Objects.requireNonNull(incident, "incident"), true);
    }

    @Override
    public synchronized Optional<Incident> findById(IncidentId incidentId) {
        return Optional.ofNullable(state.incidents().get(Objects.requireNonNull(incidentId, "incidentId")));
    }

    @Override
    public synchronized List<Incident> find(IncidentQuery query, IncidentSort sort) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(sort, "sort");
        return state.incidents().values().stream().filter(incident -> matches(query, incident))
                .sorted(sort.comparator()).toList();
    }

    @Override
    public synchronized List<Incident> find(IncidentQuery query, IncidentSearchCriteria criteria) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(criteria, "criteria");
        return state.incidents().values().stream().filter(incident -> matches(query, incident))
                .filter(incident -> matches(criteria, incident)).sorted(criteria.sort().comparator()).toList();
    }

    @Override
    public synchronized void commit(AuditedMutation<IncidentMutation> auditedMutation) {
        AuditedMutation<IncidentMutation> required = Objects.requireNonNull(auditedMutation, "auditedMutation");
        rejectDuplicateAudit(required.auditEvent());
        Map<IncidentId, Incident> incidents = new LinkedHashMap<>(state.incidents());
        List<IncidentComment> comments = new ArrayList<>(state.comments());
        apply(required.nextState(), incidents, comments);
        List<AuditEvent> audits = new ArrayList<>(state.auditEvents());
        audits.add(required.auditEvent());
        persist(nextState(state.accounts(), state.credentials(), incidents, comments, audits, state.sloTargetVersions()));
    }

    @Override
    public synchronized List<IncidentComment> findCommentsByIncidentId(IncidentId incidentId) {
        IncidentId required = Objects.requireNonNull(incidentId, "incidentId");
        return state.comments().stream().filter(comment -> comment.incidentId().equals(required)).toList();
    }

    @Override
    public synchronized void append(AuditEvent event) {
        AuditEvent required = Objects.requireNonNull(event, "event");
        rejectDuplicateAudit(required);
        List<AuditEvent> audits = new ArrayList<>(state.auditEvents());
        audits.add(required);
        persist(nextState(
                state.accounts(), state.credentials(), state.incidents(), state.comments(), audits, state.sloTargetVersions()));
    }

    /** Returns the SLO configuration facet of this aggregate store. */
    public SloConfigurationStore sloConfigurationStore() {
        return sloConfigurationStore;
    }

    public AttachmentStore attachmentStore() { return attachments; }

    @Override
    public synchronized Optional<AuditEvent> findById(AuditEventId eventId) {
        AuditEventId required = Objects.requireNonNull(eventId, "eventId");
        return state.auditEvents().stream().filter(event -> event.id().equals(required)).findFirst();
    }

    @Override
    public synchronized List<AuditEvent> find(AuditQuery query, AuditSortDirection direction) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(direction, "direction");
        Comparator<AuditEvent> order = AuditEvent.CHRONOLOGICAL_ORDER;
        if (direction == AuditSortDirection.NEWEST_FIRST) {
            order = order.reversed();
        }
        return state.auditEvents().stream().filter(event -> matches(query, event)).sorted(order).toList();
    }

    public synchronized void restoreLastKnownGoodBackup() {
        state = file.restoreBackup();
    }

    @Override
    public void close() {
        processLock.close();
    }

    private void replaceIncident(Incident incident, boolean update) {
        Map<IncidentId, Incident> incidents = new LinkedHashMap<>(state.incidents());
        Incident previous = update ? incidents.replace(incident.id(), incident) : incidents.putIfAbsent(incident.id(), incident);
        if (update && previous == null) {
            throw new RepositoryException(StorageFailureCode.NOT_FOUND, "incident does not exist");
        }
        if (!update && previous != null) {
            throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS, "incident already exists");
        }
        persist(nextState(
                state.accounts(), state.credentials(), incidents, state.comments(), state.auditEvents(), state.sloTargetVersions()));
    }

    private LocalApplicationState nextState(Map<AccountId, Account> accounts,
            Map<AccountId, PasswordCredential> credentials, Map<IncidentId, Incident> incidents,
            List<IncidentComment> comments, List<AuditEvent> audits,
            Map<SloTargetVersionId, SloTargetVersion> targets) {
        return new LocalApplicationState(accounts, credentials, incidents, comments, audits,
                state.promotionRequests(), targets, state.attachments(), state.schemaVersion());
    }

    private void persist(LocalApplicationState nextState) {
        LocalApplicationState currentSchemaState = nextState.schemaVersion() == LocalApplicationStateCodec.SCHEMA_VERSION
                ? nextState
                : new LocalApplicationState(nextState.accounts(), nextState.credentials(), nextState.incidents(),
                        nextState.comments(), nextState.auditEvents(), nextState.promotionRequests(),
                        nextState.sloTargetVersions(), nextState.attachments(),
                        LocalApplicationStateCodec.SCHEMA_VERSION);
        file.save(currentSchemaState);
        state = currentSchemaState;
    }

    private void rejectDuplicateAudit(AuditEvent event) {
        if (state.auditEvents().stream().anyMatch(existing -> existing.id().equals(event.id()))) {
            throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS, "audit event already exists");
        }
    }

    private static void apply(IncidentMutation mutation, Map<IncidentId, Incident> incidents,
            List<IncidentComment> comments) {
        Incident incident = mutation.incident();
        if (mutation.type() == IncidentMutation.Type.CREATE) {
            if (incidents.putIfAbsent(incident.id(), incident) != null) {
                throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS, "incident already exists");
            }
        } else if (incidents.replace(incident.id(), incident) == null) {
            throw new RepositoryException(StorageFailureCode.NOT_FOUND, "incident does not exist");
        }
        mutation.comment().ifPresent(comment -> {
            if (comments.stream().anyMatch(existing -> existing.id().equals(comment.id()))) {
                throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS, "comment already exists");
            }
            comments.add(comment);
        });
    }

    private static boolean matches(IncidentQuery query, Incident incident) {
        return switch (query.scope()) {
        case REPORTER_OWNED -> incident.reporterId().equals(query.accountId().orElseThrow());
        case RESPONDER_ELIGIBLE_UNASSIGNED -> incident.status() == IncidentStatus.SUBMITTED
                && incident.assigneeId().isEmpty() && query.categories().contains(incident.category());
        case RESPONDER_ASSIGNED -> incident.status() == IncidentStatus.ASSIGNED
                && incident.assigneeId().filter(query.accountId().orElseThrow()::equals).isPresent();
        case ADMINISTRATOR_ALL -> true;
        };
    }

    private boolean matches(IncidentSearchCriteria criteria, Incident incident) {
        String text = criteria.text().toLowerCase(Locale.ROOT);
        boolean textMatches = text.isEmpty() || incident.title().toLowerCase(Locale.ROOT).contains(text)
                || incident.description().toLowerCase(Locale.ROOT).contains(text)
                || incident.id().value().toString().contains(text);
        boolean rangeMatches = criteria.createdFrom().map(start -> !incident.createdAt().isBefore(start)).orElse(true)
                && criteria.createdThrough().map(end -> !incident.createdAt().isAfter(end)).orElse(true);
        return textMatches
                && (criteria.categories().isEmpty() || criteria.categories().contains(incident.category()))
                && (criteria.statuses().isEmpty() || criteria.statuses().contains(incident.status()))
                && matchesAssignment(criteria.assignmentState(), incident)
                && criteria.reporterId().map(id -> !incident.anonymous() && id.equals(incident.reporterId())).orElse(true)
                && criteria.responderId().map(id -> incident.assigneeId().filter(id::equals).isPresent()).orElse(true)
                && rangeMatches
                && (criteria.sloStates().isEmpty() || criteria.sloStates().contains(sloClassifier.classify(incident)));
    }

    private static boolean matchesAssignment(AssignmentState state, Incident incident) {
        return switch (state) {
        case ANY -> true;
        case ASSIGNED -> incident.assigneeId().isPresent();
        case UNASSIGNED -> incident.assigneeId().isEmpty();
        };
    }

    private static boolean matches(AuditQuery query, AuditEvent event) {
        Instant occurredAt = event.occurredAt();
        return query.fromInclusive().map(start -> !occurredAt.isBefore(start)).orElse(true)
                && query.toExclusive().map(end -> occurredAt.isBefore(end)).orElse(true)
                && query.actorId().map(id -> id.equals(event.actor().accountId())).orElse(true)
                && (query.actions().isEmpty() || query.actions().contains(event.action()))
                && query.target().map(event.target()::equals).orElse(true);
    }

    private final class AttachmentStoreFacet implements AttachmentStore {
        @Override
        public List<IncidentAttachment> list(IncidentId id) {
            synchronized (LocalApplicationStore.this) {
                return state.attachments().values().stream().filter(value -> value.incidentId().equals(id))
                        .sorted(Comparator.comparing(IncidentAttachment::createdAt)
                                .thenComparing(value -> value.id().value())).toList();
            }
        }

        @Override
        public Optional<IncidentAttachment> find(AttachmentId id) {
            synchronized (LocalApplicationStore.this) {
                return Optional.ofNullable(state.attachments().get(id));
            }
        }

        @Override
        public byte[] read(IncidentAttachment attachment) {
            synchronized (LocalApplicationStore.this) {
                if (!attachment.equals(state.attachments().get(attachment.id()))) {
                    throw new RepositoryException(StorageFailureCode.NOT_FOUND, "Attachment unavailable");
                }
                try {
                    return attachmentFiles.read(attachment);
                } catch (IOException exception) {
                    throw new RepositoryException(StorageFailureCode.STORAGE_UNAVAILABLE, "Attachment unavailable", exception);
                }
            }
        }

        @Override
        public void add(IncidentAttachment attachment, byte[] content, Incident expectedIncident, Account expectedActor,
                AttachmentLimits limits, AuditEvent audit, AuditEvent migrationAudit) {
            synchronized (LocalApplicationStore.this) {
                validateAddition(attachment, content, expectedIncident, expectedActor, limits);
                rejectDuplicateAudit(audit);
                Map<AttachmentId, IncidentAttachment> next = new LinkedHashMap<>(state.attachments());
                next.put(attachment.id(), attachment);
                List<AuditEvent> audits = new ArrayList<>(state.auditEvents());
                audits.add(audit);
                commitFiles(attachment, content, new LocalApplicationState(state.accounts(), state.credentials(),
                        state.incidents(), state.comments(), audits, state.promotionRequests(),
                        state.sloTargetVersions(), next, LocalApplicationStateCodec.SCHEMA_VERSION));
            }
        }

        private void validateAddition(IncidentAttachment attachment, byte[] content, Incident incident, Account actor,
                AttachmentLimits limits) {
            if (!incident.equals(state.incidents().get(attachment.incidentId()))
                    || !actor.equals(state.accounts().get(actor.id()))) {
                throw new RepositoryException(StorageFailureCode.NOT_FOUND, "Attachment unavailable");
            }
            if (state.attachments().containsKey(attachment.id())) {
                throw new RepositoryException(StorageFailureCode.ALREADY_EXISTS, "Attachment already exists");
            }
            List<IncidentAttachment> existing = list(attachment.incidentId());
            long used = existing.stream().mapToLong(IncidentAttachment::sizeBytes).sum();
            if (!attachment.type().isSupported() || content.length != attachment.sizeBytes() || existing.size() >= limits.count()
                    || attachment.sizeBytes() > limits.totalBytes() - used) {
                throw new AttachmentValidationException();
            }
        }

        private void commitFiles(IncidentAttachment attachment, byte[] content, LocalApplicationState next) {
            boolean prepared = false;
            boolean committed = false;
            try {
                attachmentFiles.prepare(attachment, content);
                prepared = true;
                persist(next);
                committed = true;
            } catch (IOException exception) {
                throw new RepositoryException(StorageFailureCode.STORAGE_UNAVAILABLE, "Attachment could not be saved", exception);
            } finally {
                if (prepared) {
                    try {
                        attachmentFiles.finish(attachment.id(), committed);
                    } catch (IOException exception) {
                        // The durable marker allows startup recovery; never report a committed addition as failed.
                        System.err.println("Attachment cleanup deferred until storage recovery");
                    }
                }
            }
        }
    }

    /** SLO configuration facet sharing this aggregate's canonical state and safe-write protocol. */
    private final class SloConfigurationStoreFacet implements SloConfigurationStore {
        @Override
        public void append(SloTargetVersionId id, SloTargetVersion value) {
            synchronized (LocalApplicationStore.this) {
                SloTargetVersionId requiredId = Objects.requireNonNull(id, "id");
                SloTargetVersion requiredValue = Objects.requireNonNull(value, "value");
                if (!requiredId.equals(requiredValue.id())) {
                    throw new IllegalArgumentException("id must match the SLO target version identifier");
                }
                Map<SloTargetVersionId, SloTargetVersion> sloTargetVersions =
                        new LinkedHashMap<>(state.sloTargetVersions());
                if (sloTargetVersions.putIfAbsent(requiredId, requiredValue) != null) {
                    throw new RepositoryException(
                            StorageFailureCode.ALREADY_EXISTS, "SLO target version already exists");
                }
                persist(nextState(
                        state.accounts(), state.credentials(), state.incidents(), state.comments(), state.auditEvents(),
                        sloTargetVersions));
            }
        }

        @Override
        public Optional<SloTargetVersion> findById(SloTargetVersionId id) {
            synchronized (LocalApplicationStore.this) {
                return Optional.ofNullable(state.sloTargetVersions().get(Objects.requireNonNull(id, "id")));
            }
        }

        @Override
        public List<SloTargetVersion> findAll() {
            synchronized (LocalApplicationStore.this) {
                return state.sloTargetVersions().values().stream()
                        .sorted(Comparator.comparing(version -> version.id().value())).toList();
            }
        }

        @Override
        public List<SloTargetVersion> findByCategory(IncidentCategory category) {
            IncidentCategory requiredCategory = Objects.requireNonNull(category, "category");
            return findAll().stream().filter(version -> version.category() == requiredCategory).toList();
        }

        @Override
        public void commit(AuditedMutation<SloTargetVersion> auditedMutation) {
            synchronized (LocalApplicationStore.this) {
                AuditedMutation<SloTargetVersion> required = Objects.requireNonNull(
                        auditedMutation, "auditedMutation");
                rejectDuplicateAudit(required.auditEvent());
                Map<SloTargetVersionId, SloTargetVersion> sloTargetVersions =
                        new LinkedHashMap<>(state.sloTargetVersions());
                SloTargetVersion version = required.nextState();
                if (sloTargetVersions.putIfAbsent(version.id(), version) != null) {
                    throw new RepositoryException(
                            StorageFailureCode.ALREADY_EXISTS, "SLO target version already exists");
                }
                List<AuditEvent> audits = new ArrayList<>(state.auditEvents());
                audits.add(required.auditEvent());
                persist(nextState(
                        state.accounts(), state.credentials(), state.incidents(), state.comments(), audits, sloTargetVersions));
            }
        }
    }
}
