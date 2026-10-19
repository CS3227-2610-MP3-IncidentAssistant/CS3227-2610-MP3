ALTER TABLE ai_usage_control
    ADD COLUMN configuration_revision BIGINT NOT NULL DEFAULT 0 CHECK (configuration_revision >= 0),
    ADD COLUMN circuit_kind VARCHAR(16) NOT NULL DEFAULT 'CLOSED'
        CHECK (circuit_kind IN ('CLOSED', 'LIMITED', 'TRANSIENT', 'BLOCKED')),
    ADD COLUMN circuit_until TIMESTAMPTZ,
    ADD COLUMN circuit_generation BIGINT NOT NULL DEFAULT 0 CHECK (circuit_generation >= 0),
    ADD COLUMN transient_failures INTEGER NOT NULL DEFAULT 0 CHECK (transient_failures BETWEEN 0 AND 3),
    ADD COLUMN probe_until TIMESTAMPTZ,
    ADD COLUMN probe_owner UUID,
    ADD COLUMN refresh_owner UUID,
    ADD COLUMN refresh_revision BIGINT CHECK (refresh_revision > 0),
    ADD COLUMN refresh_until TIMESTAMPTZ,
    ADD CONSTRAINT ai_refresh_pair CHECK ((refresh_owner IS NULL) = (refresh_until IS NULL)),
    ADD CONSTRAINT ai_refresh_revision CHECK ((refresh_owner IS NULL) = (refresh_revision IS NULL)),
    ADD CONSTRAINT ai_probe_pair CHECK ((probe_owner IS NULL) = (probe_until IS NULL)),
    ADD CONSTRAINT ai_circuit_deadline CHECK
        ((circuit_kind IN ('LIMITED', 'TRANSIENT')) = (circuit_until IS NOT NULL));

ALTER TABLE ai_usage_requests
    ADD COLUMN actor_role VARCHAR(32) CHECK (actor_role IN ('REPORTER', 'RESPONDER', 'ADMINISTRATOR')),
    ADD COLUMN gateway_revision BIGINT CHECK (gateway_revision > 0),
    ADD COLUMN gateway_generation BIGINT CHECK (gateway_generation >= 0),
    ADD COLUMN gateway_outcome VARCHAR(32) CHECK (gateway_outcome IN ('SUCCESS', 'AI_RATE_LIMITED',
        'AI_BUDGET_EXHAUSTED', 'AI_TIMEOUT', 'AI_UNAVAILABLE', 'AI_REJECTED', 'AI_INVALID_RESPONSE',
        'AI_OUTPUT_TOO_LARGE')),
    ADD COLUMN gateway_outcome_audited BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ai_gateway_identity CHECK ((gateway_revision IS NULL) = (actor_role IS NULL)),
    ADD CONSTRAINT ai_gateway_generation CHECK ((gateway_revision IS NULL) = (gateway_generation IS NULL));
