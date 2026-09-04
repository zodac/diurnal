---
name: gate
description: Run and triage this project's quality gate (.github/scripts/lint_and_tests.sh) - which step to run, and whether a red run is your change, pre-existing debt on master, or sandbox contention. Use for: run the tests, run the gate, lint, the build failed, Checkstyle/PMD/PITest/Qodana/ErrorProne failures, E2E flakes, port 8081 conflicts.
---

# Running and triaging the quality gate

**Always go through `.github/scripts/lint_and_tests.sh`, never `mvn clean install -Dall` directly**, and **scope it
to the step you touched.** The wrapper runs the same checks CI does, captures output, and prints only pass/fail
unless something fails.

## 1. Before you start

- **No dev server may be running.** The gate's ITs need port 8081 and manage the `diurnal-db-dev` container's
  lifecycle themselves. A manual `mvn quarkus:dev` alongside a gate run makes both fail in ways that look like code
  regressions. Tear it down first: `scripts/dev-teardown.sh`, or `pkill -f "quarkus:dev"` plus
  `docker compose -p diurnal-dev -f docker-compose.dev.yml down`.
- **If anything has touched the dev database** — a scratch table, a psql experiment, an E2E or manual session run
  against dev mode — recreate it first: `docker compose -p diurnal-dev -f docker-compose.dev.yml down -v`. The gate
  starts it again itself. See the boot-failure triage below for why.
- **Prerequisites, once per clone**: `git submodule update --init` (the lint config, and Qodana refuses to start
  without it) and `cd tests && npx playwright install`.

## 2. Pick the narrowest step

```bash
.github/scripts/lint_and_tests.sh              # auto-detect: only the steps whose files changed
.github/scripts/lint_and_tests.sh java         # the whole JVM gate
.github/scripts/lint_and_tests.sh java,shellcheck
.github/scripts/lint_and_tests.sh -v java      # stream full output (default hides it, prints on fail)
.github/scripts/lint_and_tests.sh java:qodana  # ONE tier of a step
```

Steps: `docker`, `java`, `javascript`, `markdown`, `perf`, `shellcheck`, `typescript`.
Substeps: `docker:hadolint` / `docker:grype`; `java:mvn` / `java:e2e` / `java:smoke` / `java:qodana`;
`shellcheck:lint` / `shellcheck:hooks`.

| You changed                      | Run                                                                      |
|----------------------------------|--------------------------------------------------------------------------|
| Java, a template, CSS, a UI spec | `java`                                                                   |
| A `*.sh` file                    | `shellcheck` (**mandatory** after any shell edit)                        |
| A `.claude/hooks/` guard         | `shellcheck` — both tiers; the `hooks` one pins the guards' behaviour    |
| A migration, `VERSION`           | `shellcheck:hooks` — the guards read the real tree, so it can drift      |
| Markdown / docs                  | `markdown`                                                               |
| A Dockerfile (iterating)         | `docker:hadolint` — skip the multi-minute CVE scan                       |
| A dependency version             | `docker:grype`                                                           |
| Only a unit test's logic         | `mvn test -Dtests -Dtest=MyTestClass` — the one gate with no scoped step |

`java` is the whole JVM gate: `mvn clean install -Dall` (unit + `*IT` + linters), then — only if green — the E2E
tier, with the deployment-smoke and Qodana tiers in parallel alongside. **The Maven build is unit + `*IT` +
linters ONLY.** E2E, smoke and perf are chained onto the wrapper's steps and are in no `mvn` command; do not
re-add them to the pom.

## 3. Reading the result

**Trust the exit code.** The wrapper live-tails the `java` lane and kills the `tail` when the lane finishes, so the
closing `✅ Java gate passed` / `❌ failed` line is frequently lost — you see tier progress arrows and then nothing.
That looks exactly like the gate died mid-run and invites a needless ~8-minute re-run. To corroborate green without
re-running, check the artifacts: `.qodana/results/qodana-short.sarif.json` (`executionSuccessful`, `results: []`),
an empty `tests/test-results/`, and a fresh `tests/playwright-report/index.html`.

