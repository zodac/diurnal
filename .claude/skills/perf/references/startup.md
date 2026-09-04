# Startup and boot time

**Class data sharing (AppCDS) was built, measured end-to-end and REMOVED. Do not rebuild it without reading
this.** It is not that CDS does nothing - it is that what it does never reaches the user:

|         | in-app `System cold start` | container create -> first `200 /api/v1/status` |
|---------|----------------------------|------------------------------------------------|
| CDS off | 2.48s                      | **3.39s**                                      |
| CDS on  | 1.61s                      | **3.34s**                                      |

- **Measure the right thing.** Interleaved, 3 reps each, same container, page cache held warm. The JVM-side
  saving is real, large and almost perfectly repeatable (-0.87s, -35%, sigma ~0.003s). Time to *serving*
  moves 0.05s - about 1.5%. **The `System cold start` line is not a proxy for readiness**, and measuring
  with it is what made this look like a 35% win for three rounds of work.
- **The headline that sold it was a measurement error.** An earlier "7.4s -> 3.4s" came from comparing a cold
  container (first read of ~95MB of jars + JRE off the overlay, *and* building the archive) against warm
  ones. Hold the page cache constant and an archive-less boot is 3.39s. That was page cache, not CDS.
- **Against 0.05s stood**: ~117 lines of entrypoint shell in a distroless container (which produced two real
  bugs, `rm: not found` and a stale-archive-never-retrained bug, *both* surfacing only on upgrade, i.e. on a
  user's machine and not in any tier); a named volume across three compose files plus ~100MB of host disk;
  and an upgrade path no test tier covers. Baking the archive into the image instead costs +26MB on every
  pull, +23MB per release (it changes every build, defeating the four-layer split), and ~1 min on every
  image build.
- **The budgets say there was nothing to win.** The image `HEALTHCHECK` allows a 30s start period and
  `PERF_BOOT_BUDGET_S` is 20s, against a ~3.4s boot - roughly 6x headroom, on an app a self-hoster restarts
  a handful of times a year.
- **Trigger to revisit**: a deployment shape where containers start *often* and JVM startup is therefore the
  user-visible number - Kubernetes with scale-to-zero, or ephemeral per-request containers. Not "boot feels
  slow" on a long-lived compose stack. If it is revisited, measure container-create-to-serving with a warm
  page cache, never `System cold start`.

**What DID move readiness, and was kept**: the admin update check no longer runs on the startup thread (see
the Update check section). Verified end-to-end - the app answers its first `200` at `14:38:14.035` and the
GitHub lookup completes at `14:38:14.334`, 0.3s *after* readiness, where it previously blocked it.
