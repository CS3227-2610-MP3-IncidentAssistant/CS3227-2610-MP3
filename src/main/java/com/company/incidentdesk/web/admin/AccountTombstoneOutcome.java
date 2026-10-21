package com.company.incidentdesk.web.admin;

/** Stable outcomes for the security-sensitive account tombstone transaction. */
enum AccountTombstoneOutcome {
    TOMBSTONED,
    RESOURCE_UNAVAILABLE,
    INVALID_STATE,
    CONFLICT,
    PERSISTENCE_FAILURE
}
