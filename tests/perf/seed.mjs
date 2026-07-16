// k6 seed script — run ONCE before the load scenarios (tests/run-perf.sh invokes it first). It
// prepares the empty production DB the perf stack boots with, then writes the resulting credentials
// and identifiers to a JSON handover file (PERF_STATE_FILE) that load.js reads back in setup().
//
// The perf stack boots with an empty DB and NO frozen clock (TZ=UTC), exactly like the smoke tier, so
// this self-seeds against the app's own UTC "today":
//   1. Bootstrap the initial administrator via the web /register form — the ONLY sanctioned first-user
//      path (the API refuses to create the very first account). This admin is also the load user.
//   2. Create a "heavy" account's worth of data via the public API: SEED_ACTIONS actions, each logged
//      across SEED_LOG_DAYS consecutive days ending today. This is what turns the list / stats /
//      calendar-feed scenarios into meaningful regression guards (N+1 queries, unindexed scans, per-log
//      fan-out) rather than empty-DB best cases.
//
// Run with: k6 run -e BASE_URL=... [-e SEED_ACTIONS=..] [-e SEED_LOG_DAYS=..]
// It is a single-iteration script (default 1 VU, 1 iteration) — no thresholds, it only seeds.
//
// Handover: k6 can only write a file from handleSummary, which runs in a SEPARATE isolate that cannot
// see state built during the iteration. So instead of writing the state file itself, the seed prints
// it on one stdout line as a base64 token between PERFSTATE: and :ENDPERFSTATE markers; run-perf.sh
// captures that, decodes it to the handover file, and passes it to load.mjs. base64 keeps the payload
// free of quotes/whitespace so k6's own `msg="…"` log wrapping can't corrupt it.
// NOTE: `k6`, `k6/http` and `k6/encoding` are built-in modules provided by the k6 runtime itself —
// they are NOT npm packages and have no entry in any package.json. k6 (the grafana/k6 binary this runs
// inside) resolves them internally; there is nothing to `npm install`. The image tag is pinned in
// tests/run-perf.sh (K6_IMAGE) and bumped by .github/scripts/update_dependency_versions.sh.
import http from "k6/http"
import { check, fail } from "k6"
import encoding from "k6/encoding"

const BASE_URL = __ENV.BASE_URL || "http://127.0.0.1:8083"
const SEED_ACTIONS = Number(__ENV.SEED_ACTIONS || 50)
const SEED_LOG_DAYS = Number(__ENV.SEED_LOG_DAYS || 90)

const ADMIN = {
    email: `perf-admin-${Date.now()}@example.com`,
    displayName: "Perf Admin",
    password: "perf_password123",
}

const JSON_HEADERS = { "Content-Type": "application/json" }

// today (and the seeded log window) in UTC, matching TZ=UTC on the perf server.
function isoDay(offsetDays) {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() - offsetDays)
    return d.toISOString().slice(0, 10)
}

export default function seed() {
    // 1. Bootstrap the initial admin via the cookieless web form (a non-CSRF path the app allows).
    const boot = http.post(`${BASE_URL}/register`, {
        email: ADMIN.email,
        displayName: ADMIN.displayName,
        password: ADMIN.password,
        confirmPassword: ADMIN.password,
    })
    check(boot, { "bootstrap admin registered (2xx/3xx)": r => r.status >= 200 && r.status < 400 })

    // 2. Log in via the API to get a Bearer token for all subsequent seeding + the load run.
    const login = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email: ADMIN.email, password: ADMIN.password }),
        { headers: JSON_HEADERS },
    )
    if (!check(login, { "seed login 200": r => r.status === 200 })) {
        fail(`seed login failed: ${login.status} ${login.body}`)
    }
    const token = login.json("token")
    const authHeaders = { ...JSON_HEADERS, Authorization: `Bearer ${token}` }

    // 3. Create SEED_ACTIONS actions and log each across SEED_LOG_DAYS days ending today. Every write
    //    goes through the public API so the seed itself exercises the create/log endpoints once.
    const actionIds = []
    const today = isoDay(0)
    for (let a = 0; a < SEED_ACTIONS; a++) {
        const created = http.post(
            `${BASE_URL}/api/v1/actions`,
            JSON.stringify({ name: `Perf Action ${a}`, colour: "#6366f1" }),
            { headers: authHeaders },
        )
        if (!check(created, { "seed action created 201": r => r.status === 201 })) {
            fail(`seed action ${a} failed: ${created.status} ${created.body}`)
        }
        const id = created.json("id")
        actionIds.push(id)
        for (let d = 0; d < SEED_LOG_DAYS; d++) {
            http.put(
                `${BASE_URL}/api/v1/logs/${isoDay(d)}/${id}`,
                JSON.stringify({ count: 1 + (d % 5) }),
                { headers: authHeaders },
            )
        }
    }

    // 4. Emit the credentials + identifiers on a single marked, base64-encoded stdout line for
    //    run-perf.sh to capture and hand to load.mjs (see the handover note at the top).
    const state = {
        baseUrl: BASE_URL,
        email: ADMIN.email,
        password: ADMIN.password,
        token,
        actionIds,
        today,
        rangeStart: isoDay(SEED_LOG_DAYS - 1),
        rangeEnd: today,
        seedActions: SEED_ACTIONS,
        seedLogDays: SEED_LOG_DAYS,
    }
    console.log(`[seed] ${SEED_ACTIONS} actions × ${SEED_LOG_DAYS} days seeded for ${ADMIN.email}`)
    console.log(`PERFSTATE:${encoding.b64encode(JSON.stringify(state))}:ENDPERFSTATE`)
}
