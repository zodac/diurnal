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
./sandbox/sandbox.sh stop       # stop & remove a running sandbox (one-click teardown)
```

The in-sandbox dev server (container **:8081**, e.g. `scripts/dev-up.sh`) is published to host
**:8071** — reachable at <http://localhost:8071>. It is deliberately **not** host :8081, so the host's
own 8081 (the project's testing port — see the port map in `CLAUDE.md`) stays free for host-native
dev/tests *while the sandbox is running*.

## Run it from IntelliJ (a "Claude Sandbox" run configuration)

The handiest way to use the sandbox day-to-day is a saved **Shell Script** run configuration:

1. **Run → Edit Configurations…**, click **+**, choose **Shell Script**.
2. **Name:** `Claude Sandbox`.
3. **Interpreter path:**
   - **macOS / Linux:** leave as the default (`/bin/bash`).
   - **Windows:** use Git Bash — `C:\PROGRA~1\Git\bin\bash.exe` (the 8.3 short path avoids the
     space-escaping bug IntelliJ has with `C:\Program Files\...` on Windows). If Git is installed
     somewhere else, find it with `where bash` in a terminal. Do **not** use `powershell.exe` —
     `sandbox.sh` is a bash script.
4. **Script path:** point it at `<project>/sandbox/sandbox.sh`. Leave **Script options** empty for the
   default interactive Claude session — or set `shell` for a bash shell, or `run <cmd>` for a one-off.
5. **Working directory:** the project root (`$ProjectFileDir$`).
6. **Tick "Execute in the terminal."** This is required — Claude's TUI needs a real PTY. Without it
   IntelliJ pipes the console (no TTY), `sandbox.sh` falls back to non-interactive `-i` mode, and the
   session won't render or accept keystrokes.
7. **Apply / OK**, then start it with **▶**. (First time only, build the image once — from a terminal,
   or a second run config whose Script options are `build`: `./sandbox/sandbox.sh build`.)

Because the project root is mounted at `/work`, edits in IntelliJ are visible to Claude immediately,
and vice versa.

> **No red ■ Stop button in terminal mode — that's expected.** IntelliJ only manages (and offers a Stop
> button for) a Shell Script run when it runs in the *console*. "Execute in the terminal" (required for
> Claude's PTY, step 6) hands the process to a terminal tab that IntelliJ no longer process-manages, so
> there's no Stop square — only a ▶ rerun. Use either shutdown method below.

### Optional: a one-click "Stop" run config

Add a second **Shell Script** config — **Name** `Claude Sandbox (Stop)`, same **Script path**, **Script
options** `stop` — and leave "Execute in the terminal" **unticked** (it's a quick non-interactive
command, no PTY needed). Running it does `docker stop diurnal-sandbox`, which trips the running
launcher's teardown (below). This is the closest thing to a Stop button.

## Shutdown — how the container is torn down

`sandbox.sh` stops **and removes** the container whenever the launcher ends. Any of these triggers it:

- **closing the terminal tab** (the ✕ on the tab) **or quitting IntelliJ** — sends SIGHUP/SIGTERM to the
  launcher, firing its trap. *This is the everyday "stop" in terminal mode.*
- running the **`Claude Sandbox (Stop)`** config (or `./sandbox/sandbox.sh stop` in any terminal) — the
  `docker stop` makes the running `docker run --rm` client exit, firing the same teardown.
- typing `exit` / Ctrl-D in the Claude session.

It does this with a `trap` that runs `docker stop`, and by launching `docker run` as `… & wait` so the
trap fires the instant the signal arrives (a foreground command would defer it until it returns — too
late, once IntelliJ escalates to SIGKILL). `docker run --rm` then removes the stopped container, and
the nested `dockerd` plus everything it spun up (dev DB, Testcontainers, Playwright) dies with it.

> Previously the container could linger: `--rm` only fires when the container *exits*, so a `docker
> run` client that was killed left its container — and its published port — running in the daemon.

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

| Volume                   | Holds                                                                      |
|--------------------------|----------------------------------------------------------------------------|
| `diurnal-sandbox-claude` | Claude auth, history **and** onboarding/terminal-setup state (`~/.claude`) |
| `diurnal-sandbox-docker` | nested Docker images/layers (`/var/lib/docker`)                            |
| `diurnal-sandbox-pw`     | Playwright browsers                                                        |

> **Why login + terminal setup persist:** Claude Code normally splits its state between the
> `~/.claude/` directory and a separate `~/.claude.json` file in the home root (onboarding /
> terminal-setup state + login *account*; the OAuth *tokens* live in `~/.claude/.credentials.json`).
> Only the directory is volume-mounted, so the loose `.claude.json` would be lost every run. The image
> sets `CLAUDE_CONFIG_DIR=/home/dev/.claude` (forwarded to the `dev` user by `entrypoint.sh`), which
> redirects `.claude.json` and credentials **into** the persisted volume — so you configure the
> terminal and log in once, not every launch.
>
> **Why a login could still be lost (and how it's recovered):** Login depends on **two** files —
> `.claude.json` (onboarding + account) and `.credentials.json` (the OAuth tokens; this is the one that
> actually gates login). Both are rewritten atomically (write-temp + rename), and the container is torn
> down with a bounded `docker stop` grace — so an *abrupt* kill (e.g. the IDE SIGKILLing the launcher
> before its teardown trap can `docker stop`) can interrupt a rename and leave a file **missing or a
> truncated stub**. Lose `.credentials.json` and you're forced to log in again no matter how healthy the
> rest of the state is; lose `.claude.json` and you're re-onboarded. Claude's *own* `~/.claude/backups/`
> are not a reliable fallback — they are frequently truncated to a `{firstStartTime}` stub by the same
> interrupted write, and there is no equivalent for `.credentials.json` at all.
>
> So the sandbox keeps its **own** known-good snapshot of *both* files under `~/.claude/.sandbox-state/`
> (`claude.json.bak` + `credentials.json.bak`), managed by `launch.sh`:
> 1. **On launch** it restores either file from the snapshot when the live copy is missing/empty/stub
>    (preferring the snapshot over Claude's stub-prone backups), then snapshots whatever good state exists.
> 2. **During an interactive session** a lightweight background watcher re-snapshots the healthy live
>    files every 15s — so a brand-new login (or an OAuth-token rotation) is captured within seconds and
>    survives *any* later kill, even one that skips the teardown grace entirely.
>
> `docker stop -t 10` (in `sandbox.sh`) is still belt-and-braces — it gives Claude time to flush on a
> *clean* stop — but recovery no longer depends on it. (Manual restore if ever needed:
> `cp ~/.claude/.sandbox-state/credentials.json.bak ~/.claude/.credentials.json` and likewise for `claude.json.bak`.)
>
> **After changing `launch.sh` you must `./sandbox.sh build`** — the script is `COPY`d into the image at
> build time, so a running image keeps the old copy until rebuilt.

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