## 4. Triage: is this failure actually mine?

Work down this list before changing any product code.

### Several ITs fail at BOOT, all at once

Look for `FlywayException: Found non-empty schema(s) "public" but no schema history table`, or
`IllegalStateException: NOTE_ENCRYPTION_KEY does not open the notes data already in this database`. Both mean the
**dev database is poisoned** — it is the same instance the `-Dall` ITs migrate. A hand-created scratch table causes
the first; users registered by an E2E or manual run against dev mode cause the second, because their
`user_notes_keys` rows are wrapped under the *dev* key while the IT profile uses a different one. Either presents as
~8-9 unrelated IT classes erroring simultaneously. Fix: `docker compose -p diurnal-dev -f docker-compose.dev.yml
down -v` and re-run. The container is ephemeral, so this looks intermittent if you do not know the cause.

### `Port already bound: 8081`

Something else owns the port. Either a manual dev server is running (see step 1), or — very commonly — **a
previously stopped gate left orphans behind**. `TaskStop` kills the tracked process but does not reap its tree, so
an orphaned `java -Dquarkus.profile=test -Dquarkus.http.port=8081 …` survives and fails every `@QuarkusTest` before
a single assertion runs. There is no `pgrep` in this sandbox: walk `/proc/[0-9]*/cmdline` for
`quarkus.http.port=8081` and `lint_and_tests`, `kill -9` them, and confirm with
`(exec 3<>/dev/tcp/127.0.0.1/8081)`.

### A single E2E spec times out

If it is one flaky-looking `page.waitForResponse: Test timeout` (not an assertion mismatch), and the files you
changed do not plausibly touch that spec, suspect **Playwright worker contention on this sandbox** rather than a
regression. Isolate it:

```bash
cd tests && npx playwright test <spec> -g "<title>" --project=<project> --repeat-each=3   # try to reproduce
cd tests && npx playwright test <spec> -g "<title>" --project=<project> --repeat-each=5 --workers=1
```

Clean at `--workers=1` and flaky at the default means environmental — re-run the gate rather than changing product
code to chase it.

### The `perf` step fails on latency

The tell that it is queueing, not a regression: **zero failed requests, zero failed checks**, every failure is a
latency threshold (usually `http_req_duration{scenario:login}` or `dropped_iterations`), and even the trivial
`status` scenario creeps toward its budget. `/proc/loadavg` here reports the *host's* load, so waiting for it to
drop never terminates. Re-run at CI's offered load — `PERF_RATE=5 PERF_VUS=10` — and if it passes there, report the
default-load failure as environmental. **Do not loosen the committed thresholds in `tests/perf/load.mjs`** to make a
local run go green. Check first whether the change even touches perf's inputs (`Dockerfile`, `src/`, `pom.xml`,
`tests/perf/**`, `tests/run-perf.sh`, `tests/docker-compose.perf.yml`) — if not, it cannot have caused it.

### The compile aborts inside ErrorProne

`java.util.NoSuchElementException` at `Iterables.getLast` / `StringConcatToTextBlock.matchLiteral` reported as "An
unhandled exception was thrown by the Error Prone static analysis plugin" is an **upstream tool bug** (2.50.0), and
its trigger is positional rather than any one literal shape — a bare `""`, a third element added to a multi-line
`List.of(...)`, an over-long Javadoc line near a text block have all done it, none of which involve concatenation.
Do not hunt for a type or nullability problem. Confirm with `mvn -o clean compile -Dlint` on clean master, then
**restructure whatever you just added so the reported construct changes shape** — extract a helper, rewrap the line,
assert on something else. Suppress only if that is impossible. It can fire at *test*-compile, so `mvn compile
-Dlint` may pass while the gate fails; reproduce with `mvn -o clean test-compile -Dall`.

