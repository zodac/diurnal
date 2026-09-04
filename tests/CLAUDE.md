# Test tiers outside Maven

Everything in this directory is chained onto `.github/scripts/lint_and_tests.sh` steps and is in **no** `mvn`
command. **The Maven build is unit + `*IT` (+ linters) ONLY — do not re-add these to the pom.**

| Directory     | Tier              | Runner                                        | Port |
|---------------|-------------------|-----------------------------------------------|------|
| `ui/`         | Playwright E2E    | `tests/run-e2e.sh` (chained onto `java`)      | 8081 |
| `smoke/`      | Deployment smoke  | `tests/run-smoke.sh` (chained onto `java`)    | 8082 |
| `perf/`       | k6 load           | `tests/run-perf.sh` (the `perf` step)         | 8083 |

## Before running anything here

- **No dev server may be running.** The E2E tier needs port 8081, which `quarkus:dev` and `@QuarkusTest` also use.
  Tear down first: `scripts/dev-teardown.sh`.
- **One-time per clone**: `cd tests && npx playwright install`.
- Iterating one spec against an already-running app: `cd tests && BASE_URL=http://localhost:8081 npm test`.
  Against `quarkus:dev`, start it with `AUTH_IP_THROTTLE_ENABLED=false` or repeated logins lock out `127.0.0.1`.

## Traps that have cost real time

- **A visual claim needs a screenshot.** `.textContent`/`.innerText` reads *logical* DOM order; the Unicode Bidi
  Algorithm decides *visual* order separately. A whole RTL effort was verified by text dumps and missed
  "Page 1 of 2" rendering as "2 of 1 Page".
- **Calendar specs must not use a bare `pastDateStr(n)`** — the grid draws only
  `(weekday-of-the-1st - weekStart + 7) % 7` leading days, so how far back a cell exists depends on today's date.
  Use `showMonthOf(page, iso)` or `otherDaysThisMonth(count)`.
- **`toHaveText` with a RegExp matches raw `textContent`** and skips the whitespace normalisation the plain-string
  form applies.
- **A single spec timing out may be sandbox CPU contention**, not a regression — see the `gate` skill to isolate it
  with `--workers=1`.

Full detail: [`TESTING.md`](../.claude/TESTING.md); the `ui` and `gate` skills hold the procedures.
