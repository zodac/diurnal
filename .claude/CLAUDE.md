# CLAUDE.md

## Hard rules

These bind before any other document is read, and several are enforced by a `PreToolUse` hook in
[`.claude/hooks/`](hooks) — you will be blocked rather than corrected later.

> **NEVER commit or push unless explicitly asked.** Do not offer to, do not ask whether to, do not treat a
> finished task or a green gate as a cue. Leave the work in the working tree and say what changed; the maintainer
> commits when they want to.
>
> When they *do* ask, **`.hooks/commit-msg` requires `[Category] Text` on EVERY non-empty line**, not just the
> subject — a body paragraph without its own `[Category]` prefix fails the commit. Keep to a one-line message
> unless there is a reason not to. Use `[AI]` for anything under `.claude/`, and otherwise the existing vocabulary
> (`[CI]`, `[UI]`, `[Testing]`, `[Notes]`, `[Stats]`, `[DB]`, `[Java]`, `[API]`, `[Settings]`, `[Documentation]`,
> `[Authentication]`, …; run `git log --format='%s'` for the full set and reuse rather than invent).
>
> **`.hooks/pre-commit` then runs the whole auto-detected gate before the commit is created**, so a commit on
> `master` can take ~10 minutes and can fail. Both hooks are installed by `.hooks/install_hooks.sh` (already
> installed in this clone) and are separate from the Claude Code hooks in `.claude/hooks/`.

> **NEVER create a git branch without explicit permission.** All work happens directly on `master` — no feature
> branches, no working branches, regardless of how large the change is. If a branch genuinely seems necessary, ask
> first and proceed only on an explicit yes.

> **NEVER modify an existing migration file — not the SQL, not even a comment or a whitespace.** This is absolute:
> it applies to brand-new/uncommitted migrations, to "minor" tweaks, to fixing a typo, and to reverting a change
> you just made. **ALWAYS express any change — including a reversion — as a NEW `V{n+1}__` file.** Flyway records a
> checksum of every applied migration and validates it at every startup, so the instant a file's bytes change after
> any database has run it (including a local dev one), that database fails to boot with `Migration checksum
> mismatch`, recoverable only by a manual `flyway repair` or by hand-editing `flyway_schema_history`.

> **Never touch `RELEASE_NOTES.md` or `VERSION`.** Hand-authored release artefacts owned by the maintainer — leave
> them alone even when they appear modified in the working tree, unless the request explicitly names them. The
> pom's `<version>` is CI-owned and bumped separately by `.github/scripts/bump_version.sh`.

> **Log output must be plain ASCII — never an em-dash (`—`) or any other non-ASCII character in a string that
> reaches the logs**: `LOGGER.*` messages, exception messages, and startup-failure text alike. The production
> container's console encoding renders non-ASCII as `?` (e.g. `... already exists ? sign in locally ...`). Use a
> plain hyphen `-`. UI/template/OpenAPI strings are unaffected (browser-rendered UTF-8), as are comments and
> Javadoc. `LogOutputIsPlainAsciiTest` fails the build otherwise.

> **A log line names an account by its EMAIL, never by its `UUID`.** An id tells the operator reading the log
> nothing — it cannot be matched against a support request or an authentication log, and tracing one line to the
> next costs a database query per line. Where only an id is in hand, resolve the email beside the logging call (see
> `NoteKeys`); never pass `something.userId`/`user.id` into the format arguments. `LogsIdentifyUsersByEmailTest`
> fails any logging statement in `src/main/java` that mentions a user id.

> **No real URLs or internal IPs in comments or examples.** Use only `https://diurnal.example.com` or
> `http://127.0.0.1:8080`. Never production hostnames, LAN addresses (`192.168.*`, `10.*`, `172.16–31.*`), or any
> other real hostname.

> **Always use `docker compose` (v2 plugin), never `docker-compose` (hyphenated).** Only the filenames keep the
> hyphen.