### A Qodana finding you want to suppress

The SARIF `ruleId` (e.g. `ReturnThis`) is frequently **not** the id `@SuppressWarnings` accepts (`ReturnOfThis`).
Read the right one out of the report rather than guessing — a wrong guess costs a full ~6-minute `java:qodana` cycle
and fails silently:

```bash
python3 -c "import json;d=json.load(open('.qodana/results/qodana.sarif.json'));\
print([(r['id'], r['defaultConfiguration']['parameters'].get('suppressToolId')) for e in d['runs'][0]['tool']['extensions'] for r in e.get('rules',[])])"
```

Some inspections have **no** `suppressToolId` and ignore `@SuppressWarnings` entirely, at class and method scope
alike (confirmed for `EqualsUsesNonFinalVariable`, `HashCodeUsesNonFinalVariable`, `ZeroLengthArrayInitialization`).
For those the escape hatch is a path-scoped per-inspection `ignore:` glob in
`code-quality-config-overrides/qodana.yaml` (the `OptionalContainsCollection` entry is the precedent) — prefer it to
`enabled: false`, which switches the rule off repo-wide. Always read the finding from the SARIF, not the console
summary, which only counts.

### Configuring the Qodana tier itself

Its timings and its config are both easy to get wrong. The full detail - the warm/cold timings and why CPU capping
makes it worse, the `--config` path the CLI cannot find by convention, the two kinds of relative path that resolve
differently, the `imports:`-only profile rule, ordering inside the profile, and the submodule entry points the
dead-code check needs - is in [`references/qodana-config.md`](references/qodana-config.md). Read it before editing
`code-quality-config-overrides/qodana.yaml` or anything under `code-quality-config/java/qodana/`.

### A long line in `pom.xml` fails Checkstyle

**Checkstyle runs TWICE**: the inherited `default` execution over the Java sources, and a second `pom` execution over `pom.xml` itself. That second
run is the ONLY check in the whole gate that reads an XML file, so the shared config's file-level rules (`LineLength` max 150, `NewlineAtEndOfFile`,
`FileTabCharacter`) are all that stands between a malformed POM and the release pipeline's SonarQube run. Other XML (`docs/*.xml`) is still unlinted.

### None of the above — check master

**Master's gate accumulates debt between sessions**, because commits land without running `-Dall`, and the lint
tools float their versions upstream so it can go red with no commit at all. It has been red on arrival repeatedly
(pre-existing Checkstyle/PMD violations, a Checkstyle module removed by an upstream bump, a pre-existing Qodana
`ConstantDeclaredInInterface` finding). **Before attributing a failure to your change, stash and run the failing
step on clean master.** If it is pre-existing: fix it if it blocks you, and report it to the user as work done
separately from what they asked for. Do not silently absorb it into the change.

Note the Maven tier runs the full unit + IT suite *before* Checkstyle, so a green test count above a linter failure
already tells you your own change is fine.

## 5. Scoped iteration

- **Unit tests only** (no DB): `mvn test -Dtests`, or `mvn test -Dtests -Dtest=MyTestClass`.
- **Test sources do not compile without `-Dtests`** — plain `mvn test-compile` prints "Not compiling test sources".
  Use `mvn test-compile -Dtests -Dcss.build.skip=true`.
- **PITest needs `-Dskip-linters=false`.** Keep logic in pure static helpers; thin glue reports as `NO_COVERAGE`.
- **E2E against an already-running app**: `cd tests && BASE_URL=http://localhost:8081 npm test`.
- **E2E around a broken linter tier**: `mvn clean package -Dtests` then
  `bash tests/run-e2e.sh 8081 "$(pwd)/target" "$(pwd)"` — that script brings up and tears down its own database and
  refuses to start if anything is already serving :8081.

## 6. When it is green

Stop. Do not re-run a step whose inputs have not changed since it passed, and do not add a formatting, coverage or
docs pass the task did not ask for.
