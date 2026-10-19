package com.company.incidentdesk.persistence.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.company.incidentdesk.domain.attachment.AttachmentId;
import com.company.incidentdesk.domain.attachment.AttachmentType;
import com.company.incidentdesk.domain.attachment.IncidentAttachment;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.ResponderAccess;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.PromotionRequestStatus;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.application.account.PasswordCredential;
import com.company.incidentdesk.domain.audit.AuditAction;
import com.company.incidentdesk.domain.audit.AuditActor;
import com.company.incidentdesk.domain.audit.AuditActorVisibility;
import com.company.incidentdesk.domain.audit.AuditChange;
import com.company.incidentdesk.domain.audit.AuditChangeField;
import com.company.incidentdesk.domain.audit.AuditEvent;
import com.company.incidentdesk.domain.audit.AuditEventId;
import com.company.incidentdesk.domain.audit.AuditEvidenceReference;
import com.company.incidentdesk.domain.audit.AuditEvidenceType;
import com.company.incidentdesk.domain.audit.AuditOutcome;
import com.company.incidentdesk.domain.audit.AuditTarget;
import com.company.incidentdesk.domain.audit.AuditTargetType;
import com.company.incidentdesk.domain.comment.CommentId;
import com.company.incidentdesk.domain.comment.CommentType;
import com.company.incidentdesk.domain.comment.IncidentComment;
import com.company.incidentdesk.domain.incident.Incident;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.domain.incident.IncidentId;
import com.company.incidentdesk.domain.incident.IncidentStatus;
import com.company.incidentdesk.domain.incident.Resolution;
import com.company.incidentdesk.domain.incident.ResolutionCycle;
import com.company.incidentdesk.domain.slo.SloTarget;
import com.company.incidentdesk.domain.slo.SloTargetVersion;
import com.company.incidentdesk.domain.slo.SloTargetVersionId;
import com.company.incidentdesk.persistence.RepositoryException;
import com.company.incidentdesk.persistence.StorageFailureCode;

/** Deterministic binary schema for the aggregate local application state. */
final class LocalApplicationStateCodec implements DataCodec<LocalApplicationState> {
    static final int SCHEMA_VERSION = 2;
    private static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAGIC = 0x49444B31; // IDK1
    private static final int MAX_RECORDS = 1_000_000;
    private static final int RESERVED_SECTION_RECORD_COUNT = 0;