> **Always run the quality gate through `.github/scripts/lint_and_tests.sh`, never `mvn clean install -Dall`
> directly**, and scope it to the step you touched — `java` after any Java/template/CSS/UI-spec change,
> `shellcheck` after a `*.sh` edit, `markdown` after docs. **After writing or editing any shell script, running
> `.github/scripts/lint_and_tests.sh shellcheck` and fixing everything it reports — including `info`-level notes
> like `SC2312` — is mandatory.** Everything else about the gate is in the `gate` skill.

> **NEVER end a turn with a question written as prose — ask it with the `AskUserQuestion` tool.** The usual shape
> is the end-of-task "investigate further, leave it as is, or apply the fix I described?"; trailing text there does
> not prompt for an answer, so the decision is silently dropped. Put the recommended course first, labelled
> "(Recommended)", and give the real alternatives as their own options. Do everything that does not depend on the
> answer first — the question is the last thing in the turn, never a substitute for finishing the work.

## Where to look

**Skills (`.claude/skills/`, loaded on demand — invoke the matching one BEFORE starting that kind of work).** Each
carries the procedure and the traps; the deep-reference docs carry the detail.

| Skill      | Invoke it before                                                                        |
|------------|-----------------------------------------------------------------------------------------|
| `gate`     | running the quality gate, or triaging a red one                                         |
| `db`       | any database change — migration, query, index, entity, `persistence`                    |
| `endpoint` | adding or changing an endpoint, a user-facing capability, or a preference               |
| `ui`       | building or verifying UI — templates, partials, CSS, the JS that drives them            |
| `perf`     | proposing or implementing ANY optimisation — an index, a cache, a rewrite, startup work |

**Deep-reference docs — read the matching one before that kind of work.** All are large; each opens with its own
section index, so `grep -n '^#'` the file and read only the range you need.

| Document                             | Covers                                                                                                                                                |
|--------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`CODE_STYLE.md`](CODE_STYLE.md)     | Every Java convention. **Read before writing or editing any code**                                                                                    |
| [`UI_PATTERNS.md`](UI_PATTERNS.md)   | Template/CSS conventions — partial extraction, component classes, tokens, `id` rules                                                                  |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | The class-by-class inventory of every package, pagination, the update check                                                                           |
| [`DATABASE.md`](DATABASE.md)         | Schema, migration conventions, the query rules, the vendor seam, ORM traps                                                                            |
| [`FRONTEND.md`](FRONTEND.md)         | The CSS build, served scripts, data-tables, the calendar engine, Stats tiles                                                                          |
| [`TESTING.md`](TESTING.md)           | The test tiers, IT base/helpers, deterministic time, smoke and perf suites                                                                            |
| [`AUTH.md`](AUTH.md)                 | Sessions, login, throttling, CSP/security headers                                                                                                     |
| [`OIDC.md`](OIDC.md)                 | The whole OIDC sign-in flow and its policy core                                                                                                       |
| [`I18N.md`](I18N.md)                 | Languages, message bundles, locale-aware formatting, RTL, fonts                                                                                       |
| [`TEXT_INPUT.md`](TEXT_INPUT.md)     | The shared free-text validation pipeline                                                                                                              |
| [`NOTES.md`](NOTES.md)               | The per-date notes feature — **read before touching `net.zodac.diurnal.note`, the dashboard grid, the calendar month cache, or the Stats notes rows** |
| [`TRANSFER.md`](TRANSFER.md)         | The per-user export/import — **read before touching `net.zodac.diurnal.transfer`, `NoteService.replaceAll`, or the Settings Data card**               |

## How the agent tooling is wired

Everything under `.claude/` is checked in except `scheduled_tasks.lock` (if it exists).

