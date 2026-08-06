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

package net.zodac.diurnal.crypto;

import java.util.Base64;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Reads the application's configured master key out of its base64 form, and says what is wrong with it when it cannot.
 *
 * <p>
 * Kept apart from the configuration binding and from the startup check so the rules — present, valid base64, exactly the right length — are a pure
 * function that can be exercised without booting anything.
 */
public final class MasterKey {

    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private MasterKey() {

    }

    /**
     * Decodes a configured master key.
     *
     * @param configured the base64-encoded value from configuration
     * @return the decoded key
     * @throws IllegalStateException if the value is absent, not valid base64, or the wrong length — all of which are caught at startup, so reaching
     *     this from a request means the configuration changed underneath a running application
     */
    public static byte[] decode(final String configured) {
        final Optional<String> problem = validate(configured);
        if (problem.isPresent()) {
            throw new IllegalStateException(problem.get());
        }
        return DECODER.decode(configured.strip());
    }

    /**
     * Checks a configured master key without throwing, describing the problem when there is one.
     *
     * <p>
     * The messages are plain ASCII and name no value: this is read by an operator at startup, and the one thing that must never end up in a log is
     * the key itself.
     *
     * @param configured the base64-encoded value from configuration, which may be absent
     * @return the problem, or empty when the value is usable
     */
    public static Optional<String> validate(final @Nullable String configured) {
        if (configured == null || configured.isBlank()) {
            return Optional.of("NOTE_ENCRYPTION_KEY is not set - notes are encrypted at rest and cannot be read or written without it. "
                + "Generate one with: openssl rand -base64 32");
        }

        final byte[] decoded;
        try {
            decoded = DECODER.decode(configured.strip());
        } catch (final IllegalArgumentException e) {
            return Optional.of("NOTE_ENCRYPTION_KEY is not valid base64. Generate one with: openssl rand -base64 32");
        }

        if (decoded.length != Aes256Gcm.KEY_BYTES) {
            return Optional.of("NOTE_ENCRYPTION_KEY must decode to exactly " + Aes256Gcm.KEY_BYTES + " bytes, but decoded to "
                + decoded.length + ". Generate one with: openssl rand -base64 32");
        }
        return Optional.empty();
    }
}
