# Test Implementation Plan

## Overview

| Layer       | Framework                             | Location                         | DB Required            |
|-------------|---------------------------------------|----------------------------------|------------------------|
| Unit        | JUnit 5 (plain, no Quarkus)           | `src/test/java/dev/lifetracker/` | No                     |
| Integration | Quarkus `@QuarkusTest` + REST Assured | `src/test/java/dev/lifetracker/` | Yes (Testcontainers)   |
| UI/E2E      | Playwright (Node/TypeScript)          | `e2e/`                           | Yes (full running app) |

---

## Phase 1 — Test Infrastructure

### 1a. Test database via docker-compose

Rather than Testcontainers (which requires Docker socket access from within the Maven JVM), the `test-db` service in `docker-compose.dev.yml` runs a PostgreSQL on port 5433 with `tmpfs` storage (wiped on restart, fast I/O). This keeps the test DB fully containerised without polluting the host.

The test profile points to this DB (`application-test.properties`). Flyway runs migrations automatically at startup. `IntegrationTestBase.setUp()` truncates tables before each test.

**Workflow before running `mvn test`:**
```bash
docker compose -f docker-compose.dev.yml up -d test-db
# wait for healthy, then:
mvn test
```

`pom.xml` test dependencies needed:
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-test-security</artifactId>
    <scope>test</scope>
