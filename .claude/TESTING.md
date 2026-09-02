# Testing Tiers & Conventions

> The test pyramid (unit / `*IT` / E2E / deployment-smoke / performance-load), the integration-test base and
> deterministic-time rules. Extracted from `CLAUDE.md`; read before adding tests or touching a test tier.
> Reminder: the Maven build is unit + `*IT` (+ linters) ONLY - the E2E, smoke and perf tiers are chained onto the
> wrapper's `java`/`perf` steps, never wired into any `mvn` command.

### Testing conventions

**A helper only tests call belongs in `src/test`, however production-shaped it looks.** `SqlParameters` (the `:name`-placeholder extractor that
`ActionLogQueriesTest`/`NoteQueriesTest` use to pin each handwritten query's parameter surface) sat in `net.zodac.diurnal.log` and shipped in the
production jar for exactly that reason — no `src/main` caller, only test ones. It now lives in the root test package, since the query classes it
guards span more than one feature package. Nothing catches this automatically: Qodana's dead-code check sees the test callers and stays quiet, so it
is a review habit, not a gate.

Integration tests extend `IntegrationTestBase` (truncates `action_logs → actions → users` before each test). Helpers: `newUser()`, `newAction()`,
`newLog()`, `runInTx()`. Tests use `@TestSecurity`. The `test` profile forces `app.timezone=UTC`. Password hashing runs at minimal cost in tests:
seeded users (`newUser()`) get a cheap Argon2id hash whose parameters mirror the `test` profile's pinned `password.hash.argon2.*` values (so a seeded
login does not trigger a re-hash).

**Deterministic time:** `IntegrationTestBase` freezes `AppClock` in `@BeforeEach` to `FIXED_TODAY = 2026-06-15`, restoring in `@AfterEach`. Use
`freezeInstant(Instant, ZoneId)` for boundary cases (`freezeDate` is the base class's own, and is private — nothing outside it calls it, and the
Qodana gate reports any member that could be narrower). Unit tests pass a fixed `today` directly. Surefire/failsafe pin
`-Duser.timezone=UTC`. E2E specs use UTC date APIs (`setUTCDate`/`getUTCDate`/`toISOString`) and `timezoneId: 'UTC'` in Playwright.

**Deterministic language:** `playwright.config.ts` pins `locale: "en-GB"` (the app default — `Language.DEFAULT`/`quarkus.default-locale`), which
Playwright turns into the `Accept-Language` header every logged-out page render negotiates against (`Language.fromAcceptLanguageHeader`).
`fixtures.ts`'s `setupTestUser`/`pinLanguage` additionally force the persisted `User.language` to `"en-GB"` via `PATCH /api/v1/users/me` after
login, since a LOGGED-IN page reads that stored preference and ignores the header entirely — belt-and-braces alongside the entity column's own
`en-GB` default, so every literal-English assertion elsewhere in the suite (`admin.spec.ts`, `auth.spec.ts`, `actions.spec.ts`, `stats.spec.ts`,
`data-transfer.spec.ts`, ...) passes on purpose rather than by accident of an unstated default. Mind the British spelling this pin implies
(`"authorised"`, `"colour"`, ...) — an assertion written against the American `en-US` override will fail even though the string exists, just
under the other locale. `tests/ui/i18n.spec.ts` is the one spec that deliberately looks elsewhere: a smoke-level pass per offered language
(`en-GB`/`en-US`/`es-ES`/`ar-SA`/`ja-JP`), overriding the pin per `describe` block with `test.use({ locale })`, asserting `<html lang>`/`<html
dir>` and one real translated string on both the logged-out (`Accept-Language`) and logged-in (`User.language`) negotiation paths. It is excluded
from the `mobile-chrome` project's duplicate pass (`playwright.config.ts`'s `testIgnore`) alongside the other behaviour-only specs, for the same
reason they are. A missing translation key never surfaces as broken text to check for — Quarkus's `@MessageBundle` silently falls back to the
English default (`AppMessagesIT`), guarded at the unit tier by `AppMessageCoverageTest` (every offered locale's `.properties` file carries
exactly the full key set) — so there is nothing further for the E2E tier to assert there. Translated strings running 15-30% longer than their
English source (commonly Spanish) is a real risk for fixed-width UI, but not one an automated assertion catches well; it was instead swept
manually with real screenshots per language at both a desktop and a 390px mobile viewport (`Settings > Appearance`, the Settings "Action stats"
picker, the Actions table, the dashboard calendar toolbar) — see [`I18N.md`](I18N.md)'s "Test coverage" section for what that pass found and why the one place text
truncates (the Settings stats-picker row caption on mobile) is a pre-existing, tooltip-mitigated design already present in English, not a
translation-specific regression.

