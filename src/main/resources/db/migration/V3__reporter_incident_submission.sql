ALTER TABLE incidents
    ADD COLUMN submission_key UUID NULL;

CREATE UNIQUE INDEX incidents_reporter_submission_key_idx
    ON incidents (reporter_id, submission_key)
    WHERE submission_key IS NOT NULL;

ALTER TABLE incidents
    ADD CONSTRAINT incidents_title_length_check
    CHECK (char_length(title) <= 200) NOT VALID;
