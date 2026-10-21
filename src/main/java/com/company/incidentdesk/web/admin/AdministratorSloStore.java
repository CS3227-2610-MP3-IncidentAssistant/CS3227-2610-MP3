package com.company.incidentdesk.web.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL boundary for authorized, versioned administrator SLO configuration. */
interface AdministratorSloStore {
    ReadResult readAll(String administratorLoginName);

    ChangeOutcome change(
            String administratorLoginName,
            String category,
            UUID expectedVersionId,
            long timeToClaimMinutes,
            long timeInProgressMinutes,
            BigDecimal reopenRatePercent,
            UUID versionId,
            UUID auditEventId,
            Instant effectiveAt);

    enum ReadOutcome { AVAILABLE, RESOURCE_UNAVAILABLE, PERSISTENCE_FAILURE }

    enum ChangeOutcome { CHANGED, UNCHANGED, CONFLICT, RESOURCE_UNAVAILABLE, PERSISTENCE_FAILURE }

    record ReadResult(ReadOutcome outcome, List<SloVersion> versions) {
        public ReadResult {
            versions = List.copyOf(versions);
        }

        static ReadResult unavailable() {
            return new ReadResult(ReadOutcome.RESOURCE_UNAVAILABLE, List.of());
        }

        static ReadResult persistenceFailure() {
            return new ReadResult(ReadOutcome.PERSISTENCE_FAILURE, List.of());
        }
    }

    record SloVersion(
            UUID id,
            String category,
            long timeToClaimMinutes,
            long timeInProgressMinutes,
            BigDecimal reopenRatePercent,
            Instant effectiveAt,
            UUID changedBy) { }
}
