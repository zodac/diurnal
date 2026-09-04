---
name: endpoint
description: Adding or changing an endpoint or user-facing capability - namespace, shared service with a sealed result, API parity, OpenAPI contract, transactions, and the full chain for a new user preference. Use for: add an endpoint, new API or route, /api/v1, HTMX fragment, add a setting, OpenAPI, Swagger.
---

# Adding or changing an endpoint

Each step below is enforced by a named failing test, so skipping one is caught — but late, after a full gate run.

## Step 1 — Put the logic in a shared service

**Every backend use case has exactly ONE implementation, and every surface — public API, internal HTMX, web form —
is a thin translator over it.** The pattern is a `*Service` bean returning a **sealed result type**, with each
resource doing an exhaustive `switch` into its own medium (JSON + status codes, or partials/banners/redirects).
`AuthenticationService`→`LoginResult`, `ActionService`→`ActionResult`, `ProfileService`→`ProfileResult`,
`AdminUserService`→`AdminUserResult`.

**Never re-implement a rule in a resource.** If a mutation rule is being written inside a `*Resource`, it belongs in
the service. Sealed-result `switch` exhaustiveness catches unhandled cases at compile time; `SurfaceParityIT`
catches behavioural drift between surfaces.

Reads may compose shared queries into surface-specific presentations (pagination, DTOs) — presentation is not
business logic.

## Step 2 — Give it an `/api/v1` twin

**Capability parity is mandatory: every user-facing capability in the UI must have a matching `/api/v1` endpoint.**
The converse is not required — an API capability need not have UI.

API list endpoints paginate exactly like their pages: the user's page-size preference, `?page=` clamped into range,
and `{items,totalCount,totalPages,currentPage}` envelopes.

## Step 3 — Pick the namespace

- **`/api/v1/*`** — the public REST API. JSON in/out, Bearer session token (cookie also accepted), fully
  OpenAPI-annotated, appears in Swagger. **Nothing under `/api` may return HTML.** Breaking changes here are
  MAJOR-version events.
- **`/internal/*`** — web-UI plumbing: HTMX fragments, fragment mutations, UI-cache JSON. Never documented, no
  stability guarantees; an anonymous request gets the browser `302 /login` challenge rather than a `401`.
- **Page routes stay top-level** (`/`, `/actions`, `/notes`, `/stats`, `/settings`, `/admin/*`, `/login`,
  `/register`, `/logout`), as do the OIDC routes.

`EndpointNamespaceTest` fails any `@Path` outside the sanctioned namespaces and page allowlist.

## Step 4 — Reject, do not coerce (public API only)

**The public API never silently changes a caller's input to a default or a clamped value — it rejects with a 4xx.**
A count above `MAX_DAILY_COUNT` is a 400, not a saturation; an out-of-range `?page=` is a 400, not a clamp.

**Web surfaces may keep coercing** — a form clamping a value, or treating a non-positive amount as a no-op, is a
deliberate per-surface *input contract*. Keep those in the resource **with a comment marking them as surface
policy**; the write rule behind them is still the shared service. Same for the API-only first-user registration
refusal and the web-only confirm-password field.

Implement the API boundary checks **in the API resource**, alongside the existing ones — they are `NO_COVERAGE` for
PITest there, which keeps mutation strength at 100%.

## Step 5 — Document it (public endpoints)

- Add the operation to **`OpenApiSurfaceIT.PUBLIC_API_CONTRACT`**. That IT pins the generated document to an exact
  endpoint set, so both an addition and an internal endpoint leaking into the docs fail CI until the contract is
  consciously updated.
- Full annotations: `@Tag`, `@Operation` with summary **and** description, `@APIResponse`, `@SecurityRequirement`,
  `@Schema` on DTOs. `OpenApiSurfaceIT.document_everyOperationIsFullyDocumented` fails a bare operation.
- **Capitalise the `id` acronym as `ID`** in any description or summary — never a standalone lowercase "id". The
  `@Parameter(name = "id")` path-param *name* stays lowercase; only human-readable text is affected.

The annotations are the single source of truth; the spec is served live from `/q/openapi` and is deliberately not
exported to a committed file.

## Step 6 — Decide who owns the transaction

**The default: the resource method carries `@Transactional`, and the service assumes one is active.** Read-only
endpoints — page renders, HTMX fragments, list/GET APIs — carry **none**; Panache reads work without one and holding
a connection for a whole render is wasteful.

**The one inversion:** `AuthenticationService`, `RegistrationService` and `PasswordChangeService` do ~100 ms of
Argon2id work *outside* any transaction and commit in a short `self`-invoked `@Transactional` method. **Their
resource callers must NOT be `@Transactional`** — a nested `REQUIRED` transaction would pull the hashing back
inside it.

**Put `@RollbackOnErrorStatus` on any resource class with `@Transactional` write endpoints.** Because a service
reports failure by *returning* a sealed result rather than throwing, the surrounding transaction would otherwise
commit whatever the service mutated before it hit the rejection. The interceptor marks the transaction
rollback-only whenever a transactional endpoint answers `>= 400`. It is a safe no-op on reads. Prefer it to
handwritten `setRollbackOnly()`.

Where a unique constraint could race a concurrent insert, flush and map the `ConstraintViolationException` to the
duplicate result rather than letting it surface as a 500 (`ActionService.create`, `RegistrationService.createUser`).

## Step 7 — Extend `SurfaceParityIT`

If both surfaces expose the use case, add a same-input/same-DB-outcome case.

## Adding a user preference

A new setting is **not** done at the entity — it is a six-link chain (column, `@Preference` field, `UserDto`
component, `/users/me` read+write, the server-rendered Settings page, the message bundle), and
`UserPreferencesExposureTest` fails until the middle links agree. All six, in order, with the reason each exists:
[`references/preferences.md`](references/preferences.md).

## A note on expected 4xx responses in the UI

**htmx `console.error`s every 4xx and no client handler can suppress it** — it logs inside `triggerEvent` before the
event is dispatched. For a form whose failure is *expected and handled inline* (a wrong current password → 422),
submit via `fetch()` rather than `hx-post`, mirroring the login and register cards in `app.js`. The server keeps
returning the 4xx, so IT and E2E status assertions still hold; `fetch` reads the status and drives the banner.

## Checklist

| Step                                | What fails if you skip it     |
|-------------------------------------|-------------------------------|
| Logic in the shared service         | `SurfaceParityIT`             |
| Sanctioned namespace                | `EndpointNamespaceTest`       |
| Added to `PUBLIC_API_CONTRACT`      | `OpenApiSurfaceIT`            |
| Full OpenAPI annotations            | `OpenApiSurfaceIT`            |
| `ID` capitalised in descriptions    | `OpenApiSurfaceIT`            |
| `@Preference` ↔ `UserDto` agreement | `UserPreferencesExposureTest` |
| API text marked `@NotUiFacing`      | `NotUiFacingTest`             |

**Rejection wording is written twice**: hardcoded English for the `/api/v1` body, marked `http/NotUiFacing`; and a
whole translated sentence in `partials/text-failure-message.html` for the page. A `*WebResource`/`*InternalResource`
must render the partial, never call the Java wording.

Then run `.github/scripts/lint_and_tests.sh java` — see the `gate` skill.
