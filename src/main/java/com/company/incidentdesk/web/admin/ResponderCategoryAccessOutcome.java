package com.company.incidentdesk.web.admin;

/** Outcomes from replacing an enabled Responder's permitted categories. */
enum ResponderCategoryAccessOutcome {
    UPDATED,
    UNCHANGED,
    CONFLICT,
    RESOURCE_UNAVAILABLE,
    PERSISTENCE_FAILURE
}
