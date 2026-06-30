import {defineConfig, devices} from '@playwright/test';

// Playwright config for the deployment-smoke suite — a small, time-robust, self-seeding black-box
// suite run against the REAL production image (docker-compose.smoke.yml) on the prod profile with a
// live Postgres. Kept separate from playwright.config.ts because it has a different testDir, must
// NOT auto-start a dev server (run-smoke.sh supplies the target via BASE_URL), and runs a single
// browser project (smoke is a thin pass/fail gate, not cross-device feature coverage).
export default defineConfig({
    testDir: './smoke',
    fullyParallel: false,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    workers: 1,
    // `list` (not `html`) so the run never spawns a report server that would hang a CI step.
    reporter: [['list']],

    use: {
        baseURL: process.env.BASE_URL || 'http://127.0.0.1:8082',
        trace: 'on-first-retry',
        // Pin the browser clock to UTC so "today" matches the TZ=UTC smoke server.
        timezoneId: 'UTC',
    },

    projects: [
        {
            name: 'chromium',
            use: {...devices['Desktop Chrome']},
        },
    ],
});
