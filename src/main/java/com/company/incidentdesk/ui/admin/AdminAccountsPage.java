package com.company.incidentdesk.ui.admin;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.company.incidentdesk.application.account.AccountDirectoryService;
import com.company.incidentdesk.application.account.AccountDeletionService;
import com.company.incidentdesk.application.account.AccountPasswordResetService;
import com.company.incidentdesk.application.account.PromotionRequestService;
import com.company.incidentdesk.application.account.ResponderAccessService;
import com.company.incidentdesk.application.audit.AuditActorLabelResolver;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.PromotionRequestStatus;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.ui.shared.components.ActionStyle;
import com.company.incidentdesk.ui.shared.components.FeedbackType;
import com.company.incidentdesk.ui.shared.components.SemanticTone;
import com.company.incidentdesk.ui.shared.components.UiComponents;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Administrator account directory. */
public final class AdminAccountsPage extends BorderPane {
    private final AccountDirectoryService directory;
    private final AccountDeletionService deletion;
    private final AccountPasswordResetService passwordResets;
    private final ResponderAccessService responderAccess;
    private final PromotionRequestService promotionRequests;
    private final VBox content = new VBox(16);
    private final CheckBox pendingOnly = new CheckBox("Show only accounts with pending promotion requests");
    private List<Account> loadedAccounts = List.of();
    private Map<AccountId, ResponderPromotionRequest> pendingRequestsByRequester = Map.of();
    private boolean accountsAvailable;

