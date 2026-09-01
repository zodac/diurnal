-- Server-side session store, replacing the stateless JWT (REST API) and the stateless encrypted
-- form-auth cookie (web UI). A login mints a random opaque token; only its SHA-256 hash is stored
-- here, so a read-only leak of this table yields no usable sessions. Identity is resolved per request
-- by hashing the presented token (cookie or Bearer) and looking it up; roles are read live from the
-- users row, so this table intentionally holds no role/permission state. Revocation = deleting a row.
CREATE TABLE sessions (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash   BYTEA       NOT NULL,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auth_source  VARCHAR(16) NOT NULL,               -- 'password' | 'oidc'
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), -- bumped per request; drives the idle timeout
    expires_at   TIMESTAMPTZ NOT NULL,               -- absolute cap (created_at + absolute lifetime)
    user_agent   TEXT,                               -- retained for a future "active sessions" view
    client_ip    VARCHAR(64),
    CONSTRAINT sessions_token_hash_unique UNIQUE (token_hash)
);

-- Revoke-all / revoke-others for a user, and the sweeper's expiry pruning.
CREATE INDEX idx_sessions_user_id ON sessions (user_id);
CREATE INDEX idx_sessions_expires_at ON sessions (expires_at);
