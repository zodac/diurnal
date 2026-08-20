# Code Quality Config Overrides

## Overview

Mostly this project's own linting configuration: the files that override or extend the shared rules in the
[code-quality-config](../code-quality-config) submodule, rather than replacing them. They live here, in one tracked
directory named for the submodule they layer over, instead of as dotfiles at the repo root. One exception —
[crowdin.yml](./crowdin.yml) — is unrelated to that submodule and lives here anyway, for the same "no tool
dotfiles at the repo root" reason rather than because it overrides anything code-quality-config ships.

Nothing here is found by convention. Each tool is pointed at its file explicitly — Grype with `-c`, Qodana with
`--config`, SonarQube by having its properties read out and passed as `-D`s (all three by
[lint_and_tests.sh](../.github/scripts/lint_and_tests.sh)), CrowdIn with `--config` on a hand-run `crowdin` CLI
invocation (not yet wired into the gate) — so a scan/sync run by hand must do the same.

Relative paths *inside* these files are mostly repo-root-relative, with one exception worth knowing before editing
`qodana.yaml`: its `imports:` resolves against **this directory** (hence the `../` in front of the submodule profile),
while its `exclude:` paths and per-inspection `ignore:` globs resolve against the **repo root**. A broken import fails
the scan outright rather than quietly falling back. `crowdin.yml` hits the same class of problem (its own tool
defaults to resolving paths against the file's own directory, not the repo root) and solves it the way that tool
actually supports: an explicit `base_path: ..`, rather than a `qodana.yaml`-style split between two resolution
rules.

## Tools

### Docker

#### [Grype](https://github.com/anchore/grype)

- [.grype.yaml](./.grype.yaml) — the CVEs this project accepts, appended to the submodule's shared ignore list. Passed
  first of the two `-c` files, so its scalars win.

### Java

#### [Qodana](https://www.jetbrains.com/qodana/)

- [qodana.yaml](./qodana.yaml) — the linter image pin, the `imports:` of the submodule's inspection profile, this
  project's inspection overrides, and the excluded paths.

#### [SonarQube](https://www.sonarsource.com/products/sonarqube/)

- [sonar.properties](./sonar.properties) — this project's analysis properties, today the `java:S3252` rule exclusion
  (a false positive on every Panache active-record call site, whose suggested fix breaks the app at runtime). Read
  line-by-line and appended as `-Dkey=value` to the analysis build, because the SonarScanner **for Maven** — which is
  how this project analyses — reads only Maven properties and ignores a `sonar-project.properties` entirely.

### Translation

Not a `code-quality-config` override — see the Overview above for why it lives here anyway.

#### [CrowdIn](https://crowdin.com/)

- [crowdin.yml](./crowdin.yml) — maps `translations/msg_en-GB.properties` (the generated source-language export,
  see `scripts/generate-source-messages.sh`) to a `msg_<locale>.properties` per offered language. Starter config,
  not yet wired to a live project — see the file's own header and [`I18N.md`](../.claude/I18N.md)'s "Translation
  tooling" section.
