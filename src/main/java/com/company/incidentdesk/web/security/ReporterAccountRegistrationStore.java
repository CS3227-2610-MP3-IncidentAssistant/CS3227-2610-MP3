package com.company.incidentdesk.web.security;

import java.time.Instant;
import java.util.UUID;

import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.web.security.ReporterAccountRegistrationService.RegistrationOutcome;

/** Atomic persistence boundary for public Reporter registration. */
interface ReporterAccountRegistrationStore {
    RegistrationOutcome register(ReporterAccountRegistration registration);
}

record ReporterAccountRegistration(
        UUID accountId,
        String loginName,
        Role role,
        String passwordHash,
        UUID auditId,
        Instant occurredAt) {
}
