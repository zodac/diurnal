CREATE TABLE action_logs (
    id         UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID    NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    action_id  UUID    NOT NULL REFERENCES actions(id) ON DELETE CASCADE,
    log_date   DATE    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT action_logs_unique UNIQUE (user_id, action_id, log_date)
);

CREATE INDEX idx_action_logs_user_date ON action_logs (user_id, log_date);
