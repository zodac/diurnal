-- Durable audit log of per-IP auth lockouts (a lockout tallies BOTH failed logins and failed
-- registrations from one client IP; see IpThrottle). The live lockout ENFORCEMENT stays in-memory
-- (AttemptThrottle, resets on restart); this table is written only when a lockout trips, so an
-- administrator can review recent lockouts and manually clear one. A row is stamped unlocked_at /
-- unlocked_by when an administrator manually unlocks the IP; a naturally-expired lockout leaves its
-- row untouched (its status is derived from locked_until at read time). The admin view shows only the
-- last week of rows, and each new lockout prunes rows older than that window, so the table stays bounded.
CREATE TABLE ip_lockouts (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    ip_address    VARCHAR(64) NOT NULL,
    locked_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_until  TIMESTAMPTZ NOT NULL,               -- locked_at + the configured lockout duration
    failure_count INTEGER     NOT NULL,               -- failures tallied for the IP when the lockout tripped
    unlocked_at   TIMESTAMPTZ,                         -- set when an administrator manually unlocks; NULL otherwise
    unlocked_by   VARCHAR(255)                         -- email of the administrator who unlocked; NULL otherwise
);

-- The history view orders by locked_at (most recent first) filtered to the retention window, and the
-- per-lockout prune deletes on locked_at; both are served by this index.
CREATE INDEX idx_ip_lockouts_locked_at ON ip_lockouts (locked_at);
