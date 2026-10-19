-- One application key: every instance locks this row before changing capacity.
CREATE TABLE ai_usage_control (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    last_seen TIMESTAMPTZ,
    account_minute INTEGER NOT NULL DEFAULT 5 CHECK (account_minute BETWEEN 1 AND 5),
    account_hour INTEGER NOT NULL DEFAULT 30 CHECK (account_hour BETWEEN 1 AND 30),
    account_day INTEGER NOT NULL DEFAULT 100 CHECK (account_day BETWEEN 1 AND 100),
    global_minute INTEGER NOT NULL DEFAULT 60 CHECK (global_minute BETWEEN 1 AND 60),
    concurrent_limit INTEGER NOT NULL DEFAULT 8 CHECK (concurrent_limit BETWEEN 1 AND 8),
    daily_limit BIGINT NOT NULL DEFAULT 60000000 CHECK (daily_limit BETWEEN 1 AND 60000000),
    monthly_limit BIGINT NOT NULL DEFAULT 600000000 CHECK (monthly_limit BETWEEN 1 AND 600000000),
    lifetime_limit BIGINT NOT NULL DEFAULT 9223372036854775807 CHECK (lifetime_limit > 0),
    verified_at TIMESTAMPTZ,
    provider_minute INTEGER CHECK (provider_minute >= 0),
    daily_allowance BIGINT CHECK (daily_allowance >= 0),
    monthly_allowance BIGINT CHECK (monthly_allowance >= 0),
    lifetime_allowance BIGINT CHECK (lifetime_allowance >= 0),
    day_spent BIGINT CHECK (day_spent >= 0),
    month_spent BIGINT CHECK (month_spent >= 0),
    lifetime_spent BIGINT CHECK (lifetime_spent >= 0),
    model VARCHAR(128),
    input_price BIGINT CHECK (input_price >= 0),
    output_price BIGINT CHECK (output_price >= 0),
    CHECK (verified_at IS NULL OR (provider_minute IS NOT NULL AND daily_allowance IS NOT NULL
        AND monthly_allowance IS NOT NULL AND day_spent IS NOT NULL AND month_spent IS NOT NULL
        AND lifetime_spent IS NOT NULL AND model IS NOT NULL AND input_price IS NOT NULL AND output_price IS NOT NULL))
);
INSERT INTO ai_usage_control (id) VALUES (1);

CREATE TABLE ai_usage_requests (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    feature VARCHAR(32) NOT NULL CHECK (feature IN
        ('REPORTER_DESCRIPTION', 'RESPONDER_INCIDENT', 'ADMINISTRATOR_TRENDS')),
    model VARCHAR(128) NOT NULL,
    admitted_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    charge_through TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    input_bound INTEGER NOT NULL CHECK (input_bound BETWEEN 1 AND 8000),
    output_bound INTEGER NOT NULL CHECK (output_bound BETWEEN 1 AND 1200),
    input_price BIGINT NOT NULL CHECK (input_price >= 0),
    output_price BIGINT NOT NULL CHECK (output_price >= 0),
    input_used BIGINT CHECK (input_used BETWEEN 0 AND input_bound),
    output_used BIGINT CHECK (output_used BETWEEN 0 AND output_bound),
    reserved BIGINT NOT NULL CHECK (reserved >= 0),
    charged BIGINT NOT NULL CHECK (charged BETWEEN 0 AND reserved),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('IN_FLIGHT', 'RECONCILED', 'AMBIGUOUS', 'EXPIRED')),
    CHECK (lease_until > admitted_at AND charge_through = lease_until),
    CHECK ((outcome = 'IN_FLIGHT') = (finished_at IS NULL)),
    CHECK ((outcome = 'RECONCILED') = (input_used IS NOT NULL AND output_used IS NOT NULL)),
    CHECK (outcome = 'RECONCILED' OR charged = reserved)
);
CREATE INDEX ai_usage_account_time ON ai_usage_requests (account_id, admitted_at);
CREATE INDEX ai_usage_time ON ai_usage_requests (admitted_at);
CREATE INDEX ai_usage_charge_time ON ai_usage_requests (charge_through);
CREATE INDEX ai_usage_inflight ON ai_usage_requests (lease_until) WHERE outcome = 'IN_FLIGHT';
