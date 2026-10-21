package com.company.incidentdesk.web.admin;

/** Outcomes from replacing an enabled Responder's permitted categories. */
enum ResponderCategoryAccessOutcome {
    UPDATED,
    UNCHANGED,
    RESOURCE_UNAVAILABLE,
    PERSISTENCE_FAILURE
}
