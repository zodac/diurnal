# Contributing to Diurnal

Thanks for taking an interest. Diurnal is a personal project, so please open an issue to discuss anything substantial before writing it - it saves
you the work if it is not a direction the project is going in.

## Reporting Bugs and Requesting Features

Use the [issue tracker](https://github.com/zodac/diurnal/issues). For a bug, the version (shown in the footer, and by `GET /api/v1/status`) and the
relevant container logs are usually enough to get started. For a security problem, follow [SECURITY.md](SECURITY.md) instead - not the issue tracker.

## Translations

Translations are **not** edited in this repository. They are managed on [Crowdin](https://crowdin.com/project/diurnal), which opens a pull request
here automatically once strings are translated. Only `translations/msg_en-GB.properties`, the source bundle, is edited by hand.

## Building Locally

Requires JDK 26, Maven 3.9+, Node 26+ and Docker (with the Compose v2 plugin).

```bash
git submodule update --init          # lint config; required for -Dlint / -Dall
npm --prefix frontend install        # required for any mvn build to produce the CSS
cd tests && npx playwright install   # required for the E2E tier
```

Run the application in dev mode, with hot reload and Swagger UI on port 8081:

```bash
scripts/dev-up.sh                    # starts PostgreSQL, then mvn quarkus:dev
scripts/dev-teardown.sh              # always tear down when you are done
```

## Before Opening a Pull Request

Run the quality gate. Bare, it auto-detects which steps your change needs; a step name narrows it:

```bash
.github/scripts/lint_and_tests.sh              # auto-detect
.github/scripts/lint_and_tests.sh java         # after any Java, template, CSS or UI change (~10 minutes)
.github/scripts/lint_and_tests.sh shellcheck   # after editing any .sh file
.github/scripts/lint_and_tests.sh markdown     # after editing any .md file
```

Everything has to be green, including the linters - Checkstyle, PMD and SpotBugs all sit at zero findings and PITest at 100% mutation strength, and
the intent is to keep them there.

Installing the git hooks makes this harder to forget:

```bash
.hooks/install_hooks.sh
```

`pre-commit` runs the auto-detected gate, and `commit-msg` enforces the message format below.

## Commit Messages

Every non-blank line needs a `[Category]` prefix, including body lines - not just the subject:

```text
[UI] Increment button now has a distinct hover colour
```

Reuse an existing category rather than inventing one; `git log --format='%s'` shows the vocabulary in use (`[CI]`, `[UI]`, `[Java]`, `[API]`,
`[Testing]`, `[Documentation]`, `[Deployment]`, `[Security]`, and so on).

## A Few Rules Worth Knowing Up Front

These are the ones most likely to trip up a first change:

- **Never modify an existing Flyway migration** - not the SQL, not a comment, not whitespace. Flyway checksums every applied migration and refuses to
  start when one changes. Express any change, including reverting one, as a new `V{n+1}__` file.
- **Log messages must be plain ASCII.** The production container renders anything else as `?`. Use a hyphen, never an em-dash.
- **A log line names a user by email, not by id.** An id cannot be matched against a support request.
- **Every URL the app emits is built by `http/AppPaths`**, never written out at the call site - that is what lets `BASE_PATH` work.
- **Rebuild the CSS after changing a Tailwind class** in a template or in Java (`npm --prefix frontend run css`), or it gets purged.
- **The README screenshots are not in the repository.** They are assets on the standalone `screenshots` release, which is why the README embeds them
  by absolute URL, and the release workflow recaptures and replaces them on every release. You never need to refresh them by hand; to look at them
  locally, `node scripts/generate-screenshots.cjs documentation` writes them to a gitignored `docs/screenshots/`. Please don't add image files there.

Each has a test that fails when it is broken, so the gate will tell you. `RELEASE_NOTES.md`, `VERSION` and the pom's `<version>` are release
artefacts - please leave them out of a pull request.

## License

Diurnal is released under the [BSD Zero Clause License](LICENSE). Contributions are accepted under the same terms.
