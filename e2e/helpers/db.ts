import { Client } from 'pg';

// Connection to the E2E test database (the same DB the app under test uses — :5433 by default,
// per the test-db service in docker-compose.dev.yml). Overridable via env so it works in CI / other setups.
const DB_CONFIG = {
  host: process.env.TEST_DB_HOST || 'localhost',
  port: Number(process.env.TEST_DB_PORT || 5433),
  user: process.env.TEST_DB_USER || 'diurnal',
  password: process.env.TEST_DB_PASSWORD || 'diurnal',
  database: process.env.TEST_DB_NAME || 'diurnal_test',
};

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
  const client = new Client(DB_CONFIG);
  await client.connect();
  try {
    const promoted = await client.query("UPDATE users SET role = 'admin' WHERE email = $1", [email]);
    if (promoted.rowCount === 0) {
      throw new Error(`ensureSoleAdmin: no user found with email ${email} (register them first)`);
    }
    await client.query("UPDATE users SET role = 'user' WHERE email <> $1 AND role = 'admin'", [email]);
  } finally {
    await client.end();
  }
}