</dependency>
```

### 1b. Playwright E2E scaffold

Create `e2e/` at repo root. This is a standalone Node project so it doesn't affect the Maven build.

```
e2e/
  package.json          (playwright, @playwright/test)
  playwright.config.ts  (baseURL: http://localhost:8080, 3 browsers)
  tests/
    auth.spec.ts
    actions.spec.ts
    dashboard.spec.ts
    stats.spec.ts
    settings.spec.ts
    navbar.spec.ts
  helpers/
    fixtures.ts         (login helper, test user creation via REST API)
```

`playwright.config.ts` should set `use.headless = true` in CI, and include a `webServer` block that starts the app (or skip if already running).

---

## Phase 2 — Unit Tests

Plain JUnit 5, no Quarkus context, no DB. Run with `mvn test`.

### `StatsServiceTest.java` — `StatsService` static methods

The two static methods `currentStreak` and `longestStreak` are the most complex pure-logic code in the project. They are package-private statics — test them directly from the same package (`dev.lifetracker.stats`).

**`currentStreak` cases:**

| Case                       | Input                     | Expected                                          |
|----------------------------|---------------------------|---------------------------------------------------|
| No logs                    | empty                     | 0                                                 |
| Only today                 | [today]                   | 1                                                 |
| Yesterday only             | [yesterday]               | 1 (grace: counts yesterday when today not logged) |
| Today + yesterday          | [yesterday, today]        | 2                                                 |
| 3-day run ending today     | [today-2, today-1, today] | 3                                                 |
| 3-day run ending yesterday | [today-2, today-1]        | 2                                                 |
| Gap breaks streak          | [today-3, today-1, today] | 2                                                 |
| Old history only           | [30 days ago]             | 0 (not today or yesterday)                        |
| Count > 1 on a day         | count=3 for today         | streak = 1 (streak is day-based, not count-based) |

**`longestStreak` cases:**

| Case                                     | Expected             |
|------------------------------------------|----------------------|
| Single date                              | 1                    |
| Two consecutive                          | 2                    |
| Two with gap                             | 1                    |
| Long run then gap then short run         | returns the long run |
| All same date (deduped before passed in) | 1                    |

### `ActionStatsTest.java` — `ActionStats` helper methods

`ActionStats` is a record with a `today` field injected — construct it directly in tests.

**`sinceLabel`:** today → "Today", yesterday → "Yesterday", 2 days ago → "2 days ago", null lastPerformed → "—"

**`weeklyAverage`:** null firstPerformed → "0.0"; 7-day span, 7 occurrences → "1.0"; 14-day span, 7 occurrences → "0.5"

**`trend`/`trendClass`:** both 0 → "—" / grey; previous=0 current=5 → "+5" / green; equal → "=" / grey; current < previous → "-2" / red

**`monthTrend`, `yearTrend`:** same cases using month/year count fields.

**`hasData`:** totalDays=0 → false; totalDays=1 → true

### `UserSettingsTest.java`

**`sanitisePageSize`:** 10 → 10; 25 → 25; 50 → 50; 100 → 100; 0 → 10 (default); 11 → 10; -1 → 10; 200 → 10; Integer.MAX_VALUE → 10

---

## Phase 3 — Integration Tests

All use `@QuarkusTest`. REST Assured tests the HTTP layer end-to-end with a real DB (Testcontainers). Use `@TestSecurity` from `quarkus-test-security` to inject an authenticated identity without going through the login form. Where a `User` row is required, create it in `@BeforeEach` inside a `@Transactional` method.

### `AuthResourceIT.java` — `POST /api/auth/register` and `POST /api/auth/login`

**Register:**
- Valid registration → 201, token in response, `email` normalised to lowercase
- Duplicate email → 409
- Missing/blank display name → 400 (Bean Validation)
- Missing/blank email → 400
- Password < 8 chars → 400
- Case-insensitive duplicate: register `User@Example.com`, then try `user@example.com` → 409

**Login:**
- Valid credentials → 200, token in body
- Wrong password → 401
- Unknown email → 401
- Email case-insensitive: register with `User@X.com`, login with `user@x.com` → 200
- Returned token is valid JWT (parse and verify `sub`, `iss`, expiry)

### `ActionsResourceIT.java` — `ActionsWebResource`

Use `@TestSecurity(user = "test@example.com", roles = "user")` plus `@BeforeEach` creating the `User` row.

**Create action (`POST /actions`):**
- Valid name + colour → 200, returns HTML fragment containing the name and colour
- Blank name → 409, `HX-Retarget` header present in response
- Duplicate name (same user) → 409 error
- Duplicate name different user → 200 (isolation)
- Invalid hex colour → sanitised to `#6366f1`
- Valid hex colour preserved
- Name is trimmed: `"  Running  "` stored as `"Running"`

**List/pagination (`GET /actions/list`):**
- 0 actions → empty list, no pagination controls
- Exactly 1 page (pageSize=10, 10 actions) → no pagination controls
- 11 actions, page 1 → 10 items, "Next →" present
- 11 actions, page 2 → 1 item, "← Previous" present
- Page number beyond total → clamps to last page
- Search `q=run` filters case-insensitively

**Update action (`POST /actions/{id}`):**
- Valid update changes name and colour
- Blank name → 409 error
- Rename to existing action's name → 409 error
- Rename to own current name → 200 (same ID excluded from duplicate check)
- Update another user's action → 404

**Delete action (`POST /actions/{id}/delete`):**
- Own action → 204, `archived=true`, associated logs deleted
- Another user's action → 404

**Partial fragments:**
- `GET /actions/{id}/edit` → 200, returns edit form HTML
- `GET /actions/{id}/confirm-delete` → 200, returns confirm HTML
- Unknown ID → 404

### `LogResourceIT.java` — `LogWebResource`

**Increment (`POST /logs/{date}/{actionId}/increment`):**
- First increment → creates `ActionLog` with count=1, returns updated HTML
- Second increment same day → count becomes 2
- Increment at 254 → count becomes 255
- Increment at 255 → count stays 255 (MAX_DAILY_COUNT cap, still returns 200)
- Future date → 400
- Another user's action → 404

**Decrement (`POST /logs/{date}/{actionId}/decrement`):**
- From count=2 → count=1
- From count=1 → log row deleted, response shows 0
- No log entry (count=0) → 200, returns item showing 0 (idempotent)
- Future date → 400
- Another user's action → 404

**Day panel (`GET /logs/day/{date}`):**
- Past date with logs → actions sorted by count desc, then alphabetical
- Past date with no logs → all actions at count=0
- Future date → "future" message, no action list
- Pagination: >pageSize actions renders pagination controls

**Day list pagination (`GET /logs/day/{date}/list`):**
- Page clamping (page 99 with only 1 page of data → returns page 1)
- Search filter works within day list
- Filler rows only present when there is more than one page

### `CalendarResourceIT.java` — `GET /logs/events`

- Returns events within date range; count=3 → title `"Running ×3"`; count=1 → title `"Running"` (no multiplier)
- Archived actions' logs still appear (historical display)
- Only current user's events returned
- Date strings with time component (ISO datetime from FullCalendar) accepted, only date part used
- Empty range returns `[]`

### `StatsResourceIT.java`

- User with no logged actions → `hasActions=false` state in rendered HTML
- Known log data → verify streak, totals, best-month values in HTML
- Pagination of stats cards

### `SettingsIT.java`

**`POST /settings`:**
- Dark mode checkbox checked → `darkMode=true` persisted
- Checkbox unchecked → `darkMode=false` persisted (form sends `["false"]` only)
- Valid page size (25) → persisted
- Invalid page size (7) → falls back to default 10
- Tampered page size (999) → falls back to 10

**`POST /toggle-theme`:**
- Toggles false → true, returns JSON `{"darkMode":true}`
- Toggles true → false, returns JSON `{"darkMode":false}`

### `WebResourceIT.java`

- `GET /register` when `PASSWORD_AUTH_ENABLED=false` → 404
- `POST /register` when disabled → 404
- `GET /` unauthenticated → 302 redirect to `/login`
- `GET /stats` unauthenticated → 302 redirect to `/login`
- `POST /register` valid → redirect to `/login?registered=true`
- `POST /register` duplicate email → redirect to `/register?error=email_taken`
- `POST /register` short password → redirect to `/register?error=invalid`
- `POST /logout` → clears `lt_session` cookie, redirects to `/login`

---

## Phase 4 — UI / E2E Tests (Playwright)

Playwright runs against the full app. Use the REST API (`POST /api/auth/register` + form login) to set up state rather than re-testing registration in every spec. Each spec file should register a unique test user to avoid state bleed.

**Login fixture (`helpers/fixtures.ts`):** call `POST /api/auth/register` once per spec in `beforeAll`, then POST to `/login` via `request.post()`, extract the `lt_session` cookie, and apply it to `page.context()`.

### `auth.spec.ts`

- Login with valid credentials → dashboard, navbar shows display name
- Login with invalid password → stays on login page, error message visible
- Logout → redirected to login, session cleared (navigating to `/` redirects back to `/login`)
- Register form: valid input → redirected to login with "registered" banner
- Register form: duplicate email → error on register page
- Register form: password too short → error on register page

### `actions.spec.ts`

- Create action: fill name + pick colour → action appears in list
- Create action with duplicate name → error message in error slot, list unchanged
- Edit action: click Edit → inline edit form appears; change name → list updates in-place
- Delete action: click Delete → confirm panel appears; confirm → action removed
- Pagination: with >pageSize actions, "Next →" navigates to page 2; "← Previous" returns
- Search: typing filters list live; clearing restores full list
- Dark mode: action colour swatches visible in dark theme

### `dashboard.spec.ts`

- Page load: today pre-selected, day panel loads automatically
- Click past date → day panel loads that day's actions
- Click a logged event dot → loads that day's panel (not just empty cell)
- Click greyed-out adjacent-month date → calendar navigates to that month, panel loads
- Increment `+`: count goes 0 → 1; again → 2
- Increment at 255: `+` button disabled, title "Maximum reached"
- Decrement from 1: count goes to 0; `−` button becomes disabled
- Decrement at 0: `−` button is disabled and non-interactive
- Calendar refreshes: after logging, calendar dots update (FullCalendar refetch fires)
- Future date: panel shows "future" message, no +/− buttons
- Jump picker: calendar icon opens month grid; click month navigates; click outside closes; Escape closes; year arrows change year
- Stats summary: shows 3 most recent; empty state when no logs

### `stats.spec.ts`

- No logged actions → empty state message
- With logged actions → stats cards render streak/totals
- Pagination: multiple pages navigate correctly

### `settings.spec.ts`

- Toggle dark mode on → page switches to dark theme; reload persists
- Toggle dark mode off → page switches to light theme; reload persists
- Change page size to 25 → saved banner; navigate to actions, 25 items per page
- Page size select only offers valid options (10, 25, 50, 100) — no invalid values possible

### `navbar.spec.ts`

- Desktop viewport: full nav links visible, hamburger hidden
- Mobile viewport (< 640px): hamburger visible, nav links hidden
- Click hamburger → menu opens with animation
- Click hamburger again → menu closes
- Menu contains Dashboard, Actions, Stats links and separator before user/logout items
- Active page highlighted in menu

---

## Implementation Order

1. **Phase 1** — test infrastructure (Testcontainers config + Playwright scaffold)
2. **`StatsServiceTest` + `ActionStatsTest`** — highest value, no setup needed
3. **`UserSettingsTest`** — trivial, 5 minutes
4. **`AuthResourceIT`** — foundational; later tests depend on user creation
5. **`ActionsResourceIT`** — most business rules (colour sanitisation, duplicate check, pagination, isolation)
6. **`LogResourceIT`** — MAX_DAILY_COUNT cap, future-date guard, decrement-to-zero deletion
7. **`CalendarResourceIT`, `StatsResourceIT`, `SettingsIT`, `WebResourceIT`**
8. **Playwright E2E** — `auth.spec.ts` first (establishes login fixture), then `dashboard.spec.ts` (most complex), then the rest

---

## Implementation Notes

- **Quarkus test DB:** `%test.quarkus.datasource.devservices.enabled=true` in `application.properties`. `mvn test` starts Testcontainers automatically.
- **Auth in integration tests:** `@TestSecurity(user = "user@example.com", roles = "user")` on the test class. Still need a matching `User` row — create it in `@BeforeEach` with `User.persist()` inside `@Transactional`.
- **HTMX response assertions:** REST Assured returns raw HTML. Use `assertThat(body).contains("Running")` or Jsoup for structural assertions.
- **Playwright test user isolation:** Each spec file registers a unique test user (e.g. `actions-test@example.com`) in `beforeAll`. Apply the session cookie to `page.context()` so all pages in that spec are authenticated.
- **`StatsService` static methods:** `currentStreak` and `longestStreak` are package-private. A test in the same package (`dev.lifetracker.stats`) can call them directly.

---

## Edge Cases from Git History

| Commit area                                                                 | Edge case                                                                           |
|-----------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `[Dashboard] Disabling action logging for future dates`                     | `POST /logs/{future}/{id}/increment` → 400                                          |
| `[Dashboard] Date select works even if the action element is clicked`       | `eventClick` calls `selectDay` same as `dateClick`                                  |
| `[Dashboard] Switching month when a date outside current month is selected` | `fc-day-other` class triggers `cal.gotoDate`                                        |
| `[Stats] Hiding actions with no entries`                                    | `forAllActiveActions` filters via `hasData()`                                       |
| `[Settings] Dark mode checkbox`                                             | Unchecked form sends `["false"]` only; `updateSettings` checks for `"true"` in list |
| `[Settings] Page size allow-list`                                           | Value outside `{10,25,50,100}` → default 10                                         |
| `[Navbar] Hamburger menu`                                                   | Mobile viewport; open/close; separator between nav and user items                   |
| Streak grace day                                                            | `currentStreak` starts at `today-1` when today has no log entry                     |
| Calendar: archived action logs still render                                 | `CalendarResource` fetches all actions including archived ones                      |
| Calendar event title                                                        | count=1 → `"Running"`; count=3 → `"Running ×3"`                                     |
| `[Actions] Delete removes logs`                                             | Soft-archives action, hard-deletes associated `ActionLog` rows                      |
