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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Pure helpers for opaque session tokens: minting a fresh token, hashing it for storage/lookup, and deciding whether a stored session is still valid.
 * Kept free of any persistence or request state so the branching (the validity boundaries) is deterministically unit-testable.
 */
final class SessionTokens {

    // 32 bytes = 256 bits of entropy; ample to make the base64url token unguessable.
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SessionTokens() {

    }

    /**
     * Mints a fresh, high-entropy opaque token as a URL-safe base64 string. This is the only form the client ever sees; the store persists
     * {@link #hash(String)} of it, not the token itself.
     */
    static String generate() {
        final byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * Computes the SHA-256 hash of a raw token, used as the stored/lookup key. Deterministic, so the same token always resolves to the same row.
     */
    static byte[] hash(final String rawToken) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm, so this is unreachable in practice.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Whether a session is still usable at {@code now}: it must be before both its absolute {@code expiresAt} and its sliding idle deadline
     * ({@code lastUsedAt + idleTimeout}). The boundary instant itself counts as expired.
     */
    static boolean isValid(final Instant lastUsedAt, final Instant expiresAt, final Instant now, final Duration idleTimeout) {
        return now.isBefore(expiresAt) && now.isBefore(lastUsedAt.plus(idleTimeout));
    }

    /**
     * Whether {@code lastUsedAt} is stale enough to be worth rewriting at {@code now}. Used to coalesce the per-request "touch" so a busy client does
     * not issue an {@code UPDATE} on every single request; with an idle timeout measured in days, a minute of granularity is immaterial.
     */
    static boolean shouldBumpLastUsed(final Instant lastUsedAt, final Instant now, final Duration minInterval) {
        return now.isAfter(lastUsedAt.plus(minInterval));
    }
}
