package com.company.incidentdesk.application.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.company.incidentdesk.application.audit.AuditEventFactory;
import com.company.incidentdesk.application.authorization.AccountAuthorizationPolicy;
import com.company.incidentdesk.application.event.ApplicationEvent;
import com.company.incidentdesk.application.result.ApplicationErrorCode;
import com.company.incidentdesk.application.session.AuthenticatedSession;
import com.company.incidentdesk.application.session.SessionProvider;
import com.company.incidentdesk.application.attachment.AttachmentLimits;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.PromotionRequestStatus;
import com.company.incidentdesk.domain.account.ResponderAccess;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.audit.AuditAction;
import com.company.incidentdesk.domain.audit.AuditActorVisibility;
import com.company.incidentdesk.domain.audit.AuditEventId;
import com.company.incidentdesk.domain.audit.AuditEvidenceReference;
import com.company.incidentdesk.domain.audit.AuditEvidenceType;
import com.company.incidentdesk.domain.audit.AuditOutcome;
import com.company.incidentdesk.domain.audit.AuditTarget;
import com.company.incidentdesk.domain.audit.AuditTargetType;
import com.company.incidentdesk.domain.attachment.AttachmentId;
import com.company.incidentdesk.domain.attachment.AttachmentType;
import com.company.incidentdesk.domain.attachment.IncidentAttachment;
import com.company.incidentdesk.domain.incident.IncidentId;
import com.company.incidentdesk.domain.incident.IncidentLifecycle;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.persistence.AuditQuery;
import com.company.incidentdesk.persistence.AuditSortDirection;
import com.company.incidentdesk.persistence.file.LocalApplicationStore;

class PromotionRequestServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-25T01:00:00Z");
    private static final AccountId REPORTER_ID = id(1);
    private static final AccountId ADMIN_ID = id(2);
    private static final AccountId RESPONDER_ID = id(3);

    @TempDir
    Path directory;

    @Test
    void reporterSubmissionAndAdministratorApprovalPersistHistoryAuditAndRoleChange() {
        PromotionRequestId requestId = new PromotionRequestId(new UUID(0, 10));
        try (LocalApplicationStore store = initializedStore()) {
            MutableSession sessions = new MutableSession(store, REPORTER_ID);
            List<ApplicationEvent> events = new ArrayList<>();
            PromotionRequestService service = service(store, sessions, requestId, events);

            var submitted = service.submit(Set.of(IncidentCategory.IT, IncidentCategory.FACILITIES),
                    Optional.of("  I can help  "));

            assertTrue(submitted.isSuccess());
            assertEquals("I can help", submitted.value().orElseThrow().comments().orElseThrow());
            assertInstanceOf(PromotionRequestedEvent.class, events.getFirst());
            sessions.accountId = ADMIN_ID;

            var approved = service.decide(requestId, true, Set.of(IncidentCategory.IT));

            assertTrue(approved.isSuccess());
            assertEquals(PromotionRequestStatus.APPROVED, approved.value().orElseThrow().status());
            Account promoted = store.findById(REPORTER_ID).orElseThrow();
            assertEquals(Role.RESPONDER, promoted.role());
            assertEquals(Set.of(IncidentCategory.IT),
                    promoted.responderAccess().categories());
            assertInstanceOf(PromotionDecisionEvent.class, events.getLast());
            assertEquals(List.of(AuditAction.RESPONDER_PROMOTION_REQUESTED,
                    AuditAction.RESPONDER_PROMOTION_APPROVED), store.find(AuditQuery.all(),
                            AuditSortDirection.OLDEST_FIRST).stream().map(event -> event.action()).toList());
        }

        try (LocalApplicationStore reopened = new LocalApplicationStore(directory)) {
            assertEquals(PromotionRequestStatus.APPROVED,
                    reopened.findPromotionRequest(requestId).orElseThrow().status());
            assertEquals(Set.of(IncidentCategory.IT), reopened.findPromotionRequest(requestId).orElseThrow()
                    .approvedCategories().orElseThrow());
            assertEquals(Role.RESPONDER, reopened.findById(REPORTER_ID).orElseThrow().role());
        }
    }

    @Test
    void attachmentWriteAfterSubsetApprovalPreservesApprovedCategoriesAcrossRestart() {
        PromotionRequestId requestId = new PromotionRequestId(new UUID(0, 30));
        IncidentId incidentId = new IncidentId(new UUID(0, 31));
        IncidentAttachment attachment = new IncidentAttachment(new AttachmentId(new UUID(0, 32)), incidentId,
                AttachmentType.PNG, 1, "proof.png", NOW);

        try (LocalApplicationStore store = initializedStore()) {
            var incident = new IncidentLifecycle(Clock.fixed(NOW, ZoneOffset.UTC)).submit(incidentId, REPORTER_ID,
                    "Printer", "Broken", IncidentCategory.IT, false);
            store.create(incident);
            MutableSession sessions = new MutableSession(store, REPORTER_ID);
            PromotionRequestService service = service(store, sessions, requestId, new ArrayList<>());
            assertTrue(service.submit(Set.of(IncidentCategory.IT, IncidentCategory.FACILITIES), Optional.empty())
                    .isSuccess());
            sessions.accountId = ADMIN_ID;
            assertTrue(service.decide(requestId, true, Set.of(IncidentCategory.IT)).isSuccess());

            Account administrator = store.findById(ADMIN_ID).orElseThrow();
            AtomicLong attachmentAuditIds = new AtomicLong(33);
            AuditEventFactory attachmentAudits = new AuditEventFactory(Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> new AuditEventId(new UUID(0, attachmentAuditIds.incrementAndGet())));
            var attachmentAudit = attachmentAudits.create(administrator, AuditActorVisibility.STANDARD,
                    AuditAction.ATTACHMENT_ADDED,
                    new AuditTarget(AuditTargetType.INCIDENT, incidentId.value().toString()), AuditOutcome.SUCCESS,
                    List.of(), Optional.of(new AuditEvidenceReference(AuditEvidenceType.ATTACHMENT,
                            attachment.id().value().toString())));
            var migrationAudit = attachmentAudits.create(administrator, AuditActorVisibility.STANDARD,
                    AuditAction.DATA_MIGRATED,
                    new AuditTarget(AuditTargetType.APPLICATION_DATA, "schema-1-to-2"), AuditOutcome.SUCCESS,
                    List.of(), Optional.empty());
            store.attachmentStore().add(attachment, new byte[] { 1 }, incident, administrator,
                    AttachmentLimits.DEFAULT, attachmentAudit, migrationAudit);
        }

        try (LocalApplicationStore reopened = new LocalApplicationStore(directory)) {
            var request = reopened.findPromotionRequest(requestId).orElseThrow();
            assertEquals(Set.of(IncidentCategory.IT), request.approvedCategories().orElseThrow());
            assertEquals(Set.of(IncidentCategory.IT),
                    reopened.findById(REPORTER_ID).orElseThrow().responderAccess().categories());
            assertEquals(attachment, reopened.attachmentStore().find(attachment.id()).orElseThrow());
        }
    }

    @Test
    void administratorCanApproveOnlyARequestedCategorySubset() {
        PromotionRequestId requestId = new PromotionRequestId(new UUID(0, 20));
        try (LocalApplicationStore store = initializedStore()) {
            MutableSession sessions = new MutableSession(store, REPORTER_ID);
            PromotionRequestService service = service(store, sessions, requestId, new ArrayList<>());
            Set<IncidentCategory> requested = Set.of(IncidentCategory.IT, IncidentCategory.FACILITIES);
            assertTrue(service.submit(requested, Optional.empty()).isSuccess());
            sessions.accountId = ADMIN_ID;

            var approved = service.decideAs(store.findById(ADMIN_ID).orElseThrow(), requestId, true,
                    Set.of(IncidentCategory.IT));

            assertTrue(approved.isSuccess());
            assertEquals(Set.of(IncidentCategory.IT), approved.value().orElseThrow()
                    .approvedCategories().orElseThrow());
            assertEquals(Set.of(IncidentCategory.IT), store.findById(REPORTER_ID).orElseThrow()
                    .responderAccess().categories());
        }
    }

    @Test
    void administratorCannotApproveEmptyOrUnrequestedCategories() {
        int scenario = 0;
        for (Set<IncidentCategory> selected : List.of(Set.<IncidentCategory>of(),
                Set.of(IncidentCategory.HUMAN_RELATIONS))) {
            try (LocalApplicationStore store = initializedStore(directory.resolve("scenario-" + scenario))) {
                PromotionRequestId requestId = new PromotionRequestId(UUID.randomUUID());
                MutableSession sessions = new MutableSession(store, REPORTER_ID);
                PromotionRequestService service = service(store, sessions, requestId, new ArrayList<>());
                assertTrue(service.submit(Set.of(IncidentCategory.IT), Optional.empty()).isSuccess());
                sessions.accountId = ADMIN_ID;

                var result = service.decideAs(store.findById(ADMIN_ID).orElseThrow(), requestId, true, selected);

                assertFalse(result.isSuccess());
                assertEquals(PromotionRequestStatus.PENDING,
                        store.findPromotionRequest(requestId).orElseThrow().status());
                assertEquals(Role.REPORTER, store.findById(REPORTER_ID).orElseThrow().role());
            }
            scenario++;
        }
    }

    @Test
    void rejectionRetainsReporterRoleAndAccessAndCreatesOneRedactedAudit() {
        PromotionRequestId requestId = new PromotionRequestId(new UUID(0, 21));
        try (LocalApplicationStore store = initializedStore()) {
            MutableSession sessions = new MutableSession(store, REPORTER_ID);
            PromotionRequestService service = service(store, sessions, requestId, new ArrayList<>());
            assertTrue(service.submit(Set.of(IncidentCategory.IT),
                    Optional.of("private rationale that must not enter audit details")).isSuccess());
            sessions.accountId = ADMIN_ID;

            var rejected = service.decide(requestId, false, Set.of());

            assertTrue(rejected.isSuccess());
            assertEquals(PromotionRequestStatus.REJECTED, rejected.value().orElseThrow().status());
            assertEquals(Role.REPORTER, store.findById(REPORTER_ID).orElseThrow().role());
            assertEquals(ResponderAccess.NONE, store.findById(REPORTER_ID).orElseThrow().responderAccess());
            var audits = store.find(AuditQuery.all(), AuditSortDirection.OLDEST_FIRST);
            assertEquals(List.of(AuditAction.RESPONDER_PROMOTION_REQUESTED,
                    AuditAction.RESPONDER_PROMOTION_REJECTED), audits.stream().map(event -> event.action()).toList());
            assertFalse(audits.stream().flatMap(event -> event.changes().stream())
                    .anyMatch(change -> change.toString().contains("private rationale")));
        }
    }

    @Test
    void disabledReporterCannotSubmitAndStaleAdministratorCannotDecide() {
        PromotionRequestId requestId = new PromotionRequestId(new UUID(0, 22));
        try (LocalApplicationStore store = initializedStore()) {
            MutableSession sessions = new MutableSession(store, REPORTER_ID);
            PromotionRequestService service = service(store, sessions, requestId, new ArrayList<>());
            Account reporter = store.findById(REPORTER_ID).orElseThrow();
            store.update(new Account(reporter.id(), reporter.loginName(), reporter.role(),
                    AccountStatus.DISABLED, reporter.responderAccess()));
            assertFalse(service.submitAs(reporter, Set.of(IncidentCategory.IT), Optional.empty()).isSuccess());
            assertTrue(store.findPromotionRequests().isEmpty());

            store.update(reporter);
            assertTrue(service.submitAs(reporter, Set.of(IncidentCategory.IT), Optional.empty()).isSuccess());
            Account administrator = store.findById(ADMIN_ID).orElseThrow();
            store.update(new Account(administrator.id(), administrator.loginName(), Role.RESPONDER,
                    administrator.status(), ResponderAccess.to(Set.of(IncidentCategory.IT))));

            assertFalse(service.decideAs(administrator, requestId, true, Set.of(IncidentCategory.IT)).isSuccess());
            assertEquals(PromotionRequestStatus.PENDING,
                    store.findPromotionRequest(requestId).orElseThrow().status());
            assertEquals(Role.REPORTER, store.findById(REPORTER_ID).orElseThrow().role());
        }
    }

    @Test
    void nonReporterCannotSubmitAndDeniedPathChangesNothing() {
        try (LocalApplicationStore store = initializedStore()) {
            MutableSession sessions = new MutableSession(store, RESPONDER_ID);
            List<ApplicationEvent> events = new ArrayList<>();

            var result = service(store, sessions, new PromotionRequestId(new UUID(0, 11)), events)
                    .submit(Set.of(IncidentCategory.IT), Optional.empty());

            assertFalse(result.isSuccess());
            assertEquals(ApplicationErrorCode.RESOURCE_UNAVAILABLE, result.error().orElseThrow().code());
            assertTrue(store.findPromotionRequests().isEmpty());
            assertTrue(store.find(AuditQuery.all(), AuditSortDirection.OLDEST_FIRST).isEmpty());
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void onlyAdministratorCanDecideAndRequestRemainsPendingWhenDenied() {
        PromotionRequestId requestId = new PromotionRequestId(new UUID(0, 12));
        try (LocalApplicationStore store = initializedStore()) {
            MutableSession sessions = new MutableSession(store, REPORTER_ID);
            PromotionRequestService service = service(store, sessions, requestId, new ArrayList<>());
            assertTrue(service.submit(Set.of(IncidentCategory.IT), Optional.empty()).isSuccess());
            sessions.accountId = RESPONDER_ID;

            var result = service.decide(requestId, true, Set.of(IncidentCategory.IT));

            assertFalse(result.isSuccess());
            assertEquals(PromotionRequestStatus.PENDING,
                    store.findPromotionRequest(requestId).orElseThrow().status());
            assertEquals(Role.REPORTER, store.findById(REPORTER_ID).orElseThrow().role());
        }
    }

    @Test
    void administratorCategoryChangeIsImmediatelyVisibleThroughReloadedSession() {
        try (LocalApplicationStore store = initializedStore()) {
            MutableSession sessions = new MutableSession(store, ADMIN_ID);
            List<ApplicationEvent> events = new ArrayList<>();
            ResponderAccessService service = new ResponderAccessService(sessions,
                    new AccountAuthorizationPolicy(sessions), store, store, auditFactory(), events::add);

            assertTrue(service.changeCategories(RESPONDER_ID, Set.of(IncidentCategory.HUMAN_RELATIONS)).isSuccess());
            sessions.accountId = RESPONDER_ID;

            assertEquals(Set.of(IncidentCategory.HUMAN_RELATIONS), sessions.currentAccount().orElseThrow()
                    .responderAccess().categories());
            assertInstanceOf(ResponderAccessChangedEvent.class, events.getFirst());
            assertEquals(AuditAction.RESPONDER_ACCESS_CHANGED,
                    store.find(AuditQuery.all(), AuditSortDirection.OLDEST_FIRST).getFirst().action());
        }
    }

    private LocalApplicationStore initializedStore() {
        return initializedStore(directory);
    }

    private LocalApplicationStore initializedStore(Path dataDirectory) {
        LocalApplicationStore store = new LocalApplicationStore(dataDirectory);
        store.create(account(REPORTER_ID, "reporter", Role.REPORTER, ResponderAccess.NONE));
        store.create(account(ADMIN_ID, "admin", Role.ADMINISTRATOR, ResponderAccess.NONE));
        store.create(account(RESPONDER_ID, "responder", Role.RESPONDER,
                ResponderAccess.to(Set.of(IncidentCategory.IT))));
        return store;
    }

    private PromotionRequestService service(LocalApplicationStore store, MutableSession sessions,
            PromotionRequestId requestId, List<ApplicationEvent> events) {
        return new PromotionRequestService(sessions, new AccountAuthorizationPolicy(sessions), store, store,
                auditFactory(), Clock.fixed(NOW, ZoneOffset.UTC), () -> requestId, events::add);
    }

    private static AuditEventFactory auditFactory() {
        AtomicLong ids = new AtomicLong(100);
        return new AuditEventFactory(Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new AuditEventId(new UUID(0, ids.incrementAndGet())));
    }

    private static Account account(AccountId id, String name, Role role, ResponderAccess access) {
        return new Account(id, name, role, AccountStatus.ENABLED, access);
    }

    private static AccountId id(long value) {
        return new AccountId(new UUID(0, value));
    }

    private static final class MutableSession implements SessionProvider {
        private final LocalApplicationStore store;
        private AccountId accountId;

        private MutableSession(LocalApplicationStore store, AccountId accountId) {
            this.store = store;
            this.accountId = accountId;
        }

        @Override
        public Optional<AuthenticatedSession> currentSession() {
            return Optional.of(new AuthenticatedSession(accountId, NOW));
        }

        @Override
        public Optional<Account> currentAccount() {
            return store.findById(accountId).filter(Account::isEnabled);
        }
    }
}
