ALTER TABLE ai_usage_requests
    DROP CONSTRAINT ai_usage_requests_feature_check,
    ADD CONSTRAINT ai_usage_requests_feature_check CHECK (feature IN
        ('REPORTER_DESCRIPTION', 'RESPONDER_INCIDENT', 'ADMINISTRATOR_TRENDS', 'ADMINISTRATOR_INSIGHTS'));