| Path                            | What it is                                                                                                                                                                                                                      |
|---------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `.claude/settings.json`         | **Project settings — the file that makes the rest work.** Registers every hook below and holds the shared permission allow/deny list. A script in `.claude/hooks/` is NOT auto-discovered; without its entry here it never runs |
| `.claude/settings.local.json`   | Personal overrides, gitignored. Keep machine-specific paths here; anything the whole project wants belongs in `settings.json`, which survives a fresh clone                                                                     |
| `.claude/hooks/*.sh`            | The `PreToolUse` guards. Each exits `2` to block with its reason on stderr                                                                                                                                                      |
| `.claude/hooks/tests/`          | Their behaviour tests — the `shellcheck:hooks` gate tier, and `sandbox/setup.sh` at session start                                                                                                                               |
| `.claude/skills/`               | The five skills, each with a `references/` folder for the parts only some tasks need                                                                                                                                            |
| `.claude/agents/gate-runner.md` | Subagent that runs a gate step and returns only the triaged verdict - use it for the ~10-minute `java` step so its output stays out of the conversation                                                                         |
| `.claude/commands/precommit.md` | `/precommit` - scopes the gate to the diff, checks the docs kept up, proposes a message. It does NOT commit                                                                                                                     |
| `.claude/*.md`                  | This file and the deep-reference docs                                                                                                                                                                                           |

**Adding or changing a guard means updating `.claude/hooks/tests/run-hook-tests.sh` in the same change.** Each
guard draws a deliberately narrow line — block an edit to an EXISTING migration but not the creation of the next
one, block `docker-compose up` but not `-f docker-compose.dev.yml`, block a command that RUNS `mvn -Dall` but not
prose that quotes it — and every one of those distinctions is a silent regression waiting to happen.

Those tests run in **two places, covering different failures**, because a guard fails silently either way:

- **`shellcheck:hooks`** (the gate, and so `.hooks/pre-commit`) invokes the guards directly, which tests their
  LOGIC. It is what stops a broken guard being committed. It cannot see whether they are wired up at all.
- **`sandbox/setup.sh`** runs them at session start, in the environment that will actually run them, which is the
  half the gate cannot reach: an unregistered `settings.json`, a missing `jq`, a lost `+x` bit. A guard that is
  never invoked blocks nothing and says nothing.

**The guards are conservative by design.** A command that carries a protected path as *data* (a heredoc writing
documentation that mentions `VERSION`, a test fixture quoting `rm VERSION`) is blocked even though it writes
nothing. That is the intended direction for a guard; use the `Write`/`Edit` tools for that file instead.

### Directory-scoped `CLAUDE.md`

`frontend/` and `tests/` carry their own `CLAUDE.md`, loaded only when a file in that tree is touched — the cheap
way to put a rule where it is needed without paying for it in every session.

**They must NOT go under `src/main/resources/`.** Anything there is packaged into the jar, and Quarkus registers
every file under `templates/` as a Qute template — so a `CLAUDE.md` there is PARSED, and fails the build on the
first `{word` it meets (`Parser error in template [CLAUDE.md:11]: empty expression found`). This was tried and
reverted; the migration and template rules live in the `db`/`ui` skills and this file's Hard rules instead.

### `.hooks/` is something else entirely — the GIT hooks

Not to be confused with `.claude/hooks/`. Installed by `.hooks/install_hooks.sh`, and both matter before a commit:

- **`commit-msg`** requires `[Category] Text` on **EVERY non-empty line**, not just the subject.
- **`pre-commit`** runs the whole auto-detected gate when on `master`, so creating a commit there takes as long as
  the gate does (~10 minutes) and can fail. It is deliberately pre-commit rather than pre-push — see the comment
  in the script for the SSH-timeout reason, and do not move it.

## Commands

