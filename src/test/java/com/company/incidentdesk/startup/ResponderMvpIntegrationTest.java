package com.company.incidentdesk.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.company.incidentdesk.application.account.AccountRegistrationService;
import com.company.incidentdesk.application.account.RegistrationResult;
import com.company.incidentdesk.application.result.ApplicationErrorCode;
import com.company.incidentdesk.application.session.AuthenticationResult;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.audit.AuditAction;
import com.company.incidentdesk.domain.audit.AuditEvent;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.domain.incident.IncidentId;
import com.company.incidentdesk.domain.incident.IncidentStatus;
import com.company.incidentdesk.persistence.AuditQuery;
import com.company.incidentdesk.persistence.AuditSortDirection;
import com.company.incidentdesk.persistence.IncidentQuery;
import com.company.incidentdesk.persistence.IncidentSort;
import com.company.incidentdesk.persistence.file.LocalApplicationStore;
import com.company.incidentdesk.ui.navigation.ApplicationNavigator;
import com.company.incidentdesk.ui.navigation.ApplicationRoute;
import com.company.incidentdesk.ui.navigation.AuthenticationPage;
import com.company.incidentdesk.ui.navigation.DefaultViewFactory;
import com.company.incidentdesk.ui.reporter.ReporterPage;
import com.company.incidentdesk.ui.responder.ResponderPage;
import com.company.incidentdesk.ui.shared.components.IncidentDetailState;
import com.company.incidentdesk.ui.shared.components.IncidentDetailView;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/** Real startup services, credential verification, navigation, and disk persistence; no user data. */
class ResponderMvpIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);
    @TempDir Path directory;
    private LocalApplicationStore store;
    private ApplicationContext context;
    private ApplicationNavigator navigator;
    private Scene scene;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @BeforeEach
    void setUp() throws Exception {
        openApplication();
        // Test-only bootstrap through the real registration service, never direct account mutation.
        register("admin", Role.ADMINISTRATOR);
        register("reporter", Role.REPORTER);
        register("candidate", Role.REPORTER);
        register("other", Role.REPORTER);
        promote("candidate", IncidentCategory.IT);
    }

    @AfterEach
    void closeApplication() throws Exception {
        onFx(() -> {
            if (navigator != null) navigator.close();
            if (scene != null) scene.setRoot(new StackPane());
            if (context != null) context.close();
            return null;
        });
    }

    @Test
    void realSubmissionClaimResolutionAndAuditSurviveRestart() throws Exception {
        login("reporter");
        onFx(() -> {
            assertTrue(nodes(scene.getRoot()).anyMatch(ReporterPage.class::isInstance));
            ((TextField) reporterField("incident-title")).setText("Printer outage");
            ((TextArea) reporterField("incident-description")).setText(" Paper remains jammed ");
            selectSubmissionCategory();
            ((Button) reporterField("submit-incident")).fire();
            return null;
        });
        awaitFx(() -> labels(scene.getRoot()).contains("Incident submitted"));
        IncidentId id = store.find(IncidentQuery.reporterOwned(accountId("reporter")),
                IncidentSort.queueOrder()).getFirst().id();
        login("candidate");
        awaitDashboardRows(1, 0);
        openDetail(id);
        onFx(() -> { button("Claim").fire(); return null; });
        awaitFx(() -> detail().state() instanceof IncidentDetailState.Ready ready
                && ready.model().summary().actions().resolve());
        onFx(() -> { button("Back to dashboard").fire(); return null; });
        awaitDashboardRows(0, 1);
        openDetail(id);
        onFx(() -> {
            button("Resolve").fire();
            ((TextArea) scene.lookup("#resolution-remarks")).setText(" Replaced roller\nTested printing. ");
            button("Confirm resolution").fire();
            return null;
        });
        awaitDashboardRows(0, 0);
        var beforeRestart = store.findById(id).orElseThrow();
        assertEquals(IncidentStatus.RESOLVED, beforeRestart.status());
        var resolution = beforeRestart.currentCycle().orElseThrow().resolution().orElseThrow();
        assertEquals(" Replaced roller\nTested printing. ", resolution.remarks());
        assertEquals(accountId("candidate"), resolution.resolvedBy());
        assertEquals(accountId("candidate"), resolution.responderAtResolution());
        List<AuditEvent> auditBeforeRestart = audits();
        assertEquals(1, auditCount(AuditAction.INCIDENT_CLAIMED));
        assertEquals(1, auditCount(AuditAction.INCIDENT_RESOLVED));
        assertTrue(auditBeforeRestart.stream().filter(event -> event.action() == AuditAction.INCIDENT_RESOLVED)
                .allMatch(event -> event.evidenceReference().isPresent()));
        closeApplication();
        openApplication();
        assertTrue(context.sessions().currentAccount().isEmpty());
        assertEquals(beforeRestart, store.findById(id).orElseThrow());
        assertEquals(auditBeforeRestart, audits());
        login("reporter");
        openDetail(id);
        onFx(() -> {
            assertTrue(labels(scene.getRoot()).contains(resolution.remarks()));
            return null;
        });
        login("candidate");
        awaitDashboardRows(0, 0);
        assertFalse(context.incidentDetails().detail(id).isSuccess());
    }

    @Test
    void currentAdminProvisioningControlsQueuesButRetainsExistingAssignment() throws Exception {
        IncidentId assigned = submit("Assigned printer");
        IncidentId queued = submit("Queued printer");
        login("candidate");
        assertTrue(context.incidents().claim(assigned).isSuccess());
        login("admin");
        assertTrue(context.responderAccess().changeCategories(accountId("candidate"), Set.of()).isSuccess());
        login("candidate");
        awaitDashboardRows(0, 1);
        assertFalse(context.incidents().claim(queued).isSuccess());
        assertFalse(context.incidentDetails().detail(queued).isSuccess());
        openDetail(assigned);
        onFx(() -> {
            assertFalse(button("Resolve").isDisabled());
            return null;
        });
        assertTrue(context.incidents().resolve(assigned, "Finished existing assignment").isSuccess());
        login("admin");
        assertTrue(context.responderAccess().changeCategories(accountId("candidate"), Set.of(IncidentCategory.IT))
                .isSuccess());
        login("candidate");
        awaitDashboardRows(1, 0);
        assertTrue(context.incidentDetails().detail(queued).isSuccess());
        assertEquals(2, auditCount(AuditAction.RESPONDER_ACCESS_CHANGED));
    }

    @Test
    void unauthorizedSessionsCannotReachOrMutateResponderWork() throws Exception {
        IncidentId id = submit("Private report");
        promote("other", IncidentCategory.FACILITIES);
        login("other");
        awaitDashboardRows(0, 0);
        int auditCount = audits().size();
        assertFalse(context.incidents().claim(id).isSuccess());
        onFx(() -> { navigator.openIncident(id); return null; });
        awaitFx(() -> detail().state() instanceof IncidentDetailState.Unavailable);
        onFx(() -> {
            assertFalse(labels(scene.getRoot()).contains("Private report"));
            navigator.navigate(ApplicationRoute.ADMIN_ACCOUNTS);
            assertTrue(labels(scene.getRoot()).contains("Administrator area unavailable"));
            return null;
        });
        assertFalse(context.responderAccess().changeCategories(accountId("other"), Set.of(IncidentCategory.IT))
                .isSuccess());
        login("reporter");
        assertFalse(context.incidents().responderDashboard(context.presentationMapper()).isSuccess());
        assertFalse(context.incidents().claim(id).isSuccess());
        onFx(() -> { navigator.logout(); navigator.openIncident(id); return null; });
        onFx(() -> { assertInstanceOf(AuthenticationPage.class, scene.getRoot()); return null; });
        assertFalse(context.incidentDetails().detail(id).isSuccess());
        assertFalse(context.incidents().claim(id).isSuccess());
        assertFalse(context.incidents().resolve(id, "Denied").isSuccess());
        assertEquals(auditCount, audits().size());
        assertEquals(IncidentStatus.SUBMITTED, store.findById(id).orElseThrow().status());
    }

    @Test
    void logoutAndReplacementSessionDiscardRetainedResponderContent() throws Exception {
        IncidentId id = submit("Responder-only detail");
        login("candidate");
        awaitDashboardRows(1, 0);
        List<TableView<?>> previousTables = onFx(this::tables);
        openDetail(id);
        IncidentDetailView previousDetail = onFx(this::detail);
        onFx(() -> { navigator.logout(); return null; });
        onFx(() -> {
            assertInstanceOf(AuthenticationPage.class, scene.getRoot());
            assertTrue(previousTables.stream().allMatch(table -> table.getItems().isEmpty()));
            assertTrue(previousDetail.state() instanceof IncidentDetailState.Unavailable);
            return null;
        });
        login("candidate");
        openDetail(id);
        IncidentDetailView replacedDetail = onFx(this::detail);
        // Replace the session without first using navigator.logout().
        assertEquals(AuthenticationResult.AUTHENTICATED,
                context.sessions().login("other", testPassword()));
        onFx(() -> { navigator.navigate(ApplicationRoute.DASHBOARD); return null; });
        onFx(() -> {
            assertTrue(nodes(scene.getRoot()).anyMatch(ReporterPage.class::isInstance));
            assertFalse(nodes(scene.getRoot()).anyMatch(ResponderPage.class::isInstance));
            assertTrue(replacedDetail.state() instanceof IncidentDetailState.Unavailable);
            return null;
        });
        assertFalse(context.incidentDetails().detail(id).isSuccess());
    }

    @Test
    void failedDiskWritePreservesIncidentAuditAndCanonicalBytesAcrossRestart() throws Exception {
        IncidentId id = submit("Cannot save claim");
        login("candidate");
        var original = store.findById(id).orElseThrow();
        List<AuditEvent> originalAudit = audits();
        byte[] originalBytes = Files.readAllBytes(directory.resolve(LocalApplicationStore.STATE_FILE_NAME));
        Path backup = directory.resolve(LocalApplicationStore.STATE_FILE_NAME + ".bak");
        Path savedBackup = directory.resolve("saved-test-backup");
        Files.move(backup, savedBackup);
        Files.createDirectory(backup);
        Path obstruction = Files.createFile(backup.resolve("test-write-obstruction"));
        try {
            var failed = context.incidents().claim(id);
            assertEquals(ApplicationErrorCode.PERSISTENCE_FAILURE, failed.error().orElseThrow().code());
            assertEquals(original, store.findById(id).orElseThrow());
            assertEquals(originalAudit, audits());
            // Do not print persisted bytes (which include credentials) if this assertion fails.
            assertTrue(Arrays.equals(originalBytes,
                    Files.readAllBytes(directory.resolve(LocalApplicationStore.STATE_FILE_NAME))));
        } finally {
            Files.delete(obstruction);
            Files.delete(backup);
            Files.move(savedBackup, backup);
        }
        closeApplication();
        openApplication();
        assertEquals(original, store.findById(id).orElseThrow());
        assertEquals(originalAudit, audits());
        login("candidate");
        assertTrue(context.incidents().claim(id).isSuccess());
    }

    private void openApplication() throws Exception {
        store = new LocalApplicationStore(directory);
        var registrations = new AccountRegistrationService(store, CLOCK);
        context = ApplicationContext.create(store, registrations, registrations, CLOCK);
        onFx(() -> {
            scene = new Scene(new StackPane());
            var views = new DefaultViewFactory(context.incidents(), context.presentationMapper(), context.sessions(),
                    context.accountDirectory(), context.accountDeletion(), context.passwordResets(),
                    context.responderAccess(), context.promotionRequests(), context.auditLog(),
                    context.sloConfigurations(), context.incidentDetails(), context.comments(), context.attachments(),
                    context.statistics());
            navigator = new ApplicationNavigator(scene, context.sessions(), context.notifications(), views,
                    context.registrations(), context.passwords());
            navigator.start();
            return null;
        });
    }

    private void register(String name, Role role) {
        assertEquals(RegistrationResult.REGISTERED, context.registrations().register(name, testPassword(), role));
    }

    private void login(String name) throws Exception {
        onFx(() -> { navigator.logout(); return null; });
        assertEquals(AuthenticationResult.AUTHENTICATED, context.sessions().login(name, testPassword()));
        onFx(() -> { navigator.start(); return null; });
    }

    private void promote(String name, IncidentCategory category) throws Exception {
        login(name);
        var request = context.promotionRequests().submit(Set.of(category), Optional.empty()).value().orElseThrow();
        login("admin");
        assertTrue(context.promotionRequests().decide(request.id(), true,
                request.requestedCategories()).isSuccess());
        assertEquals(Role.RESPONDER, store.findByLoginName(name).orElseThrow().role());
    }

    private IncidentId submit(String title) throws Exception {
        login("reporter");
        return context.incidents().submit(title, "Description", IncidentCategory.IT, false).value().orElseThrow().id();
    }

    private AccountId accountId(String name) { return store.findByLoginName(name).orElseThrow().id(); }

    private List<AuditEvent> audits() { return store.find(AuditQuery.all(), AuditSortDirection.OLDEST_FIRST); }

    private long auditCount(AuditAction action) {
        return audits().stream().filter(event -> event.action() == action).count();
    }

    private static char[] testPassword() { return "integration-test-only".toCharArray(); }

    @SuppressWarnings("unchecked")
    private void selectSubmissionCategory() {
        ((ComboBox<IncidentCategory>) reporterField("incident-category")).setValue(IncidentCategory.IT);
    }

    private Node reporterField(String id) {
        return nodes(scene.getRoot()).filter(node -> id.equals(node.getId())).findFirst().orElseThrow();
    }

    private void openDetail(IncidentId id) throws Exception {
        onFx(() -> { navigator.openIncident(id); return null; });
        awaitFx(() -> detail().state() instanceof IncidentDetailState.Ready);
    }

    private IncidentDetailView detail() {
        return nodes(scene.getRoot()).filter(IncidentDetailView.class::isInstance)
                .map(IncidentDetailView.class::cast).findFirst().orElseThrow();
    }

    private void awaitDashboardRows(int eligible, int assigned) throws Exception {
        awaitFx(() -> {
            List<TableView<?>> queues = tables();
            return queues.size() == 2 && !button("Refresh").isDisabled()
                    && queues.get(0).getItems().size() == eligible && queues.get(1).getItems().size() == assigned;
        });
    }

    private List<TableView<?>> tables() {
        return nodes(scene.getRoot()).filter(TableView.class::isInstance)
                .<TableView<?>>map(node -> (TableView<?>) node).toList();
    }

    private Button button(String text) {
        return nodes(scene.getRoot()).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static List<String> labels(Node node) {
        return nodes(node).filter(Label.class::isInstance).map(Label.class::cast).map(Label::getText).toList();
    }

    private static Stream<Node> nodes(Node node) {
        if (node instanceof ScrollPane scroll) return Stream.concat(Stream.of(node), nodes(scroll.getContent()));
        if (node instanceof Parent parent) return Stream.concat(Stream.of(node),
                parent.getChildrenUnmodifiable().stream().flatMap(ResponderMvpIntegrationTest::nodes));
        return Stream.of(node);
    }

    private static void awaitFx(BooleanSupplier condition) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Timeline timer = onFx(() -> {
            Timeline poll = new Timeline(new KeyFrame(Duration.millis(20), event -> {
                if (condition.getAsBoolean()) ready.countDown();
            }));
            poll.setCycleCount(Timeline.INDEFINITE);
            poll.play();
            return poll;
        });
        try {
            assertTrue(ready.await(15, TimeUnit.SECONDS), "Integration UI condition was not reached");
        } finally {
            onFx(() -> { timer.stop(); return null; });
        }
    }

    private static <T> T onFx(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
