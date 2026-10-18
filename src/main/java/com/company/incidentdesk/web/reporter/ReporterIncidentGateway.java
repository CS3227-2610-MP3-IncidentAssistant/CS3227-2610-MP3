package com.company.incidentdesk.web.reporter;

import java.util.Optional;
import java.util.UUID;

import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.incident.Incident;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.IncidentPage;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.ListCriteria;
import com.company.incidentdesk.web.reporter.ReporterIncidentService.SubmissionResult;

/** Persistence boundary for Reporter-owned incident queries and audited submission. */
interface ReporterIncidentGateway {
    Optional<AccountId> findEnabledReporter(String loginName);

    IncidentPage findOwned(String loginName, ListCriteria criteria);

    SubmissionResult submit(
            String loginName,
            UUID submissionKey,
            Incident incident,
            UUID auditEventId);
}
