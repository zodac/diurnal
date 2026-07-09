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

import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.zodac.diurnal.config.Argon2Config;

/**
 * Single source of truth for password hashing and verification.
 *
 * <p>Passwords are hashed with <b>Argon2id</b> (a memory-hard function resistant to GPU/ASIC cracking),
 * tuned via {@link Argon2Config}; the resulting PHC string (e.g. {@code $argon2id$v=19$m=65536,t=3,p=1$...})
 * embeds a unique, per-password salt and every cost parameter, and the plaintext is never persisted.
 * Because each hash records the parameters it was made with, verification still works after the cost is
 * retuned; a caller upgrades an out-of-date hash to the current cost by re-hashing on the next successful
 * login (see {@link #needsRehash(String)}). All hashing and verification across the web and REST layers
 * routes through here, so the algorithm and its cost are defined in exactly one place. The rules a raw
 * password must satisfy live alongside in {@link PasswordConstraints}.
 */
@ApplicationScoped
public class Passwords {

    // Length of the derived Argon2id hash, in bytes.
    private static final int HASH_LENGTH_BYTES = 32;

    // A throwaway value hashed once, at the current Argon2id cost, at startup. Verifying a submitted
    // password against it spends the same time as a genuine check, so a login for an account that does
    // not exist (or has no password hash) cannot be told apart from a wrong password by response time.
    // See matchesDummy(String).
    private static final String DUMMY_PASSWORD = "diurnal-dummy-password";

    private final Argon2Function argon2;
    private final String dummyHash;

    /**
     * Builds the password service, pinning the Argon2id cost parameters from configuration.
     *
     * @param argon2Config the tunable Argon2id cost parameters
     */
    @Inject
    public Passwords(final Argon2Config argon2Config) {
        this.argon2 = Argon2Function.getInstance(argon2Config.memoryKib(), argon2Config.iterations(),
                argon2Config.parallelism(), HASH_LENGTH_BYTES, Argon2.ID);
        this.dummyHash = argon2.hash(DUMMY_PASSWORD).getResult();
    }

    /**
     * Hashes a raw password with Argon2id and a freshly-generated per-password salt.
     *
     * @param rawPassword the plaintext password to hash
     * @return the Argon2id PHC hash string to persist
     */
    public String hash(final String rawPassword) {
        return argon2.hash(rawPassword).getResult();
    }

    /**
     * Verifies a raw password against a stored Argon2id hash, using the exact cost parameters the hash
     * embeds — so a hash made under earlier settings still verifies after the cost has been retuned.
     *
     * @param rawPassword  the plaintext password to check
     * @param passwordHash the stored Argon2id hash to verify against
     * @return {@code true} if the password matches the hash
     */
    public boolean matches(final String rawPassword, final String passwordHash) {
        return Argon2Function.getInstanceFromHash(passwordHash).check(rawPassword, passwordHash);
    }

    /**
     * Whether a stored hash should be re-hashed after a successful verification — i.e. its embedded cost
     * parameters no longer match the current configuration. Callers persist a fresh {@link #hash(String)}
     * when this returns {@code true}, transparently bringing accounts up to the current cost as their
     * owners log in.
     *
     * @param passwordHash the stored hash the caller just verified successfully
     * @return {@code true} if the hash should be upgraded to the current Argon2id parameters
     */
    public boolean needsRehash(final String passwordHash) {
        return !argon2.equals(Argon2Function.getInstanceFromHash(passwordHash));
    }

    /**
     * Verifies a raw password against a fixed, throwaway Argon2id hash and always fails. Its purpose is
     * purely to spend the same time as a real {@link #matches(String, String)} check when there is no
     * stored hash to verify against — so a login for a non-existent (or password-less) account is
     * indistinguishable from a wrong password by response time, closing a user-enumeration timing side
     * channel.
     *
     * @param rawPassword the plaintext password to verify against the throwaway hash
     * @return {@code false}, always
     */
    public boolean matchesDummy(final String rawPassword) {
        argon2.check(rawPassword, dummyHash);
        return false;
    }
}