**Tier hygiene (`run-e2e.sh` / `run-smoke.sh` / `run-perf.sh`):** each runner owns its own resources and cleans up in BOTH directions. On the way out,
an `EXIT` + `INT`/`TERM`/`HUP` trap tears the stack down on success, on failure and on interruption (the signal traps matter: the `java` step signals a
tier when a sibling fails first, and an `EXIT`-only trap would be skipped). On the way IN, each runner also sweeps what a run killed *outright* left
behind — where no trap can fire: the E2E runner reaps the app JVM recorded in `tests/.e2e-app.pid` (gitignored, deliberately not under `target/`, which
`mvn clean` wipes before the tier starts), smoke and perf remove any container still labelled with their compose project, and perf drops stale
`.perf-state.*` scratch dirs. The E2E runner then **refuses to run** if its port is still served by something it did not start (typically a
`quarkus:dev` instance, which also uses 8081) rather than silently testing an unknown app — override with `E2E_HTTP_PORT`.

### Deployment-smoke tier (`tests/smoke/`)

The test pyramid has a fourth tier on top of unit / `*IT` / E2E: **deployment-smoke**, the only tier that runs the **actual production Docker image
** (distroless, jlink custom JRE, non-root UID 65532) rather than a full JDK. It exists because that runtime is now a real source of bugs none of the
lower tiers can see — e.g. a jlink module trimmed too far (the `java.rmi` boot failure), non-root write permissions, or a CSS-hash/favicon build-stage
desync.

- **Files:** `tests/docker-compose.smoke.yml` (isolated app+DB stack built from the `Dockerfile`), `tests/run-smoke.sh` (build → `up --wait` → run →
  trap-teardown), `tests/playwright.smoke.config.ts` (`testDir: ./smoke`, single chromium project), `tests/smoke/*.spec.ts`.
- **Runs the prod profile** against a live Postgres — so there is **NO frozen clock and NO seeded DB**. Smoke specs must **self-seed** and use only
  the app's own UTC "today" (`TZ=UTC` in the compose stack; browser pinned to UTC). Do **not** port frozen-time E2E specs here. Keep the suite small
  and image-focused (boot/health, hashed assets, one persisted round-trip through the server-side session store) — feature behaviour belongs in the
  E2E suite.
- **Isolation:** dedicated compose project (`-p diurnal-smoke`), ephemeral tmpfs DB, host port **8082**. Coexists with a running prod stack. (The app
  writes nothing to the filesystem now — session state is in Postgres — so no writable secrets mount is needed.)
- **CI wiring:** chained onto the `java` step of `.github/scripts/lint_and_tests.sh` (which runs `mvn clean install -Dall && tests/run-e2e.sh &&
  tests/run-smoke.sh`) — **not** part of any `mvn` command. The Maven build (`-Dall`) is unit + `*IT` + linters only; the E2E and deployment-smoke
  tiers were split out of the pom and are now chained after the Maven gate (smoke runs only if the build, ITs and E2E all passed). The image's own
  HEALTHCHECK drives `up --wait`, so a boot failure fails the step before Playwright starts. In the wrapper's auto-detect mode any app-code or
  Dockerfile/runtime change selects `java`; run the whole JVM gate explicitly with `.github/scripts/lint_and_tests.sh java`.

### Performance/load tier (`tests/perf/`)