    @Override
    public byte[] encode(LocalApplicationState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(state.schemaVersion());
            writeAccounts(output, state.accounts());
            writeCredentials(output, state.credentials());
            writeIncidents(output, state.incidents());
            writeComments(output, state.comments());
            writeAudits(output, state.auditEvents());
            writeAttachments(output, state.attachments());
            writePromotionRequests(output, state.promotionRequests(), state.schemaVersion());
            writeSloTargetVersions(output, state.sloTargetVersions());
        }
        return bytes.toByteArray();
    }

    @Override
    public LocalApplicationState decode(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("invalid application data header");
            }
            int version = input.readInt();
            if (version > SCHEMA_VERSION) {
                throw new RepositoryException(StorageFailureCode.UNSUPPORTED_SCHEMA,
                        "application data was written by a newer version");
            }
            if (version < MIN_SUPPORTED_SCHEMA_VERSION) {
                throw new IOException("unsupported legacy schema");
            }
            Map<AccountId, Account> accounts = readAccounts(input);
            Map<AccountId, PasswordCredential> credentials = readCredentials(input);
            Map<IncidentId, Incident> incidents = readIncidents(input);
            List<IncidentComment> comments = readComments(input);
            List<AuditEvent> auditEvents = readAudits(input);
            Map<AttachmentId, IncidentAttachment> attachments = readAttachments(input);
            Map<PromotionRequestId, ResponderPromotionRequest> promotionRequests = readPromotionRequests(input, version);
            Map<SloTargetVersionId, SloTargetVersion> sloTargetVersions = readSloTargetVersions(input);
            LocalApplicationState state = new LocalApplicationState(
                    accounts, credentials, incidents, comments, auditEvents, promotionRequests,
                    sloTargetVersions, attachments, version);
            if (input.read() != -1) {
                throw new IOException("unexpected trailing application data");
            }
            validateReferences(state);
            return state;
        } catch (EOFException exception) {
            throw new IOException("truncated application data", exception);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("invalid domain data", exception);
        }
    }

    private static void writeCredentials(DataOutputStream output, Map<AccountId, PasswordCredential> credentials)
            throws IOException {
        output.writeInt(credentials.size());
        for (var entry : credentials.entrySet().stream()
                .sorted((left, right) -> left.getKey().value().compareTo(right.getKey().value())).toList()) {
            writeUuid(output, entry.getKey().value());
            PasswordCredential credential = entry.getValue();
            output.writeUTF(credential.algorithm());
            output.writeInt(credential.iterations());
            byte[] salt = credential.salt();
            output.writeInt(salt.length);
            output.write(salt);
            byte[] hash = credential.hash();
            output.writeInt(hash.length);
            output.write(hash);
            output.writeBoolean(credential.temporary());
            writeOptionalInstant(output, credential.expiresAt());
        }
    }

    private static Map<AccountId, PasswordCredential> readCredentials(DataInputStream input) throws IOException {
        Map<AccountId, PasswordCredential> credentials = new LinkedHashMap<>();
        int count = readCount(input);
        for (int index = 0; index < count; index++) {
            AccountId id = new AccountId(readUuid(input));
            String algorithm = input.readUTF();
            int iterations = input.readInt();
            byte[] salt = input.readNBytes(readByteArrayLength(input));
            byte[] hash = input.readNBytes(readByteArrayLength(input));
            boolean temporary = input.readBoolean();
            Optional<Instant> expiresAt = readOptionalInstant(input);
            requireUnique(credentials.put(id,
                    new PasswordCredential(algorithm, iterations, salt, hash, temporary, expiresAt)),
                    "account credential");
        }
        return credentials;
    }

    private static int readByteArrayLength(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 4096) {
            throw new IOException("invalid byte array length");
        }
        return length;
    }

    private static void writeAccounts(DataOutputStream output, Map<AccountId, Account> accounts) throws IOException {
        output.writeInt(accounts.size());
        for (Account account : accounts.values().stream()
                .sorted((left, right) -> left.id().value().compareTo(right.id().value())).toList()) {
            writeUuid(output, account.id().value());
            output.writeUTF(account.loginName());
            output.writeUTF(account.role().name());
            output.writeUTF(account.status().name());
            output.writeInt(account.responderAccess().categories().size());
            for (IncidentCategory category : account.responderAccess().categories().stream().sorted().toList()) {
                output.writeUTF(category.name());
            }
        }
    }

    private static Map<AccountId, Account> readAccounts(DataInputStream input) throws IOException {
        Map<AccountId, Account> accounts = new LinkedHashMap<>();
        int accountCount = readCount(input);
        for (int index = 0; index < accountCount; index++) {
            AccountId id = new AccountId(readUuid(input));
            String loginName = input.readUTF();
            Role role = readEnum(input, Role.class);
            AccountStatus status = readEnum(input, AccountStatus.class);
            EnumSet<IncidentCategory> categories = EnumSet.noneOf(IncidentCategory.class);
            int categoryCount = readCount(input);
            for (int category = 0; category < categoryCount; category++) {
                categories.add(readEnum(input, IncidentCategory.class));
            }
            Account account = new Account(id, loginName, role, status, ResponderAccess.to(categories));
            requireUnique(accounts.put(id, account), "account identifier");
        }
        return accounts;
    }

    private static void writePromotionRequests(DataOutputStream output,
            Map<PromotionRequestId, ResponderPromotionRequest> requests, int schemaVersion) throws IOException {
        output.writeUTF("promotions");
        output.writeInt(requests.size());
        for (ResponderPromotionRequest request : requests.values().stream()
                .sorted((left, right) -> left.id().value().compareTo(right.id().value())).toList()) {
            writeUuid(output, request.id().value());
            writeUuid(output, request.requesterId().value());
            output.writeInt(request.requestedCategories().size());
            for (IncidentCategory category : request.requestedCategories().stream().sorted().toList()) {
                output.writeUTF(category.name());
            }
            output.writeBoolean(request.comments().isPresent());
            if (request.comments().isPresent()) {
                output.writeUTF(request.comments().orElseThrow());
            }
            writeInstant(output, request.requestedAt());
            output.writeUTF(request.status().name());
            writeOptionalAccountId(output, request.decidedBy());
            writeOptionalInstant(output, request.decidedAt());
            if (schemaVersion >= 2) {
                Set<IncidentCategory> approved = request.approvedCategories().orElse(Set.of());
                output.writeInt(approved.size());
                for (IncidentCategory category : approved.stream().sorted().toList()) {
                    output.writeUTF(category.name());
                }
            }
        }
    }

    private static Map<PromotionRequestId, ResponderPromotionRequest> readPromotionRequests(
            DataInputStream input, int schemaVersion)
            throws IOException {
        if (!"promotions".equals(input.readUTF())) {
            throw new IOException("invalid promotions section");
        }
        Map<PromotionRequestId, ResponderPromotionRequest> requests = new LinkedHashMap<>();
        int count = readCount(input);
        for (int index = 0; index < count; index++) {
            PromotionRequestId id = new PromotionRequestId(readUuid(input));
            AccountId requesterId = new AccountId(readUuid(input));
            EnumSet<IncidentCategory> categories = EnumSet.noneOf(IncidentCategory.class);
            int categoryCount = readCount(input);
            for (int category = 0; category < categoryCount; category++) {
                categories.add(readEnum(input, IncidentCategory.class));
            }
            Optional<String> comments = input.readBoolean() ? Optional.of(input.readUTF()) : Optional.empty();
            Instant requestedAt = readInstant(input);
            PromotionRequestStatus status = readEnum(input, PromotionRequestStatus.class);
            Optional<AccountId> decidedBy = readOptionalAccountId(input);
            Optional<Instant> decidedAt = readOptionalInstant(input);
            Optional<Set<IncidentCategory>> approvedCategories;
            if (schemaVersion >= 2) {
                EnumSet<IncidentCategory> approved = EnumSet.noneOf(IncidentCategory.class);
                int approvedCount = readCount(input);
                for (int category = 0; category < approvedCount; category++) {
                    approved.add(readEnum(input, IncidentCategory.class));
                }
                approvedCategories = status == PromotionRequestStatus.PENDING
                        ? Optional.empty() : Optional.of(Set.copyOf(approved));
            } else {
                approvedCategories = switch (status) {
                case PENDING -> Optional.empty();
                case APPROVED -> Optional.of(Set.copyOf(categories));
                case REJECTED -> Optional.of(Set.of());
                };
            }
            requireUnique(requests.put(id, new ResponderPromotionRequest(id, requesterId, categories, comments,
                    requestedAt, status, decidedBy, decidedAt, approvedCategories)), "promotion request identifier");
        }
        return requests;
    }

    private static void writeIncidents(DataOutputStream output, Map<IncidentId, Incident> incidents) throws IOException {
        output.writeInt(incidents.size());
        for (Incident incident : incidents.values().stream()
                .sorted((left, right) -> left.id().value().compareTo(right.id().value())).toList()) {
            writeUuid(output, incident.id().value());
            writeUuid(output, incident.reporterId().value());
            output.writeUTF(incident.title());
            output.writeUTF(incident.description());
            output.writeUTF(incident.category().name());
            output.writeUTF(incident.status().name());
            output.writeBoolean(incident.anonymous());
            writeInstant(output, incident.createdAt());
            writeOptionalInstant(output, incident.submittedAt());
            writeOptionalInstant(output, incident.withdrawnAt());
            writeOptionalAccountId(output, incident.assigneeId());
            output.writeInt(incident.resolutionCycles().size());
            for (ResolutionCycle cycle : incident.resolutionCycles()) {
                writeCycle(output, cycle);
            }
        }
    }

    private static Map<IncidentId, Incident> readIncidents(DataInputStream input) throws IOException {
        Map<IncidentId, Incident> incidents = new LinkedHashMap<>();
        int incidentCount = readCount(input);
        for (int index = 0; index < incidentCount; index++) {
            IncidentId id = new IncidentId(readUuid(input));
            AccountId reporterId = new AccountId(readUuid(input));
            String title = input.readUTF();
            String description = input.readUTF();
            IncidentCategory category = readEnum(input, IncidentCategory.class);
            IncidentStatus status = readEnum(input, IncidentStatus.class);
            boolean anonymous = input.readBoolean();
            Instant createdAt = readInstant(input);
            Optional<Instant> submittedAt = readOptionalInstant(input);
            Optional<Instant> withdrawnAt = readOptionalInstant(input);
            Optional<AccountId> assigneeId = readOptionalAccountId(input);
            List<ResolutionCycle> cycles = new ArrayList<>();
            int cycleCount = readCount(input);
            for (int cycle = 0; cycle < cycleCount; cycle++) {
                cycles.add(readCycle(input));
            }
            Incident incident = new Incident(id, reporterId, title, description, category, status,
                    anonymous, createdAt, submittedAt, withdrawnAt, assigneeId, cycles);
            requireUnique(incidents.put(id, incident), "incident identifier");
        }
        return incidents;
    }

    private static void writeCycle(DataOutputStream output, ResolutionCycle cycle) throws IOException {
        writeInstant(output, cycle.queueEnteredAt());
        writeOptionalInstant(output, cycle.firstAssignedAt());
        writeOptionalInstant(output, cycle.latestAssignedAt());
        output.writeBoolean(cycle.resolution().isPresent());
        if (cycle.resolution().isPresent()) {
            Resolution resolution = cycle.resolution().orElseThrow();
            output.writeUTF(resolution.remarks());
            writeInstant(output, resolution.resolvedAt());
            writeUuid(output, resolution.resolvedBy().value());
            writeUuid(output, resolution.responderAtResolution().value());
        }
    }

    private static ResolutionCycle readCycle(DataInputStream input) throws IOException {
        Instant queuedAt = readInstant(input);
        Optional<Instant> firstAssignedAt = readOptionalInstant(input);
        Optional<Instant> latestAssignedAt = readOptionalInstant(input);
        Optional<Resolution> resolution = Optional.empty();
        if (input.readBoolean()) {
            resolution = Optional.of(new Resolution(input.readUTF(), readInstant(input),
                    new AccountId(readUuid(input)), new AccountId(readUuid(input))));
        }
        return new ResolutionCycle(queuedAt, firstAssignedAt, latestAssignedAt, resolution);
    }

    private static void writeComments(DataOutputStream output, List<IncidentComment> comments) throws IOException {
        output.writeInt(comments.size());
        for (IncidentComment comment : comments) {
            writeUuid(output, comment.id().value());
            writeUuid(output, comment.incidentId().value());
            writeUuid(output, comment.authorId().value());
            output.writeUTF(comment.authorRole().name());
            writeInstant(output, comment.createdAt());
            output.writeUTF(comment.type().name());
            output.writeUTF(comment.text());
        }
    }

    private static List<IncidentComment> readComments(DataInputStream input) throws IOException {
        List<IncidentComment> comments = new ArrayList<>();
        int commentCount = readCount(input);
        for (int index = 0; index < commentCount; index++) {
            comments.add(new IncidentComment(new CommentId(readUuid(input)), new IncidentId(readUuid(input)),
                    new AccountId(readUuid(input)), readEnum(input, Role.class), readInstant(input),
                    readEnum(input, CommentType.class), input.readUTF()));
        }
        return comments;
    }

    private static void writeSloTargetVersions(
            DataOutputStream output,
            Map<SloTargetVersionId, SloTargetVersion> sloTargetVersions) throws IOException {
        output.writeUTF("slo-configurations");
        output.writeInt(sloTargetVersions.size());
        for (SloTargetVersion version : sloTargetVersions.values().stream()
                .sorted((left, right) -> left.id().value().compareTo(right.id().value())).toList()) {
            writeUuid(output, version.id().value());
            output.writeUTF(version.category().name());
            writeDuration(output, version.target().timeToClaimTarget());
            writeDuration(output, version.target().timeInProgressTarget());
            output.writeDouble(version.target().reopenRateTarget());
            writeInstant(output, version.effectiveFrom());
            writeUuid(output, version.changedBy().value());
        }
    }

    private static Map<SloTargetVersionId, SloTargetVersion> readSloTargetVersions(DataInputStream input)
            throws IOException {
        if (!"slo-configurations".equals(input.readUTF())) {
            throw new IOException("invalid SLO configuration schema section");
        }
        Map<SloTargetVersionId, SloTargetVersion> sloTargetVersions = new LinkedHashMap<>();
        int count = readCount(input);
        for (int index = 0; index < count; index++) {
            SloTargetVersionId id = new SloTargetVersionId(readUuid(input));
            IncidentCategory category = readEnum(input, IncidentCategory.class);
            Duration timeToClaimTarget = readDuration(input);
            Duration timeInProgressTarget = readDuration(input);
            double reopenRateTarget = input.readDouble();
            Instant effectiveFrom = readInstant(input);
            AccountId changedBy = new AccountId(readUuid(input));
            SloTargetVersion version = new SloTargetVersion(
                    id, category, new SloTarget(timeToClaimTarget, timeInProgressTarget, reopenRateTarget),
                    effectiveFrom, changedBy);
            requireUnique(sloTargetVersions.put(id, version), "SLO target version identifier");
        }
        return sloTargetVersions;
    }

    private static void writeDuration(DataOutputStream output, Duration duration) throws IOException {
        output.writeLong(duration.getSeconds());
        output.writeInt(duration.getNano());
    }

    private static Duration readDuration(DataInputStream input) throws IOException {
        return Duration.ofSeconds(input.readLong(), input.readInt());
    }

    private static void writeAudits(DataOutputStream output, List<AuditEvent> events) throws IOException {
        output.writeInt(events.size());
        for (AuditEvent event : events) {
            writeUuid(output, event.id().value());
            writeInstant(output, event.occurredAt());
            writeUuid(output, event.actor().accountId().value());
            output.writeUTF(event.actor().role().name());
            output.writeUTF(event.actor().visibility().name());
            output.writeUTF(event.action().name());
            output.writeUTF(event.target().type().name());
            output.writeUTF(event.target().identifier());
            output.writeUTF(event.outcome().name());
            output.writeInt(event.changes().size());
            for (AuditChange change : event.changes()) {
                output.writeUTF(change.field().name());
                writeOptionalString(output, change.beforeValue());
                writeOptionalString(output, change.afterValue());
            }
            output.writeBoolean(event.evidenceReference().isPresent());
            if (event.evidenceReference().isPresent()) {
                AuditEvidenceReference evidence = event.evidenceReference().orElseThrow();
                output.writeUTF(evidence.type().name());
                output.writeUTF(evidence.identifier());
            }
        }
    }

    private static List<AuditEvent> readAudits(DataInputStream input) throws IOException {
        List<AuditEvent> events = new ArrayList<>();
        int eventCount = readCount(input);
        for (int index = 0; index < eventCount; index++) {
            AuditEventId id = new AuditEventId(readUuid(input));
            Instant occurredAt = readInstant(input);
            AuditActor actor = new AuditActor(new AccountId(readUuid(input)), readEnum(input, Role.class),
                    readEnum(input, AuditActorVisibility.class));
            AuditAction action = readEnum(input, AuditAction.class);
            AuditTarget target = new AuditTarget(readEnum(input, AuditTargetType.class), input.readUTF());
            AuditOutcome outcome = readEnum(input, AuditOutcome.class);
            List<AuditChange> changes = new ArrayList<>();
            int changeCount = readCount(input);
            for (int change = 0; change < changeCount; change++) {
                changes.add(new AuditChange(readEnum(input, AuditChangeField.class),
                        readOptionalString(input), readOptionalString(input)));
            }
            Optional<AuditEvidenceReference> evidence = Optional.empty();
            if (input.readBoolean()) {
                evidence = Optional.of(new AuditEvidenceReference(
                        readEnum(input, AuditEvidenceType.class), input.readUTF()));
            }
            events.add(new AuditEvent(id, occurredAt, actor, action, target, outcome, changes, evidence));
        }
        return events;
    }

    private static void validateReferences(LocalApplicationState state) throws IOException {
        for (IncidentAttachment attachment : state.attachments().values()) {
            if (!state.incidents().containsKey(attachment.incidentId())) {
                throw new IOException("attachment references a missing incident");
            }
        }
        for (AccountId accountId : state.credentials().keySet()) {
            requireAccount(state, accountId, "credential");
        }
        for (Incident incident : state.incidents().values()) {
            requireAccount(state, incident.reporterId(), "incident reporter");
            incident.assigneeId().ifPresent(id -> requireAccountUnchecked(state, id, "incident assignee"));
        }
        for (IncidentComment comment : state.comments()) {
            if (!state.incidents().containsKey(comment.incidentId())) {
                throw new IOException("comment references a missing incident");
            }
            requireAccount(state, comment.authorId(), "comment author");
        }
        for (SloTargetVersion version : state.sloTargetVersions().values()) {
            requireAccount(state, version.changedBy(), "SLO configuration changedBy");
        }
        for (ResponderPromotionRequest request : state.promotionRequests().values()) {
            requireAccount(state, request.requesterId(), "promotion requester");
            request.decidedBy().ifPresent(id -> requireAccountUnchecked(state, id, "promotion decision actor"));
        }
    }

    private static void requireAccount(LocalApplicationState state, AccountId id, String relation) throws IOException {
        if (!state.accounts().containsKey(id)) {
            throw new IOException(relation + " references a missing account");
        }
    }

    private static void requireAccountUnchecked(LocalApplicationState state, AccountId id, String relation) {
        if (!state.accounts().containsKey(id)) {
            throw new IllegalArgumentException(relation + " references a missing account");
        }
    }

    private static void writeAttachments(DataOutputStream output, Map<AttachmentId, IncidentAttachment> attachments)
            throws IOException {
        output.writeUTF("attachments");
        output.writeInt(attachments.size());
        for (IncidentAttachment attachment : attachments.values().stream()
                .sorted((left, right) -> left.id().value().compareTo(right.id().value())).toList()) {
            writeUuid(output, attachment.id().value());
            writeUuid(output, attachment.incidentId().value());
            output.writeUTF(attachment.type().name());
            output.writeLong(attachment.sizeBytes());
            output.writeUTF(attachment.displayName());
            writeInstant(output, attachment.createdAt());
        }
    }

    private static Map<AttachmentId, IncidentAttachment> readAttachments(DataInputStream input)
            throws IOException {
        if (!"attachments".equals(input.readUTF())) {
            throw new IOException("invalid attachment schema section");
        }
        Map<AttachmentId, IncidentAttachment> attachments = new LinkedHashMap<>();
        int count = readCount(input);
        for (int index = 0; index < count; index++) {
            AttachmentId id = new AttachmentId(readUuid(input));
            IncidentAttachment attachment = new IncidentAttachment(id, new IncidentId(readUuid(input)),
                    readEnum(input, AttachmentType.class), input.readLong(), input.readUTF(), readInstant(input));
            requireUnique(attachments.put(id, attachment), "attachment identifier");
        }
        return attachments;
    }

    private static void writeReservedSection(DataOutputStream output, String name) throws IOException {
        output.writeUTF(name);
        output.writeInt(RESERVED_SECTION_RECORD_COUNT);
    }

    private static void readReservedSection(DataInputStream input, String expectedName) throws IOException {
        if (!expectedName.equals(input.readUTF()) || input.readInt() != RESERVED_SECTION_RECORD_COUNT) {
            throw new IOException("invalid reserved schema section");
        }
    }

    private static void writeOptionalAccountId(DataOutputStream output, Optional<AccountId> value) throws IOException {
        output.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            writeUuid(output, value.orElseThrow().value());
        }
    }

    private static Optional<AccountId> readOptionalAccountId(DataInputStream input) throws IOException {
        return input.readBoolean() ? Optional.of(new AccountId(readUuid(input))) : Optional.empty();
    }

    private static void writeOptionalInstant(DataOutputStream output, Optional<Instant> value) throws IOException {
        output.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            writeInstant(output, value.orElseThrow());
        }
    }

    private static Optional<Instant> readOptionalInstant(DataInputStream input) throws IOException {
        return input.readBoolean() ? Optional.of(readInstant(input)) : Optional.empty();
    }

    private static void writeOptionalString(DataOutputStream output, Optional<String> value) throws IOException {
        output.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            output.writeUTF(value.orElseThrow());
        }
    }

    private static Optional<String> readOptionalString(DataInputStream input) throws IOException {
        return input.readBoolean() ? Optional.of(input.readUTF()) : Optional.empty();
    }

    private static void writeInstant(DataOutputStream output, Instant instant) throws IOException {
        output.writeLong(instant.getEpochSecond());
        output.writeInt(instant.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        return Instant.ofEpochSecond(input.readLong(), input.readInt());
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_RECORDS) {
            throw new IOException("invalid record count");
        }
        return count;
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream input, Class<E> type) throws IOException {
        try {
            return Enum.valueOf(type, input.readUTF());
        } catch (IllegalArgumentException exception) {
            throw new IOException("unknown " + type.getSimpleName() + " value", exception);
        }
    }

    private static void requireUnique(Object replaced, String field) throws IOException {
        if (replaced != null) {
            throw new IOException("duplicate " + field);
        }
    }
}
