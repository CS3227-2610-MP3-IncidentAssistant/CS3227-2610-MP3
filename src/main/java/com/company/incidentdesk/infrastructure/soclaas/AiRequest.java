package com.company.incidentdesk.infrastructure.soclaas;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.infrastructure.soclaas.AiUsageLedger.Feature;

/** Feature inputs only: identities, roles, model settings and prompts are never request fields. */
public sealed interface AiRequest {
    record ReporterDraft(String title, IncidentCategory category, String description) implements AiRequest {
        public ReporterDraft {
            text(title, 200);
            text(description, 10000);
            if (category == null) { throw rejected(); }
        }
        @Override public String toString() { return "Reporter AI draft [content withheld]"; }
    }

    record ResponderIncident(UUID incidentId) implements AiRequest {
        public ResponderIncident { if (incidentId == null) { throw rejected(); } }
        @Override public String toString() { return "Responder AI request [content withheld]"; }
    }

    record AdministratorTrends(Instant from, Instant until) implements AiRequest {
        public AdministratorTrends {
            if (from == null || until == null || !from.isBefore(until)) { throw rejected(); }
        }
        @Override public String toString() { return "Administrator AI request [content withheld]"; }
    }

    record AdministratorInsight(String question, Instant requestInstant) implements AiRequest {
        public AdministratorInsight {
            text(question, 500);
            Objects.requireNonNull(requestInstant, "requestInstant");
        }
        @Override public String toString() { return "Administrator insight request [content withheld]"; }
    }

    default Feature feature() {
        return switch (this) {
        case ReporterDraft ignored -> Feature.REPORTER_DESCRIPTION;
        case ResponderIncident ignored -> Feature.RESPONDER_INCIDENT;
        case AdministratorTrends ignored -> Feature.ADMINISTRATOR_TRENDS;
        case AdministratorInsight ignored -> Feature.ADMINISTRATOR_INSIGHTS;
        };
    }

    static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) { throw rejected(); }
    }

    private static SoclaasException rejected() { return new SoclaasException(SoclaasException.Category.AI_REJECTED); }
}