The fifth tier: a k6 load suite that — like the smoke tier — runs against the **real production Docker image** (not the fast-jar), because the jlink
JRE's startup, JIT warm-up and memory profile are what actually ship. It exists to catch performance regressions (an N+1 query, an unindexed scan, a
jlink/heap bloat, a slow cold boot) that no functional tier can see. It is a standalone `lint_and_tests.sh` step (**not** part of any `mvn` command and
**not** chained onto the `java` gate — a perf regression must not fail the functional build, and vice versa), but it **is auto-detected on the SAME
file set as `java`** (any `src/`, `frontend/`, `pom.xml`, Dockerfile or java-config change — the things that plausibly move performance), **plus** the
perf suite's own files (`tests/perf/**`, `tests/run-perf.sh`, `tests/docker-compose.perf.yml`). It keys off `java`'s final detection outcome, so the
same comment-only / project version-bump suppression applies. Run it explicitly with **`.github/scripts/lint_and_tests.sh perf`**, scoped alongside
others (`… java,perf`), or via `tests/run-perf.sh <port> <projectRoot>` directly; `-f/--force` and a no-tag run include it like any other step.

- **Files:** `tests/docker-compose.perf.yml` (isolated app+DB stack from the `Dockerfile`), `tests/run-perf.sh` (build → boot-timing → seed → k6 load
  → trap-teardown), `tests/perf/seed.mjs` (k6 seeder) and `tests/perf/load.mjs` (k6 scenarios + thresholds). The k6 scripts are **`.mjs`** (ES
  modules, like `tests/measure.mjs`) so the CommonJS `javascript` lint step doesn't try to parse them; k6 itself accepts `.mjs`.
