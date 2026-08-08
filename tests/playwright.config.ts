import { defineConfig, devices } from "@playwright/test"

export default defineConfig({
    testDir: "./ui",
    // Create the initial admin locally before any spec runs; the per-spec API registrations depend on it.
    globalSetup: "./global-setup.ts",
    fullyParallel: false, // tests within a file stay sequential; parallelism is file/project-level
    // Unconditional, not CI-only: a stray `test.only` locally made the gate pass green having run a
    // single test, which is the one place the check is actually needed - CI runs from a clean checkout
    // that rarely carries one, whereas a working tree routinely does mid-debug.
    forbidOnly: true,
    // No retries, anywhere. A retry cannot rescue a spec that mutates its own fixture user and asserts
    // pristine state on the way in - the second attempt starts from the state the first one left, so it
    // fails deterministically - and where a retry DOES pass, it converts a real race into a green run
    // that nobody investigates. Every flake here is a bug in the spec (each one found so far has been a
    // missing DOM barrier), so the suite is held to passing first time.
    retries: 0,
    // 2 is deliberate, and measured: raising it to 4 made the suite BOTH slower (64s vs 61s) and red.
    // The specs share one app instance and one never-reset database, so more workers only add users to
    // the paginated admin list and contention to the shared fixtures - admin.spec.ts asserts on a
    // globally-shared admin row it expects to find on page 1, which extra concurrent registrations push
    // off. There is no throughput to win either: the suite is bound by fixture round-trips against a
    // single app, not by browser CPU. Raising this needs those global-invariant specs hardened first;
    // PW_WORKERS overrides it for experiments.
    workers: Number(process.env.PW_WORKERS ?? 2),
    // `open: "never"` is load-bearing, not a preference. The html reporter defaults to open:
    // "on-failure", which - when CI is unset, i.e. every local run - serves the report on :9323 and
    // BLOCKS until Ctrl-C. Chained into the `java` gate that is not a slow suite, it is a hang with no
    // timeout: the step waits forever on a run that has already finished. Same reasoning as
    // playwright.smoke.config.ts choosing `list`. View the report with `npm run report` instead.
    reporter: [["list"], ["html", { open: "never" }]],

    use: {
        baseURL: process.env.BASE_URL ?? "http://localhost:8080",
        trace: "on-first-retry",
        // Pin the browser clock to UTC so the page's notion of "today" (the calendar's today marker,
        // any client-side date math) matches the UTC server, regardless of the host timezone.
        timezoneId: "UTC",
        // Follow redirects (e.g. form auth) automatically
        extraHTTPHeaders: {},
    },

    projects: [
        {
            name: "chromium",
            use: { ...devices["Desktop Chrome"] },
        },
        {
            // Re-running EVERY spec at a phone viewport doubled the tier for very little signal: the three
            // specs excluded below assert only behaviour and navigation (no visibility, geometry or scroll
            // assertions at all), so a second pass at a narrower viewport exercises the same code paths.
            // Everything layout-bearing still runs on both — and note that the genuinely mobile-specific
            // behaviour (the hamburger menu) does not depend on this project at all: navbar.spec.ts drives
            // it with its own `test.use({ viewport })` override, so it is covered even under `chromium`.
            // Set PW_MOBILE_ALL=1 to restore the full duplicate pass.
            name: "mobile-chrome",
            use: { ...devices["Galaxy S24"] },
            testIgnore: process.env.PW_MOBILE_ALL === undefined
                ? ["auth.spec.ts", "stats.spec.ts", "not-found.spec.ts"]
                : [],
        },
    ],

    // Start the app automatically if BASE_URL is not already set.
    // Remove or comment out this block when running against docker-compose.
    // webServer: {
    //   command: 'mvn quarkus:dev -pl ..',
    //   url: 'http://localhost:8080/login',
    //   reuseExistingServer: true,
    //   timeout: 120_000,
    // },
})
