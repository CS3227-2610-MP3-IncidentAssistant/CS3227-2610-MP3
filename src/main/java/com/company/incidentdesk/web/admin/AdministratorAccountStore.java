package com.company.incidentdesk.web.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL boundary for authorized account-directory reads and tombstones. */
interface AdministratorAccountStore {
    AccountDirectoryResult findAccounts(String administratorLoginName, long offset, int limit);

    AccountTombstoneOutcome tombstone(
            String administratorLoginName,
            UUID targetAccountId,
            UUID auditEventId,
            Instant occurredAt);

    record AccountDirectoryResult(boolean authorized, List<AccountDirectoryEntry> accounts, long total) {
        public AccountDirectoryResult {
            accounts = List.copyOf(accounts);
        }

        static AccountDirectoryResult unavailable() {
            return new AccountDirectoryResult(false, List.of(), 0);
        }
    }

    record AccountDirectoryEntry(UUID id, String loginName, String role, String status, List<String> categories) {
        public AccountDirectoryEntry {
            categories = List.copyOf(categories);
        }
    }
}
