CREATE TABLE incident_resolutions (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    incident_id UUID NOT NULL REFERENCES incidents(id),
    cycle_number INTEGER NOT NULL CHECK (cycle_number > 0),
    resolved_by UUID NOT NULL REFERENCES accounts(id),
    resolved_at TIMESTAMPTZ NOT NULL,
    remarks VARCHAR(2000) NOT NULL,
    CONSTRAINT incident_resolutions_cycle_unique UNIQUE (incident_id, cycle_number),
    CONSTRAINT incident_resolutions_remarks_length CHECK (char_length(remarks) BETWEEN 1 AND 2000),
    CONSTRAINT incident_resolutions_remarks_nonblank CHECK (
        char_length(translate(remarks,
            chr(9) || chr(10) || chr(11) || chr(12) || chr(13) ||
            chr(28) || chr(29) || chr(30) || chr(31) || chr(32) ||
            chr(5760) || chr(8192) || chr(8193) || chr(8194) || chr(8195) ||
            chr(8196) || chr(8197) || chr(8198) || chr(8200) || chr(8201) ||
            chr(8202) || chr(8232) || chr(8233) || chr(8287) || chr(12288),
            '')) > 0
    )
);