    public AdminAccountsPage(AccountDirectoryService directory, AccountDeletionService deletion,
            AccountPasswordResetService passwordResets, ResponderAccessService responderAccess,
            PromotionRequestService promotionRequests) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.deletion = Objects.requireNonNull(deletion, "deletion");
        this.passwordResets = Objects.requireNonNull(passwordResets, "passwordResets");
        this.responderAccess = Objects.requireNonNull(responderAccess, "responderAccess");
        this.promotionRequests = Objects.requireNonNull(promotionRequests, "promotionRequests");
        Label title = new Label("Accounts");
        title.getStyleClass().add("page-title");
        pendingOnly.setId("pending-only-toggle");
        pendingOnly.setAccessibleText("Show only accounts with pending promotion requests");
        pendingOnly.selectedProperty().addListener((observable, was, is) -> {
            if (accountsAvailable) {
                renderTable();
            }
        });
        content.getChildren().addAll(title, pendingOnly);
        content.setPadding(new Insets(24));
        refresh();
        setCenter(content);
    }

    private void refresh() {
        content.getChildren().remove(2, content.getChildren().size());
        var result = directory.listAccounts();
        if (result.isSuccess()) {
            accountsAvailable = true;
            loadedAccounts = result.value().orElseThrow();
            pendingRequestsByRequester = promotionRequests.visibleHistory().stream()
                    .filter(request -> request.status() == PromotionRequestStatus.PENDING)
                    .collect(Collectors.toMap(ResponderPromotionRequest::requesterId, Function.identity(),
                            (first, second) -> first, HashMap::new));
            renderTable();
        } else {
            accountsAvailable = false;
            loadedAccounts = List.of();
            pendingRequestsByRequester = Map.of();
            content.getChildren().add(UiComponents.feedback(
                    "Accounts unavailable", "Sign in as an administrator and try again.", FeedbackType.ERROR));
        }
    }

    private void renderTable() {
        content.getChildren().remove(2, content.getChildren().size());
        List<Account> visible = pendingOnly.isSelected()
                ? loadedAccounts.stream().filter(account -> pendingRequestsByRequester.containsKey(account.id())).toList()
                : loadedAccounts;
        content.getChildren().add(UiComponents.panel("Users", accountTable(visible)));
    }

    private TableView<Account> accountTable(List<Account> accounts) {
        TableView<Account> table = new TableView<>();
        table.setAccessibleText("User accounts");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Account, String> login = column("Login name", account -> account.isDeleted()
                ? AuditActorLabelResolver.DELETED_ACCOUNT_LABEL : account.loginName());
        fixWidth(login, 145);
        TableColumn<Account, Account> role = nodeColumn("Role", account -> roleBadge(account.role()));
        fixWidth(role, 130);
        TableColumn<Account, Account> categories = nodeColumn("Responder categories", this::categoryBadges);
        fixWidth(categories, 280);
        table.getColumns().addAll(List.of(login, role, categories));
        table.getColumns().add(actionColumn());
        table.getItems().setAll(accounts);
        table.setPlaceholder(new Label("No user accounts found"));
        return table;
    }

    /** Locks a column to its content width so only the actions column absorbs extra table width. */
    private static void fixWidth(TableColumn<Account, ?> column, double width) {
        column.setPrefWidth(width);
        column.setMinWidth(width);
        column.setMaxWidth(width);
    }

    private Label roleBadge(Role role) {
        return switch (role) {
        case REPORTER -> UiComponents.badge("Reporter", SemanticTone.NEUTRAL);
        case RESPONDER -> UiComponents.badge("Responder", SemanticTone.INFO);
        case ADMINISTRATOR -> UiComponents.badge("Administrator", SemanticTone.WARNING);
        };
    }

    private Node categoryBadges(Account account) {
        if (account.responderAccess().isEmpty()) {
            return new Label("—");
        }
        HBox badges = new HBox(6);
        for (IncidentCategory category : IncidentCategory.values()) {
            if (account.responderAccess().permits(category)) {
                badges.getChildren().add(UiComponents.badge(category.displayName(), SemanticTone.INFO));
            }
        }
        badges.setAccessibleText("Responder categories: " + account.responderAccess().categories().stream()
                .map(IncidentCategory::displayName)
                .sorted()
                .collect(Collectors.joining(", ")));
        return badges;
    }

    private TableColumn<Account, Void> actionColumn() {
        TableColumn<Account, Void> column = new TableColumn<>("Actions");
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Button delete = UiComponents.action("Delete", ActionStyle.DANGER);
            private final Button reset = UiComponents.action("Reset password", ActionStyle.SECONDARY);
            private final Button categories = UiComponents.action("Configure categories", ActionStyle.SECONDARY);
            private final Button viewRequest = UiComponents.action("View request", ActionStyle.SECONDARY);
            private final HBox actions = new HBox(8, viewRequest, categories, reset, delete);
            {
                delete.setAccessibleText("Delete account");
                delete.setOnAction(event -> confirmDeletion(getTableView().getItems().get(getIndex())));
                reset.setAccessibleText("Reset account password");
                reset.setOnAction(event -> confirmPasswordReset(getTableView().getItems().get(getIndex())));
                categories.setAccessibleText("Configure responder categories");
                categories.setOnAction(event -> configureCategories(getTableView().getItems().get(getIndex())));
                viewRequest.setAccessibleText("View promotion request");
                viewRequest.setOnAction(event -> viewPromotionRequest(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                Account account = getTableView().getItems().get(getIndex());
                delete.setDisable(!deletion.canDelete(account.id()));
                reset.setDisable(!passwordResets.canReset(account.id()));
                delete.setAccessibleText("Delete account " + account.loginName());
                reset.setAccessibleText("Reset password for " + account.loginName());
                categories.setAccessibleText("Configure categories for " + account.loginName());
                boolean isResponder = account.role() == Role.RESPONDER && account.isEnabled();
                categories.setManaged(isResponder);
                categories.setVisible(isResponder);
                boolean hasPendingRequest = pendingRequestsByRequester.containsKey(account.id());
                viewRequest.setAccessibleText("View promotion request from " + account.loginName());
                viewRequest.setManaged(hasPendingRequest);
                viewRequest.setVisible(hasPendingRequest);
                setGraphic(actions);
            }
        });
        column.setPrefWidth(430);
        column.setMinWidth(430);
        return column;
    }

    private void viewPromotionRequest(Account account) {
        ResponderPromotionRequest request = pendingRequestsByRequester.get(account.id());
        if (request == null) {
            return;
        }
        String categoryList = request.requestedCategories().stream()
                .map(IncidentCategory::displayName)
                .sorted()
                .collect(Collectors.joining(", "));
        String requestedAt = UiComponents.localDateTimeFormatter().format(request.requestedAt());
        TextArea details = new TextArea(
                "Requester: " + account.loginName() + "\n"
                        + "Requested categories: " + categoryList + "\n"
                        + "Requested: " + requestedAt + "\n"
                        + "Comments: " + request.comments().orElse("(none)"));
        details.setId("promotion-request-details");
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(6);
        details.setAccessibleText("Promotion request details for " + account.loginName());

        ButtonType approve = new ButtonType("Approve selected categories", ButtonType.OK.getButtonData());
        ButtonType reject = new ButtonType("Reject", ButtonType.OK.getButtonData());
        Alert dialog = new Alert(Alert.AlertType.NONE, "", approve, reject, ButtonType.CANCEL);
        dialog.setTitle("Promotion request");
        dialog.setHeaderText("Responder promotion request from " + account.loginName());
        VBox categoryOptions = new VBox(6);
        Map<IncidentCategory, CheckBox> approvalCategories = new EnumMap<>(IncidentCategory.class);
        request.requestedCategories().stream().sorted().forEach(category -> {
            CheckBox categoryOption = new CheckBox(category.displayName());
            categoryOption.setId("promotion-category-" + category.name());
            approvalCategories.put(category, categoryOption);
            categoryOptions.getChildren().add(categoryOption);
        });
        Button approveButton = (Button) dialog.getDialogPane().lookupButton(approve);
        approveButton.setDisable(true);
        approvalCategories.values().forEach(option -> option.selectedProperty().addListener(
                (observable, wasSelected, isSelected) -> approveButton.setDisable(
                        approvalCategories.values().stream().noneMatch(CheckBox::isSelected))));
        dialog.getDialogPane().setContent(new VBox(10, details,
                new Label("Select the categories to grant"), categoryOptions));
        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.orElseThrow() == ButtonType.CANCEL) {
            return;
        }
        boolean approved = choice.orElseThrow() == approve;
        Set<IncidentCategory> selectedCategories = approved
                ? approvalCategories.entrySet().stream()
                        .filter(entry -> entry.getValue().isSelected())
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toUnmodifiableSet())
                : Set.of();
        var result = promotionRequests.decide(request.id(), approved, selectedCategories);
        if (result.isSuccess()) {
            refresh();
        } else {
            content.getChildren().add(UiComponents.feedback(
                    approved ? "Promotion request was not approved" : "Promotion request was not rejected",
                    "The request may no longer be pending. Refresh and try again.", FeedbackType.ERROR));
        }
    }

    private void configureCategories(Account account) {
        Map<IncidentCategory, CheckBox> checkboxes = new EnumMap<>(IncidentCategory.class);
        VBox options = new VBox(6);
        for (IncidentCategory category : IncidentCategory.values()) {
            CheckBox checkbox = new CheckBox(category.displayName());
            checkbox.setSelected(account.responderAccess().permits(category));
            checkboxes.put(category, checkbox);
            options.getChildren().add(checkbox);
        }
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.CANCEL, ButtonType.OK);
        dialog.setTitle("Configure categories");
        dialog.setHeaderText("Choose the incident categories " + account.loginName() + " can access as a responder");
        dialog.getDialogPane().setContent(options);
        if (dialog.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }

        Set<IncidentCategory> selected = checkboxes.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());

        var result = responderAccess.changeCategories(account.id(), selected);
        if (result.isSuccess()) {
            refresh();
        } else {
            content.getChildren().add(UiComponents.feedback("Categories were not updated",
                    "The account may no longer be available. Refresh and try again.", FeedbackType.ERROR));
        }
    }

    private void confirmPasswordReset(Account account) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Generate a one-time temporary password for " + account.loginName()
                        + "? Any earlier password will stop working immediately.",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.setTitle("Reset password");
        confirmation.setHeaderText("The temporary password expires in 24 hours");
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;

        var result = passwordResets.reset(account.id());
        var secret = result.takeTemporaryPassword();
        if (secret.isEmpty()) {
            content.getChildren().add(UiComponents.feedback("Password was not reset",
                    "The account may no longer be available. Refresh and try again.", FeedbackType.ERROR));
            return;
        }
        char[] password = secret.orElseThrow();
        try {
            TextField value = new TextField(new String(password));
            value.setEditable(false);
            value.setId("temporary-password");
            value.setAccessibleText("One-time temporary password for " + account.loginName());
            Alert displayed = new Alert(Alert.AlertType.INFORMATION);
            displayed.setTitle("Temporary password created");
            displayed.setHeaderText("Copy this password now — it will not be shown again");
            displayed.getDialogPane().setContent(new VBox(10,
                    new Label("Deliver it securely to " + account.loginName()
                            + ". They must replace it immediately after signing in."), value));
            displayed.showAndWait();
            value.clear();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void confirmDeletion(Account account) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + account.loginName() + "? Their login will be disabled while incident and audit history is retained.",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.setTitle("Delete account");
        confirmation.setHeaderText("This action cannot be undone");
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        var result = deletion.delete(account.id());
        if (result.isSuccess()) {
            refresh();
        } else {
            content.getChildren().add(UiComponents.feedback(
                    "Account was not deleted", "The account may already be deleted or no longer available.",
                    FeedbackType.ERROR));
        }
    }

    private TableColumn<Account, String> column(String title, Function<Account, String> value) {
        TableColumn<Account, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private TableColumn<Account, Account> nodeColumn(String title, Function<Account, Node> value) {
        TableColumn<Account, Account> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Account account, boolean empty) {
                super.updateItem(account, empty);
                setGraphic(empty || account == null ? null : value.apply(account));
                setText(null);
            }
        });
        return column;
    }
}
