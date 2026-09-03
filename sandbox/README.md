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
- **What `--privileged` actually costs — read this before trusting the list above.**
  The nested daemon needs it, and it is not free: the container holds
  `CAP_SYS_ADMIN`, `CAP_SYS_MODULE` and `CAP_MKNOD`, and the host's raw block
  devices (`/dev/nvme0n1p*`) are visible inside it. Root in here can therefore
  `mount` the host disk and read/write **the entire host filesystem** - `~/.ssh`
  included - regardless of what is or is not bind-mounted; loading a kernel module
  is a second path. The `dev` user has passwordless `sudo`, so the non-root user
  above does not gate any of that.
  **So: the mount list is a guard against ACCIDENTS - a careless `rm -rf`, a
  runaway test, Claude with `--dangerously-skip-permissions` - and it is a good
  one. It is NOT a security boundary against deliberately hostile code**, which is
  worth remembering now that `setup.sh` runs `npm install` for whatever project
  this folder was copied into (a malicious `postinstall` inherits everything
  above). Closing that gap means dropping `--privileged`, which in turn means a
  runtime built for it - [`sysbox-runc`](https://github.com/nestybox/sysbox) runs
  Docker-in-Docker unprivileged.

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
./sandbox/sandbox.sh prune      # reclaim disk in the nested docker (see Persistence)
```

**A launch replaces any sandbox that is already running.** Two of them cannot coexist — they share the
container name, the published port and the named volumes, in particular `/home/dev/.claude`, whose
login/session state Claude rewrites in place — so starting a second one used to take *both* down. A
launch therefore stops and removes the running container before starting its own, and waits for the
name to actually be free (`--rm` removal is asynchronous, so `docker run --name` can otherwise lose the
race). That happens **after** the image has been rebuilt, so the outgoing session stays usable for the
whole build and the gap between the two is just the teardown. Each launcher tears down the container it
started **by id** (`--cidfile`), never by name, so a departing session can never stop the one that
replaced it.

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
command, no PTY needed). Running it does `docker stop diurnal-sandbox` (the container name, derived from the project
directory), which trips the running
launcher's teardown (below). This is the closest thing to a Stop button.

## Shutdown — how the container is torn down

`sandbox.sh` stops **and removes** the container whenever the launcher ends. Any of these triggers it:

- **closing the terminal tab** (the ✕ on the tab) **or quitting IntelliJ** — sends SIGHUP/SIGTERM to the
  launcher, firing its trap. *This is the everyday "stop" in terminal mode.*
- running the **`Claude Sandbox (Stop)`** config (or `./sandbox/sandbox.sh stop` in any terminal) — the
  `docker stop` makes the running `docker run --rm` client exit, firing the same teardown.
- typing `exit` / Ctrl-D in the Claude session.
- **starting another sandbox** — the new launcher stops the old container once its own build is done
  (see "Use" above).

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
2. `npm install` — only in a directory that actually **has** a `package.json` (looked for in
   `frontend/` then the project root, override with `SANDBOX_NPM_DIRS`), and only if its
   `node_modules` is missing or `package-lock.json` changed (tracked by a hash in the persistent
   state volume).
3. `npx playwright install chromium` — only if the project **declares** Playwright (looked for in
   `tests/` then the root, override with `SANDBOX_PW_DIRS`), once per Playwright volume.

Steps 2 and 3 *detect* rather than assume, so this file survives being copy-pasted into a project
with no Node side: it reports `(not applicable)` and runs nothing. That guard is load-bearing, not
cosmetic — `npm --prefix frontend install` against a missing `frontend/` still **writes**
`frontend/package-lock.json` before it errors, so an unguarded install would litter an unrelated
project with a stray lockfile on its first open.

Scripted `run <cmd>` invocations skip setup (no TTY) so they stay fast. After
setup, everything works normally: `mvn clean install -Dall`, `scripts/dev-up.sh`,
`docker compose -p diurnal-dev -f docker-compose.dev.yml up -d diurnal-db-dev`, etc.

## Reusing this sandbox in another project

Copy the `sandbox/` folder in, and launch. Nothing needs renaming: the container, image, hostname and
all four volumes are derived from the project directory's name (see *Persistence*), and the image is
built from **this folder alone** — the `Dockerfile` `COPY`s only `entrypoint.sh`, `launch.sh` and
`setup.sh`, never any project file — so the build context carries no assumption about the project.

What the new project may want to set:

| Variable           | Default         | When to change it                                                                                                |
|--------------------|-----------------|------------------------------------------------------------------------------------------------------------------|
| `SANDBOX_PORTS`    | `8071:8081`     | space-separated `host:container` pairs; **empty publishes nothing**. Two sandboxes cannot both hold host `:8071` |
| `SANDBOX_NAME`     | the project dir | two checkouts of the *same* repo that must not share state                                                       |
| `SANDBOX_NPM_DIRS` | `frontend .`    | the Node project lives somewhere else                                                                            |
| `SANDBOX_PW_DIRS`  | `tests .`       | the Playwright suite lives somewhere else                                                                        |
| `PROJECT_DIR`      | the parent dir  | the folder is not at `<project>/sandbox/`                                                                        |

What is baked into the image regardless: **JDK 26, Maven, Node, Playwright's OS libs, Docker-in-Docker**
and the Claude CLI. A project that needs none of the JVM half still works — it just carries a larger
image than it needs, so trim the `COPY --from=jdk` / `--from=maven` stages if that matters.

## Persistence

Named volumes survive across runs (so you don't re-pull/re-download each time):

| Volume                   | Holds                                                                      |
|--------------------------|----------------------------------------------------------------------------|
| `diurnal-sandbox-claude` | Claude auth, history **and** onboarding/terminal-setup state (`~/.claude`) |
| `diurnal-sandbox-docker` | nested Docker images/layers (`/var/lib/docker`)                            |
| `diurnal-sandbox-m2`     | the Maven repository (`~/.m2`)                                             |
| `diurnal-sandbox-pw`     | Playwright browsers                                                        |

### Disk usage — the `-docker` volume is the one that matters

Measured on a two-month-old sandbox:

| Volume                   | Size      | Bounded?                                                               |
|--------------------------|-----------|------------------------------------------------------------------------|
| `diurnal-sandbox-docker` | **77 GB** | **no** - 61 GB of it BuildKit cache, 21 GB images, from every gate run |
| `diurnal-sandbox-m2`     | 598 MB    | grows slowly with dependency churn                                     |
| `diurnal-sandbox-claude` | 279 MB    | yes - Claude prunes transcripts (and their side-car dirs) at 30 days   |
| `diurnal-sandbox-pw`     | small     | yes - one browser build                                                |

The image now ships `/etc/docker/daemon.json` with a BuildKit GC policy (`maxUsedSpace: 20GB`,
`keepDuration: 168h`), so the build cache self-limits from here on — a copied sandbox inherits the
bound with no setup. Images, stopped containers and dangling volumes are not covered by that policy;
reclaim them (and force a cache sweep now) with:

```bash
./sandbox/sandbox.sh prune
```

It prefers `docker exec` into a running sandbox, so it will not interrupt a live Claude session.
The trade-off is the obvious one: a pruned build cache makes the next `docker` gate run cold.

Transcript retention on the `-claude` volume is Claude's own `cleanupPeriodDays` (default 30) — set it
in `~/.claude/settings.json` inside the sandbox if a month of `/resume` history is more than you want.

> **Those names are derived, not hardcoded.** `sandbox.sh` builds every docker name - container, image,
> hostname and all four volumes - from the *project directory's* name: `<project>-sandbox[-claude|-docker|-m2|-pw]`.
> This matters when you **copy this launcher into another repo**, which is the expected way to reuse it. A hardcoded
> name travels with the copy, and the copy would then mount its own project at `/work` while attaching *these*
> volumes - so both repos would share one `~/.claude`: one prompt history (the up-arrow shows the other project's
> prompts), one memory directory, one set of transcripts offered by `/resume`. Nothing warns you, because the project
> key Claude derives from the mount point (`-work`) is identical for every project. A copied launcher scopes itself on
> its first launch instead. Set `SANDBOX_NAME` to pin a name explicitly - e.g. two checkouts of the *same* repo that
> must not share state.

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
>
> 1. **On launch**, it restores either file from the snapshot when the live copy is missing/empty/stub
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
docker volume rm diurnal-sandbox-claude diurnal-sandbox-docker diurnal-sandbox-m2 diurnal-sandbox-pw
```

> **This destroys Claude's memory directory, which is not in git.** Everything else on those volumes is
> regenerable (images re-pull, `node_modules` reinstall, a login is re-entered), but
> `<claude volume>/projects/-work/memory/` holds the accumulated `MEMORY.md` notes for this project and
> exists nowhere else. Copy it out first if you mean to keep it:
>
> ```bash
> ./sandbox/sandbox.sh run tar cf - -C /home/dev/.claude/projects/-work memory > claude-memory.tar
> ```

## Notes

- `~/git/diurnal/.env` and `secrets/` are inside the mounted tree, so the sandbox
  *can* read them. Fine for a local dev DB; move them out of the tree and inject
  via `-e` if you want zero exposure.
- Permission prompts are off by default, scoped to this sandbox only — never on
  your host. Two layers, because the flag alone does not cover every entry point:
  the entrypoint starts its no-command `claude` with
  `--dangerously-skip-permissions`, and `launch.sh` asserts
  `permissions.defaultMode = "bypassPermissions"` in the settings.json on the
  persisted config volume, so a `shell` session that runs claude by hand gets the
  same default. Flip that key to `"default"` (or `"auto"`) if you ever want a
  session to prompt.
- `gh` (the GitHub CLI) is in the image, but **unauthenticated by design, and stays
  that way**. Credentials are entered by hand in the session that needs them and are
  never persisted: nothing mounts `~/.config/gh`, so a `gh auth login` lives on the
  writable layer and dies with `--rm`, and no `GH_TOKEN` is forwarded into the
  container. **That is the policy, not a gap** — a session running with
  `--dangerously-skip-permissions` is not somewhere a GitHub credential should be
  sitting when nobody is watching. Do not "fix" it with a named volume for
  `~/.config/gh` or an `-e GH_TOKEN` passthrough in `sandbox.sh`. Re-authenticating
  each session is the cost, and it is the intended one. Unauthenticated `gh` still
  reads public repos.
