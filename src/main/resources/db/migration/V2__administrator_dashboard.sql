CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    reporter_id UUID NOT NULL REFERENCES accounts(id),
    title VARCHAR(240) NOT NULL,
    description VARCHAR(10000) NOT NULL,
    category VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'ASSIGNED', 'RESOLVED', 'WITHDRAWN')),
    anonymous BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ NULL,
    withdrawn_at TIMESTAMPTZ NULL,
    queue_entered_at TIMESTAMPTZ NULL,
    first_assigned_at TIMESTAMPTZ NULL,
    latest_assigned_at TIMESTAMPTZ NULL,
    assignee_id UUID NULL REFERENCES accounts(id),
    version BIGINT NOT NULL DEFAULT 0,
    CHECK ((status = 'ASSIGNED') = (assignee_id IS NOT NULL))
);

CREATE INDEX incidents_created_id_idx ON incidents (created_at DESC, id ASC);
CREATE INDEX incidents_category_status_idx ON incidents (category, status);
CREATE INDEX incidents_reporter_idx ON incidents (reporter_id);
CREATE INDEX incidents_assignee_idx ON incidents (assignee_id);

CREATE TABLE slo_target_versions (
    id UUID PRIMARY KEY,
    category VARCHAR(64) NOT NULL,
    average_time_to_claim_minutes BIGINT NOT NULL CHECK (average_time_to_claim_minutes >= 0),
    average_time_in_progress_minutes BIGINT NOT NULL CHECK (average_time_in_progress_minutes >= 0),
    average_reopen_rate_percent NUMERIC(5,2) NOT NULL CHECK (
        average_reopen_rate_percent >= 0 AND average_reopen_rate_percent <= 100
    ),
    effective_at TIMESTAMPTZ NOT NULL,
    changed_by UUID NOT NULL REFERENCES accounts(id)
);

CREATE INDEX slo_target_versions_current_idx
    ON slo_target_versions (category, effective_at DESC, id ASC);
