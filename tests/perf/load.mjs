// k6 load suite — the steady-state performance gate over the public API contract (the "main use
// cases", = OpenApiSurfaceIT.PUBLIC_API_CONTRACT). Run by tests/run-perf.sh AFTER seed.js has
// populated the heavy account and written the handover state file (PERF_STATE_FILE).
//
// Structure: one k6 `scenario` per API use-case group, each with its own exec function, arrival rate,
// and — via per-scenario thresholds — its own latency + error budget. A breached threshold makes k6
// exit non-zero, which run-perf.sh propagates as the step's pass/fail (the same "tool exit code is the
// gate" pattern the E2E/smoke Playwright runners use). Budgets differ per group on purpose: /status is
// a cheap DB-touch baseline, login carries deliberate Argon2id cost, and the calendar feed / stats are
// the heavy read paths whose regressions this tier exists to catch.
//
// Everything authenticates with the seeded Bearer token; the write scenarios mutate only their own
// per-iteration data (unique action names, decrement-after-increment) so a long run does not grow the
// DB unboundedly or collide across VUs.
// NOTE: `k6` and `k6/http` (and `k6/encoding` in seed.mjs) are built-in modules provided by the k6
// runtime itself — they are NOT npm packages and have no entry in any package.json. k6 (the grafana/k6
// binary this runs inside) resolves them internally; there is nothing to `npm install`. The image tag
// is pinned in tests/run-perf.sh (K6_IMAGE) and bumped by .github/scripts/update_dependency_versions.sh.
import http from "k6/http"
import { check } from "k6"

// The seed handover (credentials, action IDs, date range). Read at init via open() — k6 has no
// cross-invocation shared state, so seed.js persisted this to a file that run-perf.sh passes back in.
const STATE = JSON.parse(open(__ENV.PERF_STATE_FILE || "/tmp/perf-state.json"))
const BASE_URL = STATE.baseUrl
const AUTH = { "Content-Type": "application/json", Authorization: `Bearer ${STATE.token}` }
const JSON_HEADERS = { "Content-Type": "application/json" }

// Shared load shape knobs (overridable via -e) so the same suite runs as a quick smoke-load locally or
// a heavier sweep in CI without editing scenarios.
const RATE = Number(__ENV.PERF_RATE || 20) // iterations/sec for the read-heavy scenarios
const DURATION = __ENV.PERF_DURATION || "30s"
const VUS = Number(__ENV.PERF_VUS || 20)

// Scale every p95 latency budget by PERF_P95_TOLERANCE so ONE suite gates both a fast dev box
// (default 1.0, the tight budgets below) and a small, shared CI runner (a higher multiplier) without
// re-numbering each threshold. On a 2-vCPU GitHub runner the app, Postgres and the k6 generator all
// share the same cores, so even at a reduced arrival rate the service time of each request is several
// times a workstation's — this absorbs that fixed environment penalty. It scales latency ONLY: the
// error-rate budgets stay absolute, so a broken path fails everywhere regardless of the box.
const P95_TOLERANCE = Number(__ENV.PERF_P95_TOLERANCE || 1)

// Search terms for the two notes-search scenarios, both keyed to seed.mjs's fixed note body.
// SEARCH_HIT is a word every seeded note contains, so the match path runs over the whole journal;
// SEARCH_MISS is deliberately absent from it, which is what makes the server fall through to
// NoteSearch.suggest. Change seed.mjs's prose and these must change with it — a HIT term that stopped
// matching would quietly turn both scenarios into the same measurement.
const SEARCH_HIT = "refactor"
const SEARCH_MISS = "quixotic"
function p95(ms) {
    return [`p(95)<${Math.round(ms * P95_TOLERANCE)}`]
}

// How many iterations k6 may fail to START before the run is a failure. This closes a blind spot rather
// than tuning one: with a constant-arrival-rate executor, an app that slows down does NOT make the
// latency percentiles climb — k6 simply stops being able to launch iterations on schedule and DROPS
// them. A dropped iteration is in neither http_req_duration nor http_req_failed, so a genuine
// throughput regression could sail through every threshold below while the offered load quietly
// collapsed. Default 0 (nothing may be dropped); raise it on a box where the generator itself is the
// bottleneck, the same way PERF_P95_TOLERANCE absorbs a slow runner's service time.
const DROPPED_MAX = Number(__ENV.PERF_DROPPED_MAX || 0)

