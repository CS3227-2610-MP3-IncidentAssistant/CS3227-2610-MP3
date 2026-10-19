CREATE TABLE responder_promotion_requests (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES accounts(id),
    comments VARCHAR(2000),
    requested_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by UUID NULL REFERENCES accounts(id),
    decided_at TIMESTAMPTZ NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK ((status = 'PENDING') = (decided_by IS NULL)),
    CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

CREATE UNIQUE INDEX responder_promotion_one_pending_per_reporter_idx
    ON responder_promotion_requests (requester_id)
    WHERE status = 'PENDING';

CREATE INDEX responder_promotion_history_idx
    ON responder_promotion_requests (requester_id, requested_at DESC, id ASC);

CREATE INDEX responder_promotion_pending_order_idx
    ON responder_promotion_requests (requested_at ASC, id ASC)
    WHERE status = 'PENDING';

CREATE TABLE responder_promotion_requested_categories (
    request_id UUID NOT NULL REFERENCES responder_promotion_requests(id) ON DELETE CASCADE,
    category VARCHAR(64) NOT NULL CHECK (category IN ('IT', 'HUMAN_RELATIONS', 'FACILITIES')),
    PRIMARY KEY (request_id, category)
);

CREATE TABLE responder_promotion_approved_categories (
    request_id UUID NOT NULL,
    category VARCHAR(64) NOT NULL,
    PRIMARY KEY (request_id, category),
    FOREIGN KEY (request_id, category)
        REFERENCES responder_promotion_requested_categories(request_id, category)
        ON DELETE CASCADE
);

CREATE TABLE responder_category_access (
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category VARCHAR(64) NOT NULL CHECK (category IN ('IT', 'HUMAN_RELATIONS', 'FACILITIES')),
    granted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, category)
);
