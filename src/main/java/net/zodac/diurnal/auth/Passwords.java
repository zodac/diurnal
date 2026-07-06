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

import org.mindrot.jbcrypt.BCrypt;

/**
 * Single source of truth for password hashing, verification and length rules.
 *
 * <p>Passwords are stored only as one-way {@link BCrypt} hashes with a unique, per-password salt embedded in the hash
 * string — the plaintext is never persisted. All hashing and verification across the web and REST layers routes through
 * here so the cost factor is defined in exactly one place. The rules a raw password must satisfy live alongside in
 * {@link PasswordConstraints}.
 */
public final class Passwords {

    // BCrypt work factor (2^COST rounds). Higher is slower and more resistant to brute force.
    private static final int COST = 12;

    private Passwords() {

    }

    /**
     * Hashes a raw password with a freshly-generated per-password salt.
     *
     * @param rawPassword the plaintext password to hash
     * @return the salted BCrypt hash to persist
     */
    public static String hash(final String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(COST));
    }

    /**
     * Verifies a raw password against a stored BCrypt hash.
     *
     * @param rawPassword  the plaintext password to check
     * @param passwordHash the stored BCrypt hash to verify against
     * @return {@code true} if the password matches the hash
     */
    public static boolean matches(final String rawPassword, final String passwordHash) {
        return BCrypt.checkpw(rawPassword, passwordHash);
    }
}
