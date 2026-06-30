#!/usr/bin/env bash
#
# Self-contained deployment-smoke runner, invoked from the `deployment-smoke` exec execution in
# pom.xml (the `-Dall` profile), bound to the `install` phase alongside `e2e-run`. Where run-e2e.sh
# exercises the packaged fast-jar on a full JDK, THIS script exercises the REAL production Docker
# image (built from the Dockerfile) so the suite catches bugs that live only in the distroless /
# jlink / non-root runtime — e.g. a missing jlink module (see the java.rmi incident), non-root write
# permissions, or a CSS-hash / favicon build-stage desync.
#
# It is fully self-contained and namespaced so it never collides with a running production stack
# (docker-compose.yml) or with run-e2e.sh: a dedicated compose project (`-p diurnal-smoke`), an
# ephemeral tmpfs DB, and host port 8082 (!= 8080 prod, != 8081 dev/E2E). An EXIT trap always tears
# the whole stack down — on success OR failure — and the script exits with Playwright's own exit
# code, so a failing smoke run fails the Maven build.
#
# Ordering note: this script and run-e2e.sh are the two exec executions in the `install` phase.
# sortpom reorders executions, so their relative order is NOT guaranteed — they are deliberately
# order-independent (disjoint ports, disjoint compose projects/DBs, each self-cleaning, each failing
# the build on its own). Never make one depend on the other running first.
#
# Args (passed positionally from the exec plugin so Maven properties resolve in the POM, not here):
#   $1  host port to publish the app on (= Playwright base-URL port)
#   $2  Maven ${project.basedir} (project root; holds the Dockerfile, the compose file and e2e/)
#
# Readiness/Playwright use 127.0.0.1 (not 'localhost') because Node may otherwise resolve localhost
# to IPv6 ::1 while the published port binds IPv4.

set -eu

PORT="$1"
BASEDIR="$2"

COMPOSE_FILE="${BASEDIR}/docker-compose.smoke.yml"
PROJECT="diurnal-smoke"

cleanup() {
  # Best-effort + idempotent. `down` removes the tracked stack (containers, network, volumes); the
  # label-based sweep afterwards catches anything `down` missed — e.g. a container left half-created
  # by an interrupted `up`, which `down` won't always reap. Both are quiet so they never mask the
  # real (Playwright/Maven) exit code.
  docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" down -v --remove-orphans --timeout 10 >/dev/null 2>&1 || true
  local leftovers
  leftovers="$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" 2>/dev/null || true)"
  [ -n "${leftovers}" ] && docker rm -f ${leftovers} >/dev/null 2>&1 || true
}
# EXIT alone is not enough: a SIGINT (Ctrl-C) or SIGTERM (Maven/CI killing the child when the build
# fails) would skip an EXIT-only trap and leak the stack. Trap the signals to a plain `exit`, which
# then fires the EXIT trap exactly once — so cleanup runs on normal exit, on test failure, AND on
# interruption.
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# Build the real image and bring the stack up, blocking until the image's own HEALTHCHECK reports
# healthy (--wait). A boot failure (e.g. a missing jlink module) trips here and fails the script
# before Playwright even starts. --wait-timeout covers app start-period + retries, not the build.
SMOKE_PORT="${PORT}" docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" \
  up -d --build --wait --wait-timeout 240

# Run the smoke suite against the live container; its exit code becomes the script's (cleanup runs
# via the EXIT trap regardless), so a non-zero result fails the Maven build.
(cd "${BASEDIR}/e2e" && BASE_URL="http://127.0.0.1:${PORT}" \
  npx playwright test --config playwright.smoke.config.ts)
