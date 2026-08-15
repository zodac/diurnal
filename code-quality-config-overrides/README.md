# Code Quality Config Overrides

## Overview

This project's own linting configuration: the files that override or extend the shared rules in the
[code-quality-config](../code-quality-config) submodule, rather than replacing them. They live here, in one tracked
directory named for the submodule they layer over, instead of as dotfiles at the repo root.

Nothing here is found by convention. Each tool is pointed at its file explicitly by
[lint_and_tests.sh](../.github/scripts/lint_and_tests.sh) — Grype with `-c`, Qodana with `--config` — so a scan run by
hand must pass the same flag.

Relative paths *inside* these files are mostly repo-root-relative, with one exception worth knowing before editing
`qodana.yaml`: its `imports:` resolves against **this directory** (hence the `../` in front of the submodule profile),
while its `exclude:` paths and per-inspection `ignore:` globs resolve against the **repo root**. A broken import fails
the scan outright rather than quietly falling back.

## Tools

### Docker

#### [Grype](https://github.com/anchore/grype)

- [.grype.yaml](./.grype.yaml) — the CVEs this project accepts, appended to the submodule's shared ignore list. Passed
  first of the two `-c` files, so its scalars win.

### Java

#### [Qodana](https://www.jetbrains.com/qodana/)

- [qodana.yaml](./qodana.yaml) — the linter image pin, the `imports:` of the submodule's inspection profile, this
  project's inspection overrides, and the excluded paths.
