package com.company.incidentdesk.web.admin;

import java.util.Optional;
import java.util.UUID;

interface AdministratorIncidentStore {
    Optional<AdministratorIncidentService.IncidentDetail> findDetail(String loginName, UUID incidentId);

    AdministratorIncidentService.CommentResult addComment(
            String loginName, UUID incidentId, UUID requestId, String text);

    AdministratorIncidentService.MutationResult assign(
            String loginName, UUID incidentId, long expectedVersion, UUID responderId);

    AdministratorIncidentService.MutationResult unassign(
            String loginName, UUID incidentId, long expectedVersion);

    AdministratorIncidentService.ResolutionResult resolve(
            String loginName, UUID incidentId, long expectedVersion, UUID requestId, String remarks);
}