```bash
# One-time after cloning
git submodule update --init          # lint config (required for -Dlint / -Dall / Qodana)
npm --prefix frontend install        # required for any `mvn` build to produce the CSS
cd tests && npx playwright install   # required for the java gate's E2E tier

# Build CSS (compiled Tailwind at /css/app.css; rebuild after ANY class change in a template or in Java,
# or it is purged). Any `mvn` build regenerates it via the POM's `css-build` exec.
npm --prefix frontend run css        # or css:watch alongside quarkus:dev

# Dev mode (hot reload, Swagger UI at /api, port 8081). ALWAYS tear down when done.
docker compose -p diurnal-dev -f docker-compose.dev.yml up -d diurnal-db-dev
mvn quarkus:dev
scripts/dev-up.sh                    # both of the above; scripts/dev-teardown.sh to stop

# Build JAR (no tests by default)
mvn package

# The quality gate. Bare = auto-detect changed steps; `-v` streams output; `step:substep` narrows a step.
# Steps: docker, java, javascript, markdown, perf, shellcheck, typescript. See the `gate` skill.
.github/scripts/lint_and_tests.sh
.github/scripts/lint_and_tests.sh java

# Unit tests only (no DB) — the one gate the wrapper has no scoped step for
mvn test -Dtests
mvn test -Dtests -Dtest=MyTestClass

# Full Docker deployment
docker compose up -d --build
docker compose logs -f app
```

Dev mode expects PostgreSQL on `localhost:5432`, database `diurnal_db`, user `diurnal_user`, password
`diurnal_password`. Flyway migrations run automatically; data is ephemeral (wiped on container recreate).

Config layers: `application.properties` (base/prod), `application-dev.properties` (port 8081, DEBUG),
`application-test.properties` (UTC). Both profile files must stay in `src/main/resources` — the E2E jar runs with
`-Dquarkus.profile=test` and only reads bundled config.

**Port map**: 8080 = production; 8081 = dev mode, `@QuarkusTest`, and the E2E jar (never simultaneous); 8082 =
deployment-smoke; 8083 = perf — each tier an isolated compose project that coexists with a running prod stack.

## Architecture

### Build

Inherits from `net.zodac:parent-pom` (Maven Central), which manages all dependency/plugin versions (Quarkus BOM,
JUnit BOM, jspecify, …). Lint config lives in the `code-quality-config/` git submodule.

Quality profiles (opt-in): `-Dlint` (ErrorProne+NullAway, Checkstyle, PMD, SpotBugs, Javadoc, Enforcer, license
headers, dependency analysis, PITest — compiles test sources but runs no tests); `-Dtests` (surefire `*Test` only);
`-Dall` (unit + `*IT` + full linters). **`-Dall` is only the FIRST tier of the `java` gate** — E2E, smoke and perf
are chained onto the wrapper's steps and are in no `mvn` command; do not re-add them to the pom.

**All linters currently pass clean (Checkstyle/PMD/SpotBugs = 0, PITest strength = 100%); keep them that way.**
Code must be NullAway-annotated (JSpecify `@Nullable`), every public/package method and type carries Javadoc,
locals/params are `final`, unit-test assertions carry messages.

### Package layout

Under `src/main/java/net/zodac/diurnal/`. **This is the map; the class-by-class inventory is in**
**[`ARCHITECTURE.md`](ARCHITECTURE.md) — read it before adding a class, to see what is already there.**

