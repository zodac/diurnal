#!/bin/busybox sh
# shellcheck shell=sh
#
# Container entrypoint: decides how the JVM heap is sized, then hands over to the application.
#
# The heap FOLLOWS THE CONTAINER'S MEMORY LIMIT (-XX:MaxRAMPercentage), so docker-compose.yml's
# mem_limit is the single knob and nothing has to be kept in sync with it. This script exists for the
# one case a percentage cannot express: no limit at all. The JVM then resolves that percentage against
# TOTAL HOST RAM, which on the reference host meant a 15.7 GB max heap, a 9.4 GB max young gen, and G1
# growing eden to 1.7 GB and committing 2.8 GB to hold a 433 MB live set - 3.1 GB of RSS that never
# came back. An unlimited container is therefore capped to the same ~1.33 GB heap the default mem_limit
# produces.
#
# There is NO JVM flag for "cap only when unlimited", which is the whole reason a shell runs here, and
# the two flags that look like one are not. -XX:MaxRAM REPLACES the detected memory rather than being
# min'd with it (measured: -XX:MaxRAM=512g yields a 332 GiB heap on a 62 GiB machine), so setting it
# unconditionally would override a SMALLER cgroup limit and get the container OOM-killed - and it is
# deprecated as of JDK 26 besides. A bare -Xmx has the same override problem in both directions. So the
# two cases are simply given different flags: a percentage when there is a limit to take a percentage
# OF, and an absolute size when there is not.
#
# Run by the image's ENTRYPOINT as `busybox sh /app/start.sh`. Interpreted by busybox's ash applet -
# the runtime image is distroless and has no /bin/sh - so keep this POSIX, with no bashisms.

set -eu

# Above this, a value is how cgroup v1 spells "unlimited" (9223372036854771712) rather than a real
# budget. 1 TiB: far beyond any sane limit for this application, far below the sentinel.
UNLIMITED_ABOVE='1099511627776'
# Fraction of the container's memory given to the heap when there IS a limit. The remaining 35% is not
# spare: it is metaspace, the code cache, thread stacks and the collector's own structures.
HEAP_PERCENT='65'
# Absolute heap used when there is NOT. The maximum is HEAP_PERCENT of docker-compose.yml's
# APP_MEM_LIMIT default (65% of 2g), so an unlimited deployment tops out at the same heap the documented
# limited one does.
#
# Unlike the limited branch this is a RANGE, and the asymmetry is deliberate: with a cgroup limit the
# budget is known and guaranteed, so committing all of it up front costs nothing, whereas here the
# maximum is a GUESS about a host whose memory is unknown. -Xms commits address space, so a fixed
# 1330m can fail to start outright on a small host, or one running strict overcommit
# (vm.overcommit_memory=2), where growing into the same heap would have succeeded. The minimum still
# sits above the measured ~354 MB live set, so a healthy deployment reaches steady state without
# resizing; only an unusually constrained host ever notices the difference.
#
# Note this is NOT about resident memory: measured on the real image, fixed-vs-ranged changes RSS after
# readiness by ~13 MiB (442 vs 429), because AlwaysPreTouch is off and committed pages are not resident
# until touched. The reason to prefer a range here is startup robustness, nothing else.
FALLBACK_HEAP_MIN='512m'
FALLBACK_HEAP_MAX='1330m'

# The container's memory limit, in bytes, or the cgroup v2 "max" sentinel. Both cgroup filesystems are
# checked because the host decides which is mounted, and a container started under cgroup v1 exposes
# only the second; an unreadable pair leaves this empty, which counts as "no limit" below.
limit=''
if [ -r /sys/fs/cgroup/memory.max ]; then
    limit="$(/bin/busybox cat /sys/fs/cgroup/memory.max)"
elif [ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
    limit="$(/bin/busybox cat /sys/fs/cgroup/memory/memory.limit_in_bytes)"
fi

# Four things mean "no limit": an empty value (unreadable cgroup files), the cgroup v2 "max" sentinel,
# any other non-numeric value, and a number above UNLIMITED_ABOVE. The non-numeric arm is what makes
# this fail SAFE - anything unrecognised takes the conservative cap rather than sizing the heap against
# a host whose memory this container may not be entitled to. It is also why the numeric comparison is
# reached ONLY once the value is known to be all digits: `[ max -le ... ]` is an error, not a false,
# and an unguarded test would send exactly the values that most need the cap down the other branch.
unlimited='yes'
case "${limit}" in
    '' | *[!0-9]*) ;;
    *)
        if [ "${limit}" -le "${UNLIMITED_ABOVE}" ]; then
            unlimited='no'
        fi
        ;;
