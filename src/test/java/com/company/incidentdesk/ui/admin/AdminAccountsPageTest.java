package com.company.incidentdesk.ui.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.company.incidentdesk.application.account.AccountDeletionService;
import com.company.incidentdesk.application.account.AccountDirectoryService;
import com.company.incidentdesk.application.account.AccountPasswordResetService;
import com.company.incidentdesk.application.account.AccountRegistrationService;
import com.company.incidentdesk.application.account.PromotionRequestService;
import com.company.incidentdesk.application.account.ResponderAccessService;
import com.company.incidentdesk.application.audit.AuditEventFactory;
import com.company.incidentdesk.application.authorization.AccountAuthorizationPolicy;
import com.company.incidentdesk.application.event.ApplicationEvent;
import com.company.incidentdesk.application.session.AuthenticatedSession;
import com.company.incidentdesk.application.session.AuthenticationResult;
import com.company.incidentdesk.application.session.SessionService;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.PromotionRequestStatus;
import com.company.incidentdesk.domain.account.ResponderAccess;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.audit.AuditEventId;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.persistence.file.LocalApplicationStore;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableView;
import javafx.stage.Window;

class AdminAccountsPageTest {
    private static final Instant NOW = Instant.parse("2026-09-25T01:00:00Z");
    private static final AccountId ADMIN_ID = id(1);
    private static final AccountId REPORTER_ID = id(2);

    @TempDir
    Path directory;

    @BeforeAll
    static void startJavaFx() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test
    void togglingPendingOnlyFiltersToAccountsWithPendingRequests() throws Exception {
        onFx(() -> {
            try (LocalApplicationStore store = initializedStore()) {
                TestSession sessions = new TestSession(admin());
                PromotionRequestId requestId = new PromotionRequestId(new UUID(0, 50));
                PromotionRequestService promotionRequests = promotionRequestService(store, sessions, requestId);
                submitPendingRequestAsReporter(store, sessions, promotionRequests);

                AdminAccountsPage page = new AdminAccountsPage(
                        new AccountDirectoryService(new AccountAuthorizationPolicy(sessions), store),
                        new AccountDeletionService(sessions, new AccountAuthorizationPolicy(sessions), store,
                                auditFactory()),
                        passwordResetService(store, sessions),
                        new ResponderAccessService(sessions, new AccountAuthorizationPolicy(sessions), store, store,
                                auditFactory(), event -> { }),
                        promotionRequests);
                new Scene(page, 1200, 720);
                page.applyCss();
                page.layout();

                TableView<?> table = tableView(page);
                assertEquals(2, table.getItems().size());

                CheckBox toggle = (CheckBox) page.lookup("#pending-only-toggle");
                toggle.setSelected(true);
                page.layout();

                TableView<?> filtered = tableView(page);
                assertEquals(1, filtered.getItems().size());
                assertEquals(REPORTER_ID, ((Account) filtered.getItems().get(0)).id());

                toggle.setSelected(false);
                page.layout();
                assertEquals(2, tableView(page).getItems().size());
                return null;
            }
        });
    }

    @Test
    void viewRequestApprovalGrantsOnlySelectedCategoriesAndClearsPendingRequest() throws Exception {
        try (LocalApplicationStore store = initializedStore()) {
            TestSession sessions = new TestSession(admin());
            PromotionRequestId requestId = new PromotionRequestId(new UUID(0, 51));
            PromotionRequestService promotionRequests = promotionRequestService(store, sessions, requestId);
            submitPendingRequestAsReporter(store, sessions, promotionRequests);

            AdminAccountsPage page = onFx(() -> {
                AdminAccountsPage created = new AdminAccountsPage(
                        new AccountDirectoryService(new AccountAuthorizationPolicy(sessions), store),
                        new AccountDeletionService(sessions, new AccountAuthorizationPolicy(sessions), store,
                                auditFactory()),
                        passwordResetService(store, sessions),
                        new ResponderAccessService(sessions, new AccountAuthorizationPolicy(sessions), store, store,
                                auditFactory(), event -> { }),
                        promotionRequests);
                new Scene(created, 1200, 720);
                created.applyCss();
                created.layout();
                return created;
            });

            Button viewRequest = onFx(() -> onlyVisibleViewRequestButton(page));
            assertTrue(viewRequest.getAccessibleText().contains("reporter"));
            Platform.runLater(viewRequest::fire);

            Window detailsDialog = waitForWindowWithLookup("#promotion-request-details");
            assertTrue(onFx(() -> ((javafx.scene.control.TextArea) detailsDialog.getScene()
                    .lookup("#promotion-request-details")).getText()).contains("reporter"));
            assertTrue(onFx(() -> buttonByText(detailsDialog, "Approve selected categories").isDisable()));
            onFx(() -> {
                ((CheckBox) detailsDialog.getScene().getRoot().lookup("#promotion-category-IT")).setSelected(true);
                return null;
            });
            assertFalse(onFx(() -> buttonByText(detailsDialog, "Approve selected categories").isDisable()));
            Platform.runLater(() -> clickButtonByText(detailsDialog, "Approve selected categories"));

            waitUntil(() -> onFx(() -> store.findById(REPORTER_ID).orElseThrow().role() == Role.RESPONDER));
            onFx(() -> {
                page.layout();
                return null;
            });
            assertEquals(Role.RESPONDER, store.findById(REPORTER_ID).orElseThrow().role());
            assertEquals(PromotionRequestStatus.APPROVED,
                    store.findPromotionRequest(requestId).orElseThrow().status());
            assertEquals(Set.of(IncidentCategory.IT), store.findById(REPORTER_ID).orElseThrow()
                    .responderAccess().categories());
            assertEquals(Set.of(IncidentCategory.IT), store.findPromotionRequest(requestId).orElseThrow()
                    .approvedCategories().orElseThrow());
            assertTrue(onFx(() -> visibleViewRequestAccessibleTexts(page)).isEmpty());
        }
    }