| Package        | What lives there                                                                                                          |
|----------------|---------------------------------------------------------------------------------------------------------------------------|
| `action`       | The `Action` entity, its three resources (page/internal/API), validation and the colour palette                           |
| `log`          | `ActionLog` (composite key, no surrogate id), the day panel, the calendar feeds, log guards                               |
| `stats`        | `StatsService`, the `StatSubject`-keyed figures, the tile catalogue and the frequency graph                               |
| `auth`         | **The credentials core only** - register/login/logout, the hashing services, `Passwords`, roles                           |
| `auth.session` | The session substrate: entity, store, tokens, mechanism, sweeper. **A sink - depends on nothing else in `auth`**          |
| `auth.lockout` | Per-IP auth throttling and the admin lockout console. Also a sink                                                         |
| `auth.oidc`    | The whole OIDC sign-in flow and its pure policy core. See [`OIDC.md`](OIDC.md)                                            |
| `note`         | The `Note` entity, `NoteService` (the single owner of every note write and search), keys, search, pages                   |
| `note.crypto`  | The encryption primitives: AEAD seal/open, HKDF, key envelope, master key. Pure statics, imports nothing from `note`      |
| `user`         | `User`, `/api/v1/users/me`, settings, the pagination preference trio, the picker enums                                    |
| `web`          | The app shell only: dashboard route, `AppInfo`, error pages, request logging, assets config                               |
| `web.admin`    | The admin console - a leaf nothing else in `web` references                                                               |
| `page`         | `Pages` + `PageWindow` - the one place a page number is resolved against a total and sliced                               |
| `http`         | Request-level plumbing owned by no feature: rollback-on-4xx, `NotUiFacing`, client address, ETags                         |
| `colour`       | `Colours` - the rules every user-chosen colour obeys. Shared by `action` and `user`                                       |
| `persistence`  | Typed query binding (`QueryParameter`/`JpqlQuery`/`SqlQuery`) and the vendor seam. See [`DATABASE.md`](DATABASE.md)       |
| `stats.cache`  | The Stats page's cached figures, one row per `(user, subject)`. **A sink**, which is what lets every writer invalidate it |
| `transfer`     | The per-user export/import. See [`TRANSFER.md`](TRANSFER.md)                                                              |
| `update`       | The admin-only "newer version available" check and its outbound lookup seam                                               |

**A cross-cutting helper belongs in `http`, not in the feature package that happened to need it first.** The three
sinks (`auth.session`, `stats.cache`, `note.crypto`) depend on nothing above them, which is what lets the rest of
the app import them freely without creating a cycle.

### The three rules every endpoint obeys

Stated here because they shape any change to a resource; **the full six-step checklist, each step named with the
test that fails when it is skipped, is the `endpoint` skill.**

1. **One implementation per use case.** Every backend use case has exactly ONE implementation — a `*Service` bean
   returning a **sealed result type** — and every surface (public API, internal HTMX, web form) is a thin
   translator that `switch`es it into its own medium. Never re-implement a rule in a `*Resource`. Deliberately
   different per-surface *input contracts* stay in the resource **with a comment marking them as surface policy**.
2. **Capability parity is mandatory.** Every user-facing capability in the UI has a matching `/api/v1` endpoint
   (the converse is not required). API list endpoints paginate exactly like their pages.
3. **The namespace decides the contract.** `/api/v1/*` is the public REST API — JSON only (nothing under `/api`
   may return HTML), fully OpenAPI-annotated, breaking changes are MAJOR-version events. `/internal/*` is web-UI
   plumbing — never documented, no stability guarantees, anonymous requests get a `302 /login` rather than a `401`.
   Page routes stay top-level.

### Transaction handling (who owns the `@Transactional`)

**The default: the resource method owns the transaction and the `*Service` assumes one is active.** Read-only
endpoints — page renders, HTMX fragments, list/GET APIs — carry **none**; Panache reads work without one and
holding a connection for a whole render is wasteful.

**The one inversion:** `AuthenticationService`, `RegistrationService` and `PasswordChangeService` do ~100 ms of
Argon2id work *outside* any transaction and commit in a short `self`-invoked `@Transactional` method, so **their
resource callers must NOT be `@Transactional`** — a nested `REQUIRED` transaction would pull the hashing back in.

**Put `@RollbackOnErrorStatus` on any resource class with `@Transactional` write endpoints.** Because a service
reports failure by *returning* a sealed result rather than throwing, the surrounding transaction would otherwise
commit whatever it mutated before the rejection. Details and the concurrency case: the `endpoint` skill.

### Data records vs. logic (`*Extensions`)

**Records hold data; derived logic lives in a `<Type>Extensions` class.** This is mandatory rather than stylistic —
PITest cannot mutate a record, so logic left on one is silently untested behind the 100% gate. The rule in full,
with the two query rules that follow from it, is in [`CODE_STYLE.md`](CODE_STYLE.md).

### Notable invariants

Each of these is stated in full in the document named beside it. They are listed here so a change knows which
document it needs, not so the rule can be applied from this page.