// One scenario per use-case group. `startTime` staggers nothing — they run concurrently on purpose to
// model a realistic mixed workload against the single-instance deploy.
export const options = {
    scenarios: {
        status: sc("status", RATE * 2), // cheap baseline; drive it hardest
        login: sc("login", 3), // Argon2id is deliberately expensive — low arrival rate
        actionsList: sc("actionsList", RATE),
        actionCrud: sc("actionCrud", 5),
        logsWrite: sc("logsWrite", 8),
        calendarFeed: sc("calendarFeed", RATE),
        stats: sc("stats", RATE),
        notesFeed: sc("notesFeed", RATE),
        notesWrite: sc("notesWrite", 8),
        // The search pair is deliberately split by OUTCOME, not by endpoint: a search that matches costs
        // open+match, while one that matches nothing then runs NoteSearch.suggest over every word in the
        // journal — measured at 70-80% of a miss's total cost, and the single path in the app that grows
        // with history and that no index can reach (the content is ciphertext). One scenario averaging the
        // two would hide a regression in either.
        notesSearchHit: sc("notesSearchHit", 5),
        notesSearchMiss: sc("notesSearchMiss", 3),
        // The heaviest single request the API can be asked for: it opens and decrypts the ENTIRE journal
        // plus every action and log into a ZIP, and is bounded only by the size of the account. Driven at
        // 1/s because one call already does more work than a second of any other scenario.
        dataExport: sc("dataExport", 1),
        statsFrequency: sc("statsFrequency", 10),
        adminUsers: sc("adminUsers", RATE),
    },
    thresholds: {
        // The offered load must actually be offered (see DROPPED_MAX), and every check must hold. The
        // checks threshold is what gives the check() calls in the scenarios below any teeth at all:
        // without it a failing check is recorded and then ignored, so only the status-code budgets
        // below could ever fail the run.
        dropped_iterations: [`count<=${DROPPED_MAX}`],
        checks: ["rate==1.00"],
        // Per-scenario p95 latency + error-rate budgets. Tune to the deployment's SLOs; these are
        // deliberately generous starting points for a single-instance box under mixed load.
        "http_req_failed{scenario:status}": ["rate<0.01"],
        "http_req_duration{scenario:status}": p95(150),
        // Login tolerates a higher error rate (10%) than the other scenarios: Argon2id verification is
        // memory-hard, so under full concurrent load some login requests can time out / be shed on a
        // constrained box without indicating a real regression. 10% still catches a broadly-broken
        // auth path while absorbing that contention-driven noise.
        "http_req_failed{scenario:login}": ["rate<0.10"],
        // Argon2id is deliberately memory-hard, so a login costs far more than anything else here. This
        // budget is DERIVED, not picked: it is ~10x the cost of a single verify, which is the contention
        // allowance the previous 2500 was built on (its comment assumed ~250ms per verify). The verify
        // is now ~54ms measured at the configured cost - password.hash.argon2.* defaults to OWASP's
        // 19 MiB / t=2 / p=1, against a former 96 MiB / t=3 / p=4 - so the same 10x re-bases to ~540.
        // Observed p95 on the reference box is ~66ms, leaving ~9x headroom; a slower runner scales the
        // whole file through PERF_P95_TOLERANCE rather than by loosening this. RE-DERIVE this the same
        // way if the Argon2id cost changes again - do not nudge it until it passes.
        "http_req_duration{scenario:login}": p95(600),
        "http_req_failed{scenario:actionsList}": ["rate<0.01"],
        "http_req_duration{scenario:actionsList}": p95(400),
        "http_req_failed{scenario:actionCrud}": ["rate<0.01"],
        "http_req_duration{scenario:actionCrud}": p95(600),
        "http_req_failed{scenario:logsWrite}": ["rate<0.01"],
        "http_req_duration{scenario:logsWrite}": p95(500),
        "http_req_failed{scenario:calendarFeed}": ["rate<0.02"],
        "http_req_duration{scenario:calendarFeed}": p95(800), // heaviest read (per-log fan-out)
        "http_req_failed{scenario:stats}": ["rate<0.02"],
        "http_req_duration{scenario:stats}": p95(800), // full recompute per call
        // Notes are encrypted at rest, so these two are the only scenarios doing cryptographic work on the
        // request path. The read opens the account's data key once for the range and then decrypts per day;
        // the budget is a little above calendarFeed because the payload is prose rather than counts, and
        // generous enough to absorb contention while still catching a per-note key resolution creeping back.
        "http_req_failed{scenario:notesFeed}": ["rate<0.02"],
        "http_req_duration{scenario:notesFeed}": p95(900),
        "http_req_failed{scenario:notesWrite}": ["rate<0.01"],
        "http_req_duration{scenario:notesWrite}": p95(500),
        // A search that HITS is the notesFeed work over the whole journal rather than a date range, so it
        // is budgeted a little above it. A search that MISSES additionally runs the "did you mean"
        // suggestion, whose cost is the journal's whole vocabulary — hence the far wider budget. Both are
        // ceilings on the SHAPE of the work, not on the seeded size: point the tier at a many-year journal
        // (PERF_SEED_NOTE_DAYS) and these are the numbers to re-derive first.
        "http_req_failed{scenario:notesSearchHit}": ["rate<0.02"],
        "http_req_duration{scenario:notesSearchHit}": p95(1000),
        "http_req_failed{scenario:notesSearchMiss}": ["rate<0.02"],
        "http_req_duration{scenario:notesSearchMiss}": p95(2000),
        // Whole-account export: every note decrypted, every log and action serialised, then zipped. The
        // budget is the loosest here on purpose — it exists to catch an order-of-magnitude regression (a
        // per-note key resolution, an N+1 over logs), not to pin a number that legitimately grows with
        // the account.
        "http_req_failed{scenario:dataExport}": ["rate<0.01"],
        "http_req_duration{scenario:dataExport}": p95(5000),
        // The chart's monthly rollup is range-bound and its navigation bound is a per-subject LATERAL
        // probe; both were deliberate fixes (~65ms -> 1.3ms, 32.3ms -> 0.27ms) that nothing guarded until
        // now. A whole-history rollup creeping back is exactly what this budget is set to catch.
        "http_req_failed{scenario:statsFrequency}": ["rate<0.02"],
        "http_req_duration{scenario:statsFrequency}": p95(800),
        // The admin list is the query V42's users.created_at index was added for (13-15ms first page /
        // 127ms last page at 50k accounts, before it). Index-backed and paginated, so it should stay
        // cheap; this is the guard on that index still being used.
        "http_req_failed{scenario:adminUsers}": ["rate<0.01"],
        "http_req_duration{scenario:adminUsers}": p95(400),
    },
}