    private static Window waitForWindowWithLookup(String selector) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Optional<Window> found = onFx(() -> Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .filter(window -> window.getScene() != null && window.getScene().lookup(selector) != null)
                    .findFirst());
            if (found.isPresent()) {
                return found.orElseThrow();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No showing window matched " + selector + " within timeout");
    }

    private static void waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Condition not met within timeout");
    }

    /**
     * Returns the distinct accessible-text labels of currently visible "View request" buttons.
     *
     * <p>The table's virtual flow may keep more than one cell instance bound to the same
     * account, so callers must compare distinct accessible labels rather than raw button counts.</p>
     */
    private static Set<String> visibleViewRequestAccessibleTexts(AdminAccountsPage page) {
        return page.lookupAll(".button").stream()
                .filter(node -> node instanceof Button button && "View request".equals(button.getText())
                        && button.isVisible())
                .map(node -> ((Button) node).getAccessibleText())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Button onlyVisibleViewRequestButton(AdminAccountsPage page) {
        Set<String> labels = visibleViewRequestAccessibleTexts(page);
        assertEquals(Set.of("View promotion request from reporter"), labels);
        return page.lookupAll(".button").stream()
                .filter(node -> node instanceof Button button && "View request".equals(button.getText())
                        && button.isVisible())
                .map(Button.class::cast)
                .findFirst().orElseThrow();
    }

    private static TableView<?> tableView(AdminAccountsPage page) {
        return (TableView<?>) page.lookup(".table-view");
    }

    private static void clickButtonByText(Window window, String text) {
        buttonByText(window, text).fire();
    }

    private static Button buttonByText(Window window, String text) {
        return (Button) window.getScene().getRoot().lookupAll(".button").stream()
                .filter(node -> node instanceof Button button && text.equals(button.getText()))
                .findFirst().orElseThrow();
    }

    private void submitPendingRequestAsReporter(LocalApplicationStore store, TestSession sessions,
            PromotionRequestService promotionRequests) {
        sessions.account = store.findById(REPORTER_ID).orElseThrow();
        assertTrue(promotionRequests.submit(Set.of(IncidentCategory.IT, IncidentCategory.FACILITIES),
                Optional.of("please")).isSuccess());
        sessions.account = store.findById(ADMIN_ID).orElseThrow();
    }

    private PromotionRequestService promotionRequestService(
            LocalApplicationStore store, TestSession sessions, PromotionRequestId requestId) {
        List<ApplicationEvent> events = new ArrayList<>();
        return new PromotionRequestService(sessions, new AccountAuthorizationPolicy(sessions), store, store,
                auditFactory(), Clock.fixed(NOW, ZoneOffset.UTC), () -> requestId, events::add);
    }

    private AccountPasswordResetService passwordResetService(LocalApplicationStore store, TestSession sessions) {
        AccountRegistrationService registrations = new AccountRegistrationService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        return new AccountPasswordResetService(sessions, new AccountAuthorizationPolicy(sessions), registrations,
                store, auditFactory(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private LocalApplicationStore initializedStore() {
        LocalApplicationStore store = new LocalApplicationStore(directory);
        store.create(admin());
        store.create(reporter());
        return store;
    }

    private static Account admin() {
        return new Account(ADMIN_ID, "admin", Role.ADMINISTRATOR, AccountStatus.ENABLED, ResponderAccess.NONE);
    }

    private static Account reporter() {
        return new Account(REPORTER_ID, "reporter", Role.REPORTER, AccountStatus.ENABLED, ResponderAccess.NONE);
    }

    private static AuditEventFactory auditFactory() {
        AtomicLong ids = new AtomicLong(100);
        return new AuditEventFactory(Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new AuditEventId(new UUID(0, ids.incrementAndGet())));
    }

    private static AccountId id(long value) {
        return new AccountId(new UUID(0, value));
    }

    private static <T> T onFx(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static final class TestSession implements SessionService {
        private Account account;

        TestSession(Account account) {
            this.account = account;
        }

        @Override
        public AuthenticationResult login(String loginName, char[] password) {
            return AuthenticationResult.REJECTED;
        }

        @Override
        public void logout() {
            account = null;
        }

        @Override
        public Optional<AuthenticatedSession> currentSession() {
            return account == null ? Optional.empty() : Optional.of(new AuthenticatedSession(account.id(), NOW));
        }

        @Override
        public Optional<Account> currentAccount() {
            return Optional.ofNullable(account);
        }
    }
}
