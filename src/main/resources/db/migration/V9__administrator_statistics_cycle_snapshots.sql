ALTER TABLE incident_resolutions
    ADD COLUMN queue_entered_at TIMESTAMPTZ,
    ADD COLUMN first_assigned_at TIMESTAMPTZ,
    ADD COLUMN responder_at_resolution UUID REFERENCES accounts(id),
    ADD COLUMN reopened_at TIMESTAMPTZ;

UPDATE incident_resolutions resolution
   SET queue_entered_at = incident.queue_entered_at,
       first_assigned_at = incident.first_assigned_at,
       responder_at_resolution = resolution.resolved_by
  FROM incidents incident
 WHERE incident.id = resolution.incident_id;

ALTER TABLE incident_resolutions
    ALTER COLUMN queue_entered_at SET NOT NULL,
    ALTER COLUMN first_assigned_at SET NOT NULL,
    ALTER COLUMN responder_at_resolution SET NOT NULL,
    ADD CONSTRAINT incident_resolutions_timestamp_order CHECK (
        queue_entered_at <= first_assigned_at
        AND first_assigned_at <= resolved_at
        AND (reopened_at IS NULL OR reopened_at >= resolved_at)
    );

CREATE INDEX incident_resolutions_statistics_scope_idx
    ON incident_resolutions (resolved_at, responder_at_resolution, incident_id);