// Build a constant-arrival-rate scenario bound to the named exec function.
function sc(exec, rate) {
    return {
        executor: "constant-arrival-rate",
        exec,
        rate,
        timeUnit: "1s",
        duration: DURATION,
        preAllocatedVUs: VUS,
        maxVUs: VUS * 4,
    }
}

// A random seeded action ID, for the read/write scenarios that target an existing action.
function anyActionId() {
    return STATE.actionIds[Math.floor(Math.random() * STATE.actionIds.length)]
}

// A date beyond the seeded window, so note writes never disturb the range the read scenario measures.
function futureDay() {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() + 1 + Math.floor(Math.random() * 90))
    return d.toISOString().slice(0, 10)
}

// ── Scenario exec functions (one per use-case group) ────────────────────────────

// GET /api/v1/status — anonymous, DB-readiness-gated. The cheap baseline.
export function status() {
    const res = http.get(`${BASE_URL}/api/v1/status`, { tags: { name: "status" } })
    check(res, { "status 200": r => r.status === 200 })
}

// POST /api/v1/auth/login — credential verification (Argon2id). Reuses the one seeded credential.
export function login() {
    const res = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email: STATE.email, password: STATE.password }),
        { headers: JSON_HEADERS, tags: { name: "login" } },
    )
    check(res, { "login 200": r => r.status === 200 })
}

