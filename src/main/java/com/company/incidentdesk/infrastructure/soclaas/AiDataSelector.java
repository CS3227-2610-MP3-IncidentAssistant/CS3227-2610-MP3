package com.company.incidentdesk.infrastructure.soclaas;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.domain.incident.IncidentStatus;

/** Trusted role integrations must authorize and select consistently from canonical data. Defaults deny. */
public interface AiDataSelector {
    default Optional<ResponderData> responder(UUID actor, UUID incident) { return Optional.empty(); }
    default boolean responderMayView(UUID actor, UUID incident) { return false; }
    default Optional<List<Trend>> administrator(UUID actor, Instant from, Instant until) { return Optional.empty(); }

    record ResponderData(UUID incidentId, IncidentCategory category, String title, String description,
            IncidentStatus state, Instant createdAt, Stage stage, long stageElapsedSeconds, SloState sloState) {
        public enum Stage { QUEUE, IN_PROGRESS }
        public enum SloState { WITHIN_TARGET, OVERDUE, NOT_APPLICABLE }
        public ResponderData {
            AiRequest.text(title, 200);
            AiRequest.text(description, 10000);
            if (incidentId == null || category == null || state == null || createdAt == null
                    || stage == null || sloState == null || stageElapsedSeconds < 0
                    || (state == IncidentStatus.SUBMITTED && stage != Stage.QUEUE)
                    || (state == IncidentStatus.ASSIGNED && stage != Stage.IN_PROGRESS)
                    || (state != IncidentStatus.SUBMITTED && state != IncidentStatus.ASSIGNED)) {
                throw new SoclaasException(SoclaasException.Category.AI_REJECTED);
            }
        }
        @Override public String toString() { return "Authorized AI incident fields [content withheld]"; }
    }

    record Trend(IncidentCategory category, long incidentCount, long resolvedCount, long reopenedCount,
            long overdueCount, long averageResolutionSeconds) {
        public Trend {
            if (category == null || incidentCount < 0 || resolvedCount < 0 || reopenedCount < 0
                    || overdueCount < 0 || averageResolutionSeconds < 0) {
                throw new SoclaasException(SoclaasException.Category.AI_REJECTED);
            }
        }
    }
}
