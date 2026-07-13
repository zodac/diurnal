import { Client } from "pg"

// Connection to the E2E test database (the same DB the app under test uses — :5432 by default,
// per the diurnal-db-dev service in docker-compose.dev.yml). Overridable via env so it works in CI / other setups.
const DB_CONFIG = {
    host: process.env.TEST_DB_HOST ?? "localhost",
    port: Number(process.env.TEST_DB_PORT ?? 5432),
    user: process.env.TEST_DB_USER ?? "diurnal_user",
    password: process.env.TEST_DB_PASSWORD ?? "diurnal_password",
    database: process.env.TEST_DB_NAME ?? "diurnal_db",
}

// The users.role storage values, mirrored from the backend's single source of truth
// (net.zodac.diurnal.user.Role.Values — ADMIN/USER). These are a fixed schema contract, not an
// environment-varying setting, so they are constants rather than env overrides; this file cannot
// import the Java enum, so if a role's stored value ever changes there, change it here to match.
const ROLE_ADMIN = "admin"
const ROLE_USER = "user"

/**
 * Make an already-registered user the SOLE admin: promote it and demote every other admin.
 *
 * Admin status is otherwise only granted by RoleAssigner to the very first user ever registered,
 * which is far too fragile to rely on from tests (it depends on spec execution order and a pristine
 * DB) — and a stray second admin (e.g. the bootstrap first-user) would break the "last administrator"
 * guard tests. Forcing exactly one admin makes those tests deterministic regardless of prior state.
 *
 * Must be called BEFORE the user logs in: roles are baked into the session at authentication time
 * (PasswordIdentityProvider), so a role change only takes effect on the next login.
 */
export async function ensureSoleAdmin(email: string): Promise<void> {
    const client = new Client(DB_CONFIG)
    await client.connect()
    try {
        const promoted = await client.query(`UPDATE users SET role = '${ROLE_ADMIN}' WHERE email = $1`, [email])
        if (promoted.rowCount === 0) {
            throw new Error(`ensureSoleAdmin: no user found with email ${email} (register them first)`)
        }
        await client.query(`UPDATE users SET role = '${ROLE_USER}' WHERE email <> $1 AND role = '${ROLE_ADMIN}'`, [email])
    } finally {
        await client.end()
    }
}

/**
 * Force an already-registered user to be a plain (non-admin) 'user'.
 *
 * The first user ever registered is promoted to admin by RoleAssigner ("first user = admin"),
 * so on a pristine tmpfs DB the per-spec/per-project fixture user can non-deterministically end
 * up an admin depending on which worker registered first. The admin access-control tests need a
 * guaranteed non-admin, so they demote explicitly before login.
 *
 * Must be called BEFORE the user logs in: roles are baked into the session at authentication time
 * (PasswordIdentityProvider), so a role change only takes effect on the next login.
 */
export async function ensureNotAdmin(email: string): Promise<void> {
    const client = new Client(DB_CONFIG)
    await client.connect()
    try {
        const updated = await client.query(`UPDATE users SET role = '${ROLE_USER}' WHERE email = $1`, [email])
        if (updated.rowCount === 0) {
            throw new Error(`ensureNotAdmin: no user found with email ${email} (register them first)`)
        }
    } finally {
        await client.end()
    }
}