// GET /api/v1/actions — paginated list over the heavy account. Request only pages that actually exist:
// the API REJECTS an out-of-range page with 400 (reject-not-coerce), which would (correctly) count
// against http_req_failed, so the scenario must stay within [1, totalPages]. Page size is the account's
// preference (default 5, unchanged by the seed), so totalPages = ceil(seedActions / 5).
const MAX_PAGE = Math.max(1, Math.ceil(STATE.seedActions / 5))
export function actionsList() {
    const page = 1 + Math.floor(Math.random() * MAX_PAGE)
    const res = http.get(`${BASE_URL}/api/v1/actions?page=${page}`, { headers: AUTH, tags: { name: "actionsList" } })
    check(res, { "actions list 200": r => r.status === 200 })
}

// POST -> PATCH -> DELETE /api/v1/actions/{id} — the full CRUD lifecycle, unique per iteration so it
// neither collides across VUs nor grows the DB (the delete reclaims what the create request added).
export function actionCrud() {
    const name = `Perf CRUD ${__VU}-${__ITER}-${Date.now()}`
    const created = http.post(
        `${BASE_URL}/api/v1/actions`,
        JSON.stringify({ name, colour: "#64748b" }),
        { headers: AUTH, tags: { name: "actionCreate" } },
    )
    if (!check(created, { "action created 201": r => r.status === 201 })) {
        return
    }
    const id = created.json("id")
    http.patch(
        `${BASE_URL}/api/v1/actions/${id}`,
        JSON.stringify({ name: `${name} edited` }),
        { headers: AUTH, tags: { name: "actionUpdate" } },
    )
    http.del(`${BASE_URL}/api/v1/actions/${id}`, null, { headers: AUTH, tags: { name: "actionDelete" } })
}

// POST increment then decrement on a seeded action/day — a net-zero write pair so repeated runs don't
// drift the seeded counts, while still exercising the atomic write path under contention.
export function logsWrite() {
    const id = anyActionId()
    const day = STATE.today
    const inc = http.post(
        `${BASE_URL}/api/v1/logs/${day}/${id}/increment`,
        JSON.stringify({ amount: 1 }),
        { headers: AUTH, tags: { name: "logIncrement" } },
    )
    // 200 only. Tolerating a 400 here was misleading: http_req_failed already counts a 400 as a failure
    // against this scenario's rate<0.01 budget, so the two disagreed about the same response. Nor is a
    // 400 reachable - the seeded counts are 1-5 so the 999 cap is far away, a decrement at zero removes
    // the entry with a 200, and STATE.today can only go STALE INTO THE PAST as a run proceeds, which the
    // future-date guard permits.
    check(inc, { "increment 200": r => r.status === 200 })
    http.post(
        `${BASE_URL}/api/v1/logs/${day}/${id}/decrement`,
        JSON.stringify({ amount: 1 }),
        { headers: AUTH, tags: { name: "logDecrement" } },
    )
}

// GET /api/v1/logs/events?start=&end= — the calendar feed over the full seeded range. The heaviest
// read (one event per logged action per day), and the endpoint most worth guarding against regression.
export function calendarFeed() {
    const res = http.get(
        `${BASE_URL}/api/v1/logs/events?start=${STATE.rangeStart}&end=${STATE.rangeEnd}`,
        { headers: AUTH, tags: { name: "calendarFeed" } },
    )
    check(res, { "events 200": r => r.status === 200 })
}

// GET /api/v1/stats — full statistics recompute over the heavy account on every call.
export function stats() {
    const res = http.get(`${BASE_URL}/api/v1/stats`, { headers: AUTH, tags: { name: "stats" } })
    check(res, { "stats 200": r => r.status === 200 })
}

// GET /api/v1/notes?start=&end= — the seeded note range. Every note in it is individually sealed, so this
// is the read that measures decryption: one data-key resolution for the range, then one AES pass per day.
// A regression here is most likely to mean the key is being resolved per note again rather than per range.
export function notesFeed() {
    const res = http.get(
        `${BASE_URL}/api/v1/notes?start=${STATE.noteRangeStart}&end=${STATE.rangeEnd}`,
        { headers: AUTH, tags: { name: "notesFeed" } },
    )
    check(res, { "notes 200": r => r.status === 200 })
}

