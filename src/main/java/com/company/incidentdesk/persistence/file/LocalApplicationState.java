package com.company.incidentdesk.persistence.file;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.domain.audit.AuditEvent;
import com.company.incidentdesk.domain.comment.IncidentComment;
import com.company.incidentdesk.domain.incident.Incident;
import com.company.incidentdesk.domain.incident.IncidentId;
import com.company.incidentdesk.domain.slo.SloTargetVersion;
import com.company.incidentdesk.domain.slo.SloTargetVersionId;
import com.company.incidentdesk.application.account.PasswordCredential;
import com.company.incidentdesk.domain.attachment.AttachmentId;
import com.company.incidentdesk.domain.attachment.IncidentAttachment;

/** Immutable aggregate committed as one canonical application-data file. */
record LocalApplicationState(
        Map<AccountId, Account> accounts,
        Map<AccountId, PasswordCredential> credentials,
        Map<IncidentId, Incident> incidents,
        List<IncidentComment> comments,
        List<AuditEvent> auditEvents,
        Map<PromotionRequestId, ResponderPromotionRequest> promotionRequests,
        Map<SloTargetVersionId, SloTargetVersion> sloTargetVersions,
        Map<AttachmentId, IncidentAttachment> attachments,
        int schemaVersion) {
    LocalApplicationState {
        accounts = Map.copyOf(new LinkedHashMap<>(accounts));
        credentials = Map.copyOf(new LinkedHashMap<>(credentials));
        incidents = Map.copyOf(new LinkedHashMap<>(incidents));
        comments = List.copyOf(comments);
        auditEvents = List.copyOf(auditEvents);
        promotionRequests = Map.copyOf(new LinkedHashMap<>(promotionRequests));
        sloTargetVersions = Map.copyOf(new LinkedHashMap<>(sloTargetVersions));
        attachments = Map.copyOf(new LinkedHashMap<>(attachments));
        if (schemaVersion < 1 || schemaVersion > 2) {
            throw new IllegalArgumentException("Invalid attachment schema version");
        }
    }

    LocalApplicationState(Map<AccountId, Account> accounts, Map<AccountId, PasswordCredential> credentials,
            Map<IncidentId, Incident> incidents, List<IncidentComment> comments, List<AuditEvent> auditEvents,
            Map<SloTargetVersionId, SloTargetVersion> sloTargetVersions) {
        this(accounts, credentials, incidents, comments, auditEvents, Map.of(), sloTargetVersions, Map.of(), 2);
    }

    LocalApplicationState(Map<AccountId, Account> accounts, Map<AccountId, PasswordCredential> credentials,
            Map<IncidentId, Incident> incidents, List<IncidentComment> comments, List<AuditEvent> auditEvents,
            Map<SloTargetVersionId, SloTargetVersion> sloTargetVersions,
            Map<AttachmentId, IncidentAttachment> attachments, int schemaVersion) {
        this(accounts, credentials, incidents, comments, auditEvents, Map.of(), sloTargetVersions,
                attachments, schemaVersion);
    }

    static LocalApplicationState empty() {
        return new LocalApplicationState(Map.of(), Map.of(), Map.of(), List.of(), List.of(), Map.of());
    }
}
