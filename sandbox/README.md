# diurnal-sandbox

A disposable Docker container for running **Claude Code with
`--dangerously-skip-permissions`** against the `diurnal` project, fully isolated
from the host machine.

This lives in `sandbox/` inside the project, so `~/git/diurnal` is the single root
for both the source code and its sandbox environment. The project root is mounted
into the sandbox at `/work` (this `sandbox/` dir included). Run everything below
from the project root unless noted.

## Why this is safe

- **Only the project directory is mounted** (`~/git/diurnal` → `/work`). No
  `$HOME`, no SSH keys, no other projects are visible to Claude.
- **The host Docker socket is NOT mounted.** The sandbox runs its *own* nested
  Docker daemon, so the dev DB / Testcontainers / Playwright containers the
  project spins up live and die *inside* the sandbox. Your host daemon and any
  running production container (`:8080`) are never touched.
- **Runs as a non-root user** (`dev`, matching your host UID 1000) so files
  written under `/work` stay owned by you. (Claude also refuses
  `--dangerously-skip-permissions` as root — another reason for the non-root user.)
- `--privileged` grants privilege over the *container's* kernel view (needed for
  the nested daemon), not over any host path. Isolation comes from not mounting
  host files.

## Build

```bash
./sandbox/sandbox.sh build
```

## Use

```bash
./sandbox/sandbox.sh            # interactive Claude session (--dangerously-skip-permissions)
./sandbox/sandbox.sh shell      # a bash shell instead
./sandbox/sandbox.sh run <cmd>  # run an arbitrary command (e.g. ./sandbox/sandbox.sh run mvn -v)
```

The dev app inside the sandbox is published on host **:8081** (so
`scripts/dev-up.sh` is reachable from your host browser).

## Automatic per-open setup

Every **interactive** open (the default session and `shell`) runs `setup.sh`
first, which is idempotent and guarded — it only does real work on a fresh
sandbox or when something changed, otherwise it's a fast no-op:

1. `git submodule update --init` — only if a submodule is uninitialised (linters
   need `code-quality-config`).
2. `npm install` — only if `node_modules` is missing or `package-lock.json`
   changed (tracked by a hash in the persistent state volume).
3. `npx playwright install --with-deps chromium` — once per Playwright volume
   (E2E browser + its OS libs).

Scripted `run <cmd>` invocations skip setup (no TTY) so they stay fast. After
setup, everything works normally: `mvn clean install -Dall`, `scripts/dev-up.sh`,
`docker compose -f docker-compose.dev.yml up -d diurnal-db-dev`, etc.

## Persistence

Named volumes survive across runs (so you don't re-pull/re-download each time):

| Volume                    | Holds                                            |
|---------------------------|--------------------------------------------------|
| `diurnal-sandbox-claude`  | Claude auth + history (`~/.claude`)              |
| `diurnal-sandbox-docker`  | nested Docker images/layers (`/var/lib/docker`)  |
| `diurnal-sandbox-pw`      | Playwright browsers                              |

Full reset (wipe all sandbox state):

```bash
docker volume rm diurnal-sandbox-claude diurnal-sandbox-docker diurnal-sandbox-pw
```

## Notes

- `~/git/diurnal/.env` and `secrets/` are inside the mounted tree, so the sandbox
  *can* read them. Fine for a local dev DB; move them out of the tree and inject
  via `-e` if you want zero exposure.
- `--dangerously-skip-permissions` is scoped to this sandbox only — it's the
  entrypoint default here and is never passed to Claude on your host.
