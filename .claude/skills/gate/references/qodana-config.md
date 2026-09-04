# Configuring the Qodana tier

Read this only when editing the Qodana profile or its config - its timings and its config are both easy to get
wrong, and a wrong guess costs a full ~6-minute `java:qodana` cycle. For reading a FINDING out of a completed
scan, the `gate` skill itself is enough.

Its timings and its config are both easy to get wrong, so keep these to hand when editing the profile:

- **Timing**: warm it is ~2m35s (index + module model cached), *not* the longest tier — the Maven tier (~4m) and the
  E2E run chained behind it set the `java` step's wall clock. Cold it is ~10m and dominates everything, which is why
  CI caches `.qodana/cache` but never `.qodana/results` (the SARIF must not be inherited). Running it in parallel
  with Maven stays worth it on a busy machine (436s together against 581s sequentially), and **capping the scan's
  CPU makes it worse** — the two just converge on equal finish times.
- **Its config is not at the repo root**, so the CLI does not find it by convention: the wrapper passes
  `code-quality-config-overrides/qodana.yaml` with `--config`, and so must any hand-run scan. The grype ignore list
  sits beside it (`code-quality-config-overrides/.grype.yaml`, passed with `-c`), so the repo root holds no linter
  dotfiles at all.
- **Its two kinds of relative path resolve differently**, and `--config --help` is wrong about the first:
  `imports:` is relative to the **config file** (hence the `../` in front of the submodule profile), while
  `exclude:` and the per-inspection `ignore:` globs are relative to the **project**. A bad import fails the run
  outright ("imports file not found"), so that one cannot go green by accident.
- **The profile MUST be reached by `imports:`.** `profile: path:` / `base: path:` / `--profile-path` are accepted
  and then silently ignored, falling back to the IDE Default profile — which omits `UnusedDeclaration`, the whole
  point of the tier. `--config`, unlike those, *is* honoured.
- **Order decides inside the profile**: an individual `- inspection:` entry placed ABOVE its own
  `- group: "category:…"` is silently re-enabled by it.
- **It reports on Java source only** — the profile's first entry is `ignore: ["*", "**/*", "!**/*.java"]`, so every
  other file type stays with the gate that already covers it.
- **Both its inputs are in the submodule** (`git submodule update --init` is a prerequisite; the wrapper refuses to
  start the container without them). The dead-code check needs the entry points in
  `code-quality-config/java/qodana/overrides/`, which the wrapper copies into `.qodana/idea-config` and mounts over
  `.idea/` inside the container — without them 238 framework-instantiated declarations report as unused, with them
  0. A change under `code-quality-config/java/` or to that `qodana.yaml` auto-detects as the `java` step.
