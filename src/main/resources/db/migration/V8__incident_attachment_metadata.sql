CREATE TABLE incident_attachments (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    media_type VARCHAR(64) NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX incident_attachments_incident_created_idx
    ON incident_attachments (incident_id, created_at, id);

CREATE TABLE incident_comments (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES accounts(id),
    author_role VARCHAR(32) NOT NULL CHECK (author_role IN ('REPORTER', 'RESPONDER', 'ADMINISTRATOR')),
    comment_type VARCHAR(32) NOT NULL CHECK (comment_type IN ('ORDINARY', 'REOPEN_EXPLANATION')),
    text VARCHAR(4000) NOT NULL CHECK (char_length(text) BETWEEN 1 AND 4000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT incident_comments_nonblank CHECK (char_length(translate(text,
        chr(9) || chr(10) || chr(11) || chr(12) || chr(13) || chr(28) || chr(29) || chr(30) || chr(31) || chr(32) ||
        chr(5760) || chr(8192) || chr(8193) || chr(8194) || chr(8195) || chr(8196) || chr(8197) || chr(8198) ||
        chr(8200) || chr(8201) || chr(8202) || chr(8232) || chr(8233) || chr(8287) || chr(12288), '')) > 0)
);

CREATE INDEX incident_comments_incident_created_idx
    ON incident_comments (incident_id, created_at, id);

CREATE INDEX audit_events_incident_history_idx
    ON audit_events (target_identifier, occurred_at, sequence)
    WHERE target_type = 'INCIDENT' AND outcome = 'SUCCESS'
      AND action IN ('INCIDENT_SUBMITTED', 'INCIDENT_CLAIMED', 'INCIDENT_RESOLVED',
                     'INCIDENT_REOPENED', 'INCIDENT_HANDED_OFF', 'INCIDENT_WITHDRAWN');
