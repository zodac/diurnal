/*
 * BSD Zero Clause License
 *
 * Copyright (c) 2026-2026 zodac.net
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package net.zodac.diurnal.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Typed view over the {@code password.hash.argon2.*} settings that tune the Argon2id password hashing cost.
 *
 * <p>
 * The three cost parameters should be tuned so a single hash takes roughly 100–500&nbsp;ms on the target production hardware — slow enough to
 * frustrate offline brute-forcing of a leaked hash, fast enough not to hurt legitimate logins. The defaults ({@value #DEFAULT_MEMORY_KIB}&nbsp;KiB of
 * memory, {@value #DEFAULT_ITERATIONS} iterations, {@value #DEFAULT_PARALLELISM} lanes) exceed the OWASP Argon2id memory guidance and were measured
 * at ~130&nbsp;ms on the reference host (an 8-core AMD Ryzen 7 5700X): 96&nbsp;MiB of memory-hardness held to that latency by spreading the work
 * across 4 lanes. Re-measure and adjust on other hardware — raise {@link #memoryKib()} first on a faster or higher-core machine, lower it on a
 * constrained one. Increasing any parameter transparently re-hashes each account on its next successful login (see {@code Passwords.needsRehash}).
 */
@ConfigMapping(prefix = "password.hash.argon2")
public interface Argon2Config {

    /**
     * Default memory cost in kibibytes (96&nbsp;MiB).
     */
    String DEFAULT_MEMORY_KIB = "98304";

    /**
     * Default number of iterations (passes over memory).
     */
    String DEFAULT_ITERATIONS = "3";

    /**
     * Default degree of parallelism (lanes).
     */
    String DEFAULT_PARALLELISM = "4";

    /**
     * Memory cost in kibibytes — the size of the memory block Argon2id fills while hashing. This is the dominant defence against GPU/ASIC cracking
     * and the parameter to raise first when tuning.
     *
     * @return the memory cost in KiB, defaulting to {@value #DEFAULT_MEMORY_KIB} (96&nbsp;MiB)
     */
    @WithDefault(DEFAULT_MEMORY_KIB)
    int memoryKib();

    /**
     * Number of iterations (time cost) — how many passes Argon2id makes over the memory block. Linearly scales the hashing time for a fixed memory
     * cost.
     *
     * @return the iteration count, defaulting to {@value #DEFAULT_ITERATIONS}
     */
    @WithDefault(DEFAULT_ITERATIONS)
    int iterations();

    /**
     * Degree of parallelism — the number of independent lanes Argon2id computes. The total work (and so the security) is fixed by
     * {@link #memoryKib()} and {@link #iterations()}; parallelism only spreads that work, which password4j runs across real threads (one per lane),
     * reducing wall-clock roughly linearly on a multi-core host. The default of {@value #DEFAULT_PARALLELISM} is what lets the 96&nbsp;MiB memory
     * cost stay near ~130&nbsp;ms; each concurrent login consumes that many cores, so lower it (and the memory) on core-constrained hardware.
     *
     * @return the parallelism, defaulting to {@value #DEFAULT_PARALLELISM}
     */
    @WithDefault(DEFAULT_PARALLELISM)
    int parallelism();
}
