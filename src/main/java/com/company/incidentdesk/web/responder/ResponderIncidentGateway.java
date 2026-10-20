package com.company.incidentdesk.web.responder;

import java.util.Optional;
import java.util.UUID;

import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ListCriteria;

/** Persistence boundary for the authorized Responder queue projection. */
interface ResponderIncidentGateway {
    Optional<IncidentPage> findQueue(String loginName, ListCriteria criteria);

    ResponderIncidentService.ClaimResult claim(String loginName, UUID incidentId);

    ResponderIncidentService.ResolutionResult resolve(
            String loginName, UUID incidentId, UUID requestId, String remarks);

    Optional<ResponderIncidentService.IncidentDetail> findDetail(String loginName, UUID incidentId);
}
