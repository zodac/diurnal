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
function p95(ms) {
    return [`p(95)<${Math.round(ms * P95_TOLERANCE)}`]
}

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
    },
    thresholds: {
        // Per-scenario p95 latency + error-rate budgets. Tune to the deployment's SLOs; these are
        // deliberately generous starting points for a single-instance box under mixed load.
        "http_req_failed{scenario:status}": ["rate<0.01"],
        "http_req_duration{scenario:status}": p95(150),
        // Login tolerates a higher error rate (10%) than the other scenarios: Argon2id verification is
        // memory-hard, so under full concurrent load some login requests can time out / be shed on a
        // constrained box without indicating a real regression. 10% still catches a broadly-broken
        // auth path while absorbing that contention-driven noise.
        "http_req_failed{scenario:login}": ["rate<0.10"],
        // Argon2id is deliberately memory-hard; a single verify is ~250ms, but on one box under full
        // concurrent mixed load the p95 climbs well past that. Budget for that contention while still
        // catching a genuine regression (e.g. a cost-factor mis-config pushing it to multiple seconds).
        "http_req_duration{scenario:login}": p95(2500),
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
    check(inc, { "increment 200/400": r => r.status === 200 || r.status === 400 })
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