// PUT /api/v1/notes/{date} — the write side of the same path: normalise, seal, upsert. Targets a FUTURE
// date so it never overwrites the seeded range the read scenario is measuring against (a note may be
// written for any date, unlike a log entry).
export function notesWrite() {
    const res = http.put(
        `${BASE_URL}/api/v1/notes/${futureDay()}`,
        JSON.stringify({ content: `Load-written note at ${Date.now()}, long enough to be worth sealing properly.` }),
        { headers: { ...AUTH, "Content-Type": "application/json" }, tags: { name: "notesWrite" } },
    )
    check(res, { "note written 200": r => r.status === 200 })
}

// GET /api/v1/notes?q= — a search that MATCHES. No date range, so this is the whole journal: the content
// is ciphertext and cannot be filtered by the database, so every note is read, opened and substring-matched
// in the JVM. Cost is linear in journal length with no index available, by design (a per-word blind index
// was rejected as a frequency-analysis exposure), which is why it is measured rather than assumed.
export function notesSearchHit() {
    const res = http.get(
        `${BASE_URL}/api/v1/notes?q=${SEARCH_HIT}`,
        { headers: AUTH, tags: { name: "notesSearchHit" } },
    )
    check(res, {
        "search 200": r => r.status === 200,
        // A term that stopped matching would still answer 200 and would silently make this the miss path.
        "search found something": r => (r.json("totalCount") ?? 0) > 0,
    })
}

// GET /api/v1/notes?q= — a search that matches NOTHING, which is the expensive half. Matching stays exact,
// so a fruitless search is answered with a "did you mean" (NoteSearch.suggest) computed over every word the
// journal holds; that suggestion is 70-80% of a miss's cost and is the part that degrades with history.
export function notesSearchMiss() {
    const res = http.get(
        `${BASE_URL}/api/v1/notes?q=${SEARCH_MISS}`,
        { headers: AUTH, tags: { name: "notesSearchMiss" } },
    )
    check(res, {
        "search 200": r => r.status === 200,
        // Pins that this really is the miss path — if it starts matching, the suggest branch stops running.
        "search found nothing": r => (r.json("totalCount") ?? 0) === 0,
    })
}

// GET /api/v1/data/export — the whole account as a ZIP of CSVs. An export necessarily OPENS every note
// (the archive holds content in the clear), so this is the only request that decrypts the entire journal
// in one call, on top of serialising every action and log. Unbounded in account size, and until now the
// only public endpoint carrying that much work with no load coverage at all.
export function dataExport() {
    const res = http.get(`${BASE_URL}/api/v1/data/export`, { headers: AUTH, tags: { name: "dataExport" } })
    check(res, {
        "export 200": r => r.status === 200,
        // A ZIP starts "PK" — cheap proof the archive was actually built, not that a 200 was returned.
        "export returned an archive": r => r.body.length > 2 && r.body[0] === "P" && r.body[1] === "K",
    })
}

// GET /api/v1/stats/{subjectId}/frequency — the frequency chart's monthly rollup for one subject. Reads
// only the window it draws (a whole-history rollup here was a measured regression), and its navigation
// bound is a per-subject LATERAL probe rather than a MIN over an IN-list.
export function statsFrequency() {
    const res = http.get(
        `${BASE_URL}/api/v1/stats/${anyActionId()}/frequency?period=month`,
        { headers: AUTH, tags: { name: "statsFrequency" } },
    )
    check(res, { "frequency 200": r => r.status === 200 })
}

// GET /api/v1/admin/users — the admin console's account list. The seeded account is the deployment's FIRST
// user and therefore its admin, so the same Bearer token reaches it. Counted-query paginated against the
// users.created_at index added in V42, which is what this scenario guards.
export function adminUsers() {
    const res = http.get(
        `${BASE_URL}/api/v1/admin/users?page=1`,
        { headers: AUTH, tags: { name: "adminUsers" } },
    )
    check(res, { "admin users 200": r => r.status === 200 })
}