- **Notes.** A note may be written for ANY date including a future one; content is ENCRYPTED AT REST under a
  per-account data key (**never describe it to a user as end-to-end or zero-knowledge**); search works by OPENING
  notes, never by a database predicate, so the search TERM is as private as the note it finds; the length bound is
  the one per-deployment `TextFields` entry, read through `note/NoteField`. → [`NOTES.md`](NOTES.md)
- **Statistics are computed per `StatSubject`, not per action** — a subject is an action *or* the user's day notes,
  the latter carrying the fixed nil-UUID `StatSubject.NOTES_ID` so every id-keyed path stays `UUID`-typed. One
  `assemble(...)` computes every figure, so a new kind of subject needs no new statistics code. →
  [`NOTES.md`](NOTES.md)
- **A data import REPLACES everything and is all-or-nothing**, rejecting rather than coercing any bad row; an
  export holds note content in the CLEAR. → [`TRANSFER.md`](TRANSFER.md)
- **Every user-chosen colour obeys one shared rule set in `colour/Colours`**, and is stored and rendered exactly as
  picked in both themes — the ONE derived shade in the app is the calendar note marker, derived because it is a
  legibility floor rather than a preference. → [`FRONTEND.md`](FRONTEND.md)
- **The dashboard calendar's first column is a user preference defaulted from the LANGUAGE** (`user/WeekStart`).
  **Resolve it ONCE per render** — the header words and the grid's cell offset must come from the same resolution,
  or they drift a column apart. → [`FRONTEND.md`](FRONTEND.md), [`I18N.md`](I18N.md)
- **A run of days shown as a duration is carried as a `time/DaySpan`, never a bare day count**, because the
  years/months/days split depends on *which* months the run covered. → [`FRONTEND.md`](FRONTEND.md)
- **UI text must use correct singular/plural**, resolved inside the `@Message` value's own `{#if count == 1}`
  branch — never by composing words in Java, which can never be locale-aware. → [`I18N.md`](I18N.md)
- **Text worded in Java is either UI text or `@NotUiFacing`, never both.** A rejection is worded twice: hardcoded
  English for the `/api/v1` body, and a whole translated sentence in `partials/text-failure-message.html` for the
  page. A `*WebResource`/`*InternalResource` must render the partial. → [`I18N.md`](I18N.md)
- **All date-boundary "now"/"today" goes through `AppClock`** — `clock.today(clock.zoneFor(user.timezone))`. There
  is deliberately NO zero-argument `today()`, since every user-visible date boundary belongs to that user's
  timezone. Entity audit stamps use `Instant.now()` directly. `app.timezone` (default `UTC`) feeds it and must
  match `TZ` in `docker-compose.yml`. → [`TESTING.md`](TESTING.md)
- **All list views paginate in memory and the arithmetic is `page/Pages`, never written out at the list.** A list
  asks `PageSizes.forSection(user, PageSection.X)` for its size, never `user.pageSize`. A page past the end is
  CLAMPED for a UI surface and REJECTED by a public API resource. → [`ARCHITECTURE.md`](ARCHITECTURE.md)
- **`ActionLog.MAX_DAILY_COUNT = 999`** — a `SMALLINT` column; the web surface silently caps, the API rejects. →
  the `endpoint` skill
- **`password.auth.enabled=false`** disables register (404, except during first-run setup) and skips
  `PasswordIdentityProvider`; `AppLifecycle` enforces at least one auth mechanism at startup. Password MANAGEMENT
  stays available regardless, keyed on holding a password rather than on the flag. → [`AUTH.md`](AUTH.md)
- **HTMX partials**: a full `@GET` returns a `TemplateInstance`, an HTMX endpoint returns
  `Response.ok(partial.data(...)).build()`, errors use `HX-Retarget`/`HX-Reswap`. Watch the Qute `{`-parsing gotcha
  — a bare `{word` is read as an expression even inside a JS/HTML comment. → [`FRONTEND.md`](FRONTEND.md), `ui` skill
