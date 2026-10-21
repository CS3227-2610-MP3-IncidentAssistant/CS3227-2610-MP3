package com.company.incidentdesk.web.admin;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.company.incidentdesk.domain.incident.IncidentCategory;

/** Coordinates bounded account directory reads and administrator account commands. */
final class AdministratorAccountService {
    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 100;

    private final AdministratorAccountStore store;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    AdministratorAccountService(AdministratorAccountStore store, Clock clock, Supplier<UUID> identifiers) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    AccountPage accounts(String administratorLoginName, int requestedPage, int requestedSize) {
        String actor = requireActor(administratorLoginName);
        int page = Math.max(0, requestedPage);
        int size = requestedSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(requestedSize, MAX_PAGE_SIZE);
        long offset = (long) page * size;
        AdministratorAccountStore.AccountDirectoryResult result = store.findAccounts(actor, offset, size);
        if (!result.authorized()) return AccountPage.unavailable();
        long totalPages = Math.max(1, (result.total() + size - 1) / size);
        return new AccountPage(true, result.accounts(), page, size, result.total(), totalPages);
    }

    AccountTombstoneOutcome tombstone(String administratorLoginName, UUID targetAccountId) {
        return store.tombstone(
                requireActor(administratorLoginName),
                Objects.requireNonNull(targetAccountId, "targetAccountId"),
                Objects.requireNonNull(identifiers.get(), "generated audit event identifier"),
                clock.instant());
    }

    ResponderCategoryAccessOutcome replaceResponderCategories(
            String administratorLoginName,
            UUID targetAccountId,
            Set<IncidentCategory> categories) {
        return store.replaceResponderCategories(
                requireActor(administratorLoginName),
                Objects.requireNonNull(targetAccountId, "targetAccountId"),
                Set.copyOf(Objects.requireNonNull(categories, "categories")),
                Objects.requireNonNull(identifiers.get(), "generated audit event identifier"),
                clock.instant());
    }

    private static String requireActor(String loginName) {
        Objects.requireNonNull(loginName, "administratorLoginName");
        if (loginName.isBlank()) throw new IllegalArgumentException("administratorLoginName must not be blank");
        return loginName;
    }

    record AccountPage(
            boolean authorized,
            List<AdministratorAccountStore.AccountDirectoryEntry> accounts,
            int page,
            int size,
            long totalElements,
            long totalPages) {
        AccountPage {
            accounts = List.copyOf(accounts);
        }

        static AccountPage unavailable() {
            return new AccountPage(false, List.of(), 0, DEFAULT_PAGE_SIZE, 0, 1);
        }
    }
}
