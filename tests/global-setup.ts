import { bootstrapAdmin } from "./helpers/fixtures"

/**
 * Playwright globalSetup: create the initial administrator account before any spec runs.
 *
 * The API and OIDC user-creation paths are blocked until the first (admin) account has been created
 * locally through the web setup flow. The per-spec fixtures register their users via the API, so that
 * initial account must exist first — this hook creates it via the web /register form. It runs once per
 * test run (not per project/worker) and is idempotent.
 */
async function globalSetup(): Promise<void> {
    await bootstrapAdmin()
}

export default globalSetup
