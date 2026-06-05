CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255),
    oidc_subject    VARCHAR(255),
    oidc_issuer     VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT users_email_unique UNIQUE (email)
);

-- Composite index prevents duplicate OIDC accounts across different issuers
CREATE UNIQUE INDEX idx_users_oidc
    ON users (oidc_issuer, oidc_subject)
    WHERE oidc_subject IS NOT NULL;
