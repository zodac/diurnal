<!-- markdownlint-disable-file MD041 -- GitHub PR template: no top-level heading by design -->

## What this changes

<!-- A sentence or two. Link the issue it addresses, if there is one. -->

## Checklist

- [ ] The quality gate is green for the steps this change touches (`.github/scripts/lint_and_tests.sh`)
- [ ] Commit messages carry a `[Category]` prefix on **every** non-blank line
- [ ] Any database change is a **new** `V{n+1}__` migration; no existing migration was edited
- [ ] Documentation is updated if behaviour, configuration or an endpoint changed
- [ ] `RELEASE_NOTES.md`, `VERSION` and the pom `<version>` are untouched
