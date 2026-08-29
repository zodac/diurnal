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

package net.zodac.diurnal.auth;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Typed view over the {@code password.hash.argon2.*} settings that tune the Argon2id password hashing cost.
 *
 * <p>
 * The three cost parameters should be tuned so a single hash takes long enough to frustrate offline brute-forcing of a leaked hash, but not so long
 * that it hurts legitimate logins. The defaults are OWASP's primary Argon2id recommendation (19 MiB, 2 iterations, 1 lane), measured at ~54 ms per
 * hash on the reference host (an 8-core AMD Ryzen 7 5700X).
 *
 * <p>
 * These parameters size the JVM heap more than anything else in the application, because password4j is a pure-Java implementation: the memory cost
 * is a {@code long[][]} on the Java heap, not native memory. Two distinct costs follow, both measured rather than derived:
 *
 * <p>
 * The first is <b>retained</b> - a block of {@link #memoryKib()} is held for the life of the process, because password4j caches each
 * {@code Argon2Function} in a static map that is never evicted. The second is <b>transient</b> - roughly {@code memoryKib x (2 x iterations + 1)}
 * is allocated per hash, which is 97 MiB at these defaults. A burst of concurrent logins multiplies that second term, and the per-IP lockout
 * ({@code auth.ip-throttle.max-attempts}) is what bounds the burst.
 *
 * <p>
 * Re-measure and adjust on other hardware - raise {@link #memoryKib()} first on a faster or higher-core machine, lower it on a constrained one.
 * Changing any parameter transparently re-hashes each account on its next successful login (see {@code Passwords.needsRehash}), but the superseded
 * parameters are revived by every not-yet-upgraded hash and their retained block then stays cached until the next restart - so a migration
 * temporarily costs the sum of both, and is worth following with a restart once every account has logged in.
 */
@ConfigMapping(prefix = "password.hash.argon2")
public interface Argon2Config {

    /**
     * Memory cost in kibibytes - the size of the memory block Argon2id fills while hashing. This is the dominant defence against GPU/ASIC cracking,
     * the parameter to raise first when tuning, and the one that decides the retained and transient heap costs described on the type.
     *
     * @return the memory cost in KiB, defaulting to {@code 19 MiB}
     */
    @WithDefault("19456")
    int memoryKib();

    /**
     * Number of iterations (time cost) - how many passes Argon2id makes over the memory block. Linearly scales the hashing time for a fixed memory
     * cost, and superlinearly scales the transient allocation per hash (see the type's Javadoc).
     *
     * @return the iteration count, defaulting to {@code 2}
     */
    @WithDefault("2")
    int iterations();

    /**
     * Degree of parallelism - the number of independent lanes Argon2id computes. The total work (and so the security) is fixed by
     * {@link #memoryKib()} and {@link #iterations()}; parallelism only spreads that work, which password4j runs across real threads (one per lane),
     * reducing wall-clock roughly linearly on a multi-core host at no change in allocation. It defaults to a single lane, matching OWASP's
     * recommendation: each concurrent login otherwise occupies that many cores, and the executor handoff was measurably the second-largest source of
     * garbage in the application before it was lowered.
     *
     * @return the parallelism, defaulting to 1
     */
    @WithDefault("1")
    int parallelism();
}
