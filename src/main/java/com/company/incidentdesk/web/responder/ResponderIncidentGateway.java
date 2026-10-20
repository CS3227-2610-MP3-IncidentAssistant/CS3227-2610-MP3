package com.company.incidentdesk.web.responder;

import java.util.Optional;

import com.company.incidentdesk.web.responder.ResponderIncidentService.IncidentPage;
import com.company.incidentdesk.web.responder.ResponderIncidentService.ListCriteria;

/** Persistence boundary for the authorized Responder queue projection. */
interface ResponderIncidentGateway {
    Optional<IncidentPage> findQueue(String loginName, ListCriteria criteria);
}
