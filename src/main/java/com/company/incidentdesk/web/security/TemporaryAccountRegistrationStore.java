package com.company.incidentdesk.web.security;

import java.time.Instant;
import java.util.UUID;

import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.web.security.TemporaryAccountRegistrationService.RegistrationOutcome;

/** Atomic persistence boundary for the temporary registration migration. */
interface TemporaryAccountRegistrationStore {
    RegistrationOutcome register(TemporaryAccountRegistration registration);
}

record TemporaryAccountRegistration(
        UUID accountId,
        String loginName,
        Role role,
        String passwordHash,
        UUID auditId,
        Instant occurredAt) {
}