- **What it measures:** (1) **cold-boot** latency — container start → first `200 /api/v1/status` with readiness `UP` — plus post-boot RSS, asserted
  against `PERF_BOOT_BUDGET_S` / `PERF_RSS_MAX_MB` (RSS is best-effort: skipped if `docker stats` can't be parsed); (2) **steady-state throughput** —
  one k6 `constant-arrival-rate` scenario per public-API use-case group (`= OpenApiSurfaceIT.PUBLIC_API_CONTRACT`: status, login, actions list, action
  CRUD, log write, calendar feed, stats, notes feed/write, **notes search (hit and miss), data export, stats frequency, admin user list**), each with
  its own p95-latency + error-rate **threshold** (a breach makes k6 exit non-zero → the runner and
  the `perf` step fail); (3) **heavy-data edge cases** — the seed populates a large account (`PERF_SEED_ACTIONS` × `PERF_SEED_LOG_DAYS`, plus
  `PERF_SEED_NOTE_DAYS` notes) so the list / stats / calendar-feed / search / export scenarios exercise real fan-out, not empty-DB best cases.

> **The notes search is split into two scenarios by OUTCOME, not by endpoint, and that is deliberate.** A search that matches costs open+match over the
> whole journal; one that matches nothing then runs `NoteSearch.suggest` over every word in it, which is 70-80% of a miss's cost and the one path in the
> app that grows with history and that **no index can reach** (the content is ciphertext, and a per-word blind index was rejected as a
> frequency-analysis exposure — see [`NOTES.md`](NOTES.md)). A single averaged scenario would hide a regression in either half, so `notesSearchHit` and
> `notesSearchMiss` each carry their own budget, and each `check`s that it really took the branch it is named for — a HIT term that silently stopped
> matching would otherwise turn both into the same measurement. **Both terms are keyed to `seed.mjs`'s fixed note prose; change that prose and these
> must change with it.** Journal length is the variable they exist to measure, so `PERF_SEED_NOTE_DAYS` is the knob to raise when re-deriving their
> budgets against a many-year journal.
- **Runs the prod profile** against a live Postgres — **NO frozen clock, NO pre-seeded DB** (like smoke). `seed.mjs` self-seeds (bootstraps the
  first-user admin via the web `/register` form, then seeds via the API) and hands credentials/IDs to `load.mjs` via a base64 `PERFSTATE:…` stdout
  token the runner decodes (k6's `handleSummary` runs in a separate isolate and can't see iteration state, so a file-write handover isn't possible).
  The per-IP auth throttle is disabled in the perf stack (`AUTH_IP_THROTTLE_ENABLED=false`) so the single-source load generator isn't locked out.
- **Isolation:** dedicated compose project (`-p diurnal-perf`), ephemeral tmpfs DB, host port **8083** (!= 8080 prod / 8081 dev+E2E / 8082 smoke), so
  a perf run coexists with everything. An EXIT/INT/TERM/HUP trap always tears the stack down and removes the scratch state dir.
- **Tuning knobs** (env, all with sensible defaults, forwarded by name into the k6 container by `run-perf.sh`):
  `PERF_SEED_ACTIONS`, `PERF_SEED_LOG_DAYS`, `PERF_SEED_NOTE_DAYS`, `PERF_DURATION`, `PERF_RATE`, `PERF_VUS`, `PERF_P95_TOLERANCE`, `PERF_BOOT_BUDGET_S`,
  `PERF_RSS_MAX_MB`, `PERF_DROPPED_MAX`; the per-scenario latency/error budgets live in `load.mjs`'s `options.thresholds` — tune them to the deployment's
  SLOs. `PERF_P95_TOLERANCE` (default `1`) scales **every** p95 latency budget by a single multiplier so the same suite gates both a
  fast dev box and a small shared CI runner without re-numbering each threshold — it scales latency ONLY (error-rate budgets stay
  absolute). `publish.yml` sets a lighter `PERF_RATE`/`PERF_VUS` + a `PERF_P95_TOLERANCE` on the gate step because `ubuntu-latest` is a
  2-vCPU box co-running the app, Postgres and k6, so the dev-workstation load-shape collapses into queueing (seconds of pure queue
  time). The k6 image is pinned (`grafana/k6`), matching the containerised-tool pattern of the lint steps (no host k6 install needed).

## Reading state in a test: use the API, never the markup

**A test (or a script) that needs an id, a name or a count reads it from `/api/v1/*` as JSON — it never
regexes it out of rendered HTML.** Scraping rots silently and in the worst possible way: when the markup
changes the pattern simply matches nothing, so the helper "finds" no rows, does no work, and the test goes
on to assert against state it never actually set up. Nothing fails at the point of the break.

This has bitten twice:

- `scripts/generate-screenshots.cjs` parsed the actions table for `<span data-dt-view>`. The span later
  gained a class, the regex stopped matching, every action looked missing, and re-running against a
  populated database died with `Could not create or locate action` — a message about creation, for a
  listing fault.
- `tests/ui/stats.spec.ts` scraped the created action's id out of the returned row fragment and guarded it
  with `if (match)`, so a failed match silently skipped the logging the test then depended on.

Both now read JSON. The public API gives ids (`POST`/`GET /api/v1/actions`), a day's logged entries
(`GET /api/v1/logs/{date}`, unpaginated) and notes (`GET /api/v1/notes/{date}`) — everything a fixture
needs. Remember the list endpoints are **paginated by the user's page-size preference**, so page through
them rather than assuming one page holds everything.

## Preflight: port 8081

The `java` step refuses to start when `${E2E_HTTP_PORT}` (8081) is already listening, because EVERY `*IT`
boots Quarkus on it: a leftover `quarkus:dev` otherwise turns the run into dozens of identical
"Port already bound" stack traces under a generic "There are test failures", several minutes in. The check
costs milliseconds and names the fix (`scripts/dev-teardown.sh`). **Run the teardown before the gate.**

## The linters see NEW files, not just committed ones

Both `run_javascript` and `run_shellcheck` list files with
`git ls-files --cached --others --exclude-standard`. The `--others` half is load-bearing: a bare
`git ls-files` lists only **tracked** files, so a brand-new script was skipped entirely until the moment it
was committed — which is exactly when linting it matters most. A newly-extracted `note.js` shipped a real
**syntax error** through a passing `javascript` gate because of this. `--exclude-standard` still keeps out
the gitignored vendored assets and build output.

> **Never begin a comment line in a shell script with the word "shellcheck".** Such a line is parsed as a
> directive rather than prose, and the whole enclosing function then fails to parse (`SC1072`/`SC1073`) —
> a rewrapped comment is enough to trigger it.