esac

# The remaining flags are fixed, and each is here rather than in the jlink --add-options baked into the
# JRE because they are properties of how this app is DEPLOYED, not of the runtime image.
#
# UseG1GC is pinned rather than left to ergonomics, which is the trap the sizing above creates: the JVM
# only selects G1 (and a 25% heap) when it considers itself "server class", i.e. >= ~1792 MB and
# >= 2 CPUs. ADDING a memory limit can therefore silently downgrade a deployment to SerialGC with a
# 256 MB heap, which against Argon2id's allocation bursts would be pathological. Pinning it makes the
# collector independent of how small mem_limit is set.
#
# On the limited branch the initial heap matches the maximum, so it starts at its final size and RSS is
# stable from the outset. What that does NOT do is prevent the runaway a JFR recording caught (an 8 MB
# initial heap reaching 2.8 GB committed in ~13 minutes): that was driven by an unbounded MAXIMUM, which
# let G1 grow eden to 1.7 GB, and bounding the maximum is what fixes it on either branch.
# AlwaysPreTouch is deliberately NOT set - it would turn the commit into startup latency, against the
# image HEALTHCHECK's 30s start-period, and it is why -Xms costs address space rather than RSS.
#
# MaxDirectMemorySize bounds the OFF-heap NIO/Netty buffers, which otherwise default to the maximum heap
# size - a second budget as large as the heap itself, outside it. Left at that default the JVM's ceilings
# (heap + direct + metaspace + code cache + stacks) sum to well over the container's memory limit, and
# exceeding the limit is a KERNEL kill: an opaque exit 137, with no OutOfMemoryError for
# ExitOnOutOfMemoryError below to turn into a clean restart. Bounding it keeps that failure inside the JVM
# where it is diagnosable.
#
# 256m is not arbitrary. A JFR recording of a real deployment - taken with the data import/export
# exercised, which is the only path that moves real volume through these buffers - peaked at 15.0 MB
# across 244 buffers, so this is ~17x the measured high-water mark. It is sized against
# quarkus.http.limits.max-body-size (MAX_UPLOAD_SIZE, default 100M) rather than that measurement alone,
# because body buffering is what consumes direct memory and a bound below the largest accepted request
# would be the wrong shape. RAISE THIS ALONGSIDE MAX_UPLOAD_SIZE if a deployment increases it.
#
# ExitOnOutOfMemoryError pairs with `restart: unless-stopped`: a JVM that has exhausted the heap should
# die and be restarted, not linger serving errors. HeapDumpOnOutOfMemoryError is deliberately ABSENT -
# a heap dump would write every decrypted note and unwrapped data key to disk in the clear, which is
# exactly the exposure the at-rest encryption exists to prevent (see NOTES.md).
#
# Built as positional parameters so every argument stays quoted; the cap is one argument or none, and
# an unquoted variable would otherwise pass java an empty string when a limit IS set.
set -- -XX:+UseG1GC
if [ "${unlimited}" = 'yes' ]; then
    set -- "$@" "-Xms${FALLBACK_HEAP_MIN}" "-Xmx${FALLBACK_HEAP_MAX}"
    # Said out loud because an under-provisioned heap is otherwise invisible until the app is slow.
    echo "INFO  [diurnal] no container memory limit detected - capping the JVM heap at ${FALLBACK_HEAP_MAX}." \
        "Set one (mem_limit in docker-compose.yml) to size the heap from it; see Performance Tuning in README.md"
else
    set -- "$@" "-XX:InitialRAMPercentage=${HEAP_PERCENT}" "-XX:MaxRAMPercentage=${HEAP_PERCENT}"
fi
set -- "$@" -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError

# `exec` replaces this shell with the JVM, so java becomes PID 1 and receives SIGTERM from `docker stop`
# directly. Without it the shell would hold PID 1 and the JVM would never be signalled, leaving every
# shutdown to the kill timeout.
exec /opt/jdk/bin/java "$@" -jar quarkus-run.jar
