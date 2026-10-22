ALTER TABLE incident_comments
    ADD COLUMN request_id UUID;

CREATE UNIQUE INDEX incident_comments_request_id_idx
    ON incident_comments (request_id)
    WHERE request_id IS NOT NULL;
